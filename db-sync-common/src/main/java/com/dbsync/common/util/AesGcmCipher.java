package com.dbsync.common.util;

import com.dbsync.common.exception.DbSyncException;
import com.dbsync.common.exception.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM 加解密（架构 §12.1）。
 *
 * <p>密文格式：{@code Base64(IV(12B) || ciphertext || tag(16B))}。
 * 密钥派生：{@code HKDF-SHA256(masterKey, salt="dbsync-ds", info=keyVersion)} → 32 字节。
 *
 * <p>轮换：新写入用 {@code currentVersion}；读取时按行上的 {@code key_version} 选密钥，
 * 旧版本（{@code currentVersion-1}）仍可解密，因此可以"先换密钥再逐步重加密"。
 *
 * <p>线程安全：本类无可变状态（{@link Cipher} 每次新建），可单例复用。
 */
public final class AesGcmCipher {

    private static final String HKDF_SALT = "dbsync-ds";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_LENGTH = 32;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private final byte[] currentKey;
    private final byte[] previousKey;
    private final short currentVersion;
    private final SecureRandom random = new SecureRandom();

    /**
     * 构造。
     *
     * @param masterKey      主密钥（建议 32 字节），不可空
     * @param previousKey    上一版主密钥（轮换期用于解密旧数据），可 null
     * @param currentVersion 当前密钥版本（≥1）
     */
    public AesGcmCipher(byte[] masterKey, byte[] previousKey, short currentVersion) {
        if (masterKey == null || masterKey.length == 0) {
            throw new IllegalArgumentException("masterKey 不可为空：请设置环境变量 DBSYNC_MASTER_KEY");
        }
        if (currentVersion < 1) {
            throw new IllegalArgumentException("keyVersion 必须 ≥ 1");
        }
        this.currentVersion = currentVersion;
        this.currentKey = hkdf(masterKey, currentVersion);
        this.previousKey = previousKey == null || previousKey.length == 0
                ? null : hkdf(previousKey, (short) (currentVersion - 1));
    }

    /**
     * 生成 32 字节随机主密钥。
     *
     * @return 主密钥
     */
    public static byte[] generateKey() {
        byte[] key = new byte[KEY_LENGTH];
        new SecureRandom().nextBytes(key);
        return key;
    }

    /**
     * 加密。
     *
     * @param plain 明文；null 返回 null
     * @return Base64(iv||ct||tag)
     * @throws DbSyncException {@code E1010} 加解密失败
     */
    public String encrypt(String plain) {
        if (plain == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(currentKey, "AES"),
                    new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new DbSyncException(ErrorCode.CRYPTO_FAILED, e, "ENCRYPT");
        }
    }

    /**
     * 解密。
     *
     * @param cipherText Base64(iv||ct||tag)
     * @param keyVersion 该密文使用的密钥版本
     * @return 明文；入参 null 返回 null
     * @throws DbSyncException {@code E1010} 解密失败（含密文被篡改、密钥版本不匹配）
     */
    public String decrypt(String cipherText, short keyVersion) {
        if (cipherText == null) {
            return null;
        }
        byte[] key = keyFor(keyVersion);
        try {
            byte[] all = Base64.getDecoder().decode(cipherText);
            if (all.length <= IV_LENGTH) {
                throw new DbSyncException(ErrorCode.CRYPTO_FAILED, "密文长度非法");
            }
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(all, 0, iv, 0, IV_LENGTH);
            byte[] ct = new byte[all.length - IV_LENGTH];
            System.arraycopy(all, IV_LENGTH, ct, 0, ct.length);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new DbSyncException(ErrorCode.CRYPTO_FAILED, e, "DECRYPT(v" + keyVersion + ")");
        }
    }

    /**
     * @return 当前密钥版本
     */
    public short currentVersion() {
        return currentVersion;
    }

    /**
     * 按版本取密钥。
     *
     * @param keyVersion 版本
     * @return 32 字节密钥
     */
    private byte[] keyFor(short keyVersion) {
        if (keyVersion == currentVersion) {
            return currentKey;
        }
        if (previousKey != null && keyVersion == currentVersion - 1) {
            return previousKey;
        }
        throw new DbSyncException(ErrorCode.CRYPTO_FAILED, "无可用密钥版本：" + keyVersion);
    }

    /**
     * HKDF-SHA256（extract + expand 单块）。
     *
     * @param ikm   主密钥
     * @param info  版本号（作为 info 字节）
     * @return 32 字节密钥
     */
    private static byte[] hkdf(byte[] ikm, short info) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            // extract
            mac.init(new SecretKeySpec(HKDF_SALT.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] prk = mac.doFinal(ikm);
            // expand（单块足够 32 字节）
            mac.init(new SecretKeySpec(prk, "HmacSHA256"));
            mac.update(String.valueOf(info).getBytes(StandardCharsets.UTF_8));
            mac.update((byte) 0x01);
            byte[] okm = mac.doFinal();
            byte[] key = new byte[KEY_LENGTH];
            System.arraycopy(okm, 0, key, 0, KEY_LENGTH);
            return key;
        } catch (GeneralSecurityException e) {
            throw new DbSyncException(ErrorCode.CRYPTO_FAILED, e, "HKDF");
        }
    }
}
