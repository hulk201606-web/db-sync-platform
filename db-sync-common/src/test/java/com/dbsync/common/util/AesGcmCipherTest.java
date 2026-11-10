package com.dbsync.common.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dbsync.common.exception.DbSyncException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 加解密单测：往返、篡改检测、密钥轮换、密钥长度。
 */
class AesGcmCipherTest {

    private final byte[] key = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);

    @Test
    @DisplayName("加解密往返")
    void should_roundTrip_When_encryptThenDecrypt() {
        AesGcmCipher cipher = new AesGcmCipher(key, null, (short) 1);
        String ct = cipher.encrypt("Admin@12345");
        assertThat(ct).isNotEqualTo("Admin@12345");
        assertThat(cipher.decrypt(ct, (short) 1)).isEqualTo("Admin@12345");
    }

    @Test
    @DisplayName("同一明文两次加密结果不同（IV 随机）")
    void should_useRandomIv_When_encryptTwice() {
        AesGcmCipher cipher = new AesGcmCipher(key, null, (short) 1);
        assertThat(cipher.encrypt("x")).isNotEqualTo(cipher.encrypt("x"));
    }

    @Test
    @DisplayName("修改密文 1 字节 → GCM 校验失败")
    void should_throw_When_cipherTampered() {
        AesGcmCipher cipher = new AesGcmCipher(key, null, (short) 1);
        String ct = cipher.encrypt("secret");
        byte[] raw = java.util.Base64.getDecoder().decode(ct);
        raw[raw.length - 1] = (byte) (raw[raw.length - 1] ^ 0x01);
        String tampered = java.util.Base64.getEncoder().encodeToString(raw);
        assertThatThrownBy(() -> cipher.decrypt(tampered, (short) 1))
                .isInstanceOf(DbSyncException.class)
                .hasMessageContaining("DECRYPT");
    }

    @Test
    @DisplayName("轮换后旧密钥版本仍可解密")
    void should_decryptOldVersion_When_keyRotated() {
        AesGcmCipher v1 = new AesGcmCipher(key, null, (short) 1);
        String oldCipher = v1.encrypt("legacy-password");

        byte[] newKey = "ffffffffffffffffffffffffffffffff".getBytes(StandardCharsets.UTF_8);
        AesGcmCipher v2 = new AesGcmCipher(newKey, key, (short) 2);
        assertThat(v2.decrypt(oldCipher, (short) 1)).isEqualTo("legacy-password");
        assertThat(v2.decrypt(v2.encrypt("new-password"), (short) 2)).isEqualTo("new-password");
    }

    @Test
    @DisplayName("未知密钥版本抛 E1010")
    void should_throw_When_keyVersionUnknown() {
        AesGcmCipher cipher = new AesGcmCipher(key, null, (short) 1);
        assertThatThrownBy(() -> cipher.decrypt(cipher.encrypt("x"), (short) 9))
                .isInstanceOf(DbSyncException.class);
    }

    @Test
    @DisplayName("generateKey 长度 32 且每次不同")
    void should_generate32Bytes_When_generateKey() {
        byte[] k1 = AesGcmCipher.generateKey();
        byte[] k2 = AesGcmCipher.generateKey();
        assertThat(k1).hasSize(32);
        assertThat(k1).isNotEqualTo(k2);
    }

    @Test
    @DisplayName("masterKey 为空 → 拒绝构造（启动失败语义）")
    void should_throw_When_masterKeyEmpty() {
        assertThatThrownBy(() -> new AesGcmCipher(new byte[0], null, (short) 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("masterKey");
    }
}
