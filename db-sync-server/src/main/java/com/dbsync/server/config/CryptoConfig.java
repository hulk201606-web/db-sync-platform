package com.dbsync.server.config;

import com.dbsync.common.util.AesGcmCipher;
import com.dbsync.common.util.SnowflakeId;
import com.dbsync.core.lease.InstanceIdProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 加密 / ID / 实例标识装配。
 *
 * <p>这三样是"全局单例且无状态"，必须集中一处创建：
 * <ul>
 *   <li>{@link AesGcmCipher}：数据源口令加解密，全局一个（密钥版本一致才能解密历史密文）；</li>
 *   <li>{@link SnowflakeId}：元数据主键，workerId 由主机名派生，多实例不会撞号；</li>
 *   <li>{@link InstanceIdProvider}：{@code host:port#随机串}，租约归属与存活判定都靠它。</li>
 * </ul>
 */
@Configuration
public class CryptoConfig {

    private static final Logger log = LoggerFactory.getLogger(CryptoConfig.class);

    /**
     * AES-GCM 加解密器。
     *
     * @param properties 配置（主密钥必填，缺失时启动已失败）
     * @return 加解密器
     */
    @Bean
    public AesGcmCipher aesGcmCipher(DbSyncProperties properties) {
        AesGcmCipher cipher = new AesGcmCipher(properties.masterKeyBytes(),
                properties.previousMasterKeyBytes(), properties.getKeyVersion());
        log.info("AES-GCM 已就绪 keyVersion={}", Short.valueOf(cipher.currentVersion()));
        return cipher;
    }

    /**
     * 雪花 ID 生成器。
     *
     * @return 生成器
     */
    @Bean
    public SnowflakeId snowflakeId() {
        long workerId = SnowflakeId.defaultWorkerId();
        log.info("雪花 ID workerId={}", Long.valueOf(workerId));
        return new SnowflakeId(workerId);
    }

    /**
     * 实例标识。
     *
     * @param properties 配置
     * @return 提供者
     */
    @Bean
    public InstanceIdProvider instanceIdProvider(DbSyncProperties properties) {
        InstanceIdProvider provider = new InstanceIdProvider(properties.getInstance().getHost(),
                properties.getInstance().getPort());
        log.info("本实例 instanceId={}", provider.instanceId());
        return provider;
    }
}
