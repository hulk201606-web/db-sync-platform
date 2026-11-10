package com.dbsync.common.model;

import com.dbsync.common.enums.DbType;
import com.dbsync.common.enums.EnvType;
import com.dbsync.common.enums.IdentifierCase;
import java.util.Objects;

/**
 * 数据源定义（不可变）。{@code passwordCipher} 是 AES-256-GCM 密文，永不含明文。
 */
public final class DataSourceDef {

    private final long id;
    private final String name;
    private final DbType dbType;
    private final EnvType env;
    private final JdbcEndpoint endpoint;
    private final String username;
    private final String passwordCipher;
    private final short keyVersion;
    private final IdentifierCase identifierCase;
    private final int poolMaxSize;
    private final int poolMinIdle;
    private final int fetchSize;
    private final String healthStatus;

    private DataSourceDef(Builder b) {
        this.id = b.id;
        this.name = Objects.requireNonNull(b.name, "name");
        this.dbType = Objects.requireNonNull(b.dbType, "dbType");
        this.env = b.env == null ? EnvType.UAT : b.env;
        this.endpoint = Objects.requireNonNull(b.endpoint, "endpoint");
        this.username = Objects.requireNonNull(b.username, "username");
        this.passwordCipher = b.passwordCipher;
        this.keyVersion = b.keyVersion;
        this.identifierCase = b.identifierCase == null ? IdentifierCase.LOWER : b.identifierCase;
        this.poolMaxSize = b.poolMaxSize <= 0 ? 8 : b.poolMaxSize;
        this.poolMinIdle = Math.max(0, b.poolMinIdle);
        this.fetchSize = b.fetchSize <= 0 ? 1000 : b.fetchSize;
        this.healthStatus = b.healthStatus == null ? "UNKNOWN" : b.healthStatus;
    }

    /**
     * @param id   数据源 ID
     * @param name 名称
     * @return builder
     */
    public static Builder builder(long id, String name) {
        return new Builder(id, name);
    }

    /** @return 数据源 ID */
    public long getId() {
        return id;
    }

    /** @return 名称 */
    public String getName() {
        return name;
    }

    /** @return 数据库类型 */
    public DbType getDbType() {
        return dbType;
    }

    /** @return 环境 */
    public EnvType getEnv() {
        return env;
    }

    /** @return 端点（无密码） */
    public JdbcEndpoint getEndpoint() {
        return endpoint;
    }

    /** @return 用户名 */
    public String getUsername() {
        return username;
    }

    /** @return 密码密文（Base64(iv||ct||tag)） */
    public String getPasswordCipher() {
        return passwordCipher;
    }

    /** @return 主密钥版本 */
    public short getKeyVersion() {
        return keyVersion;
    }

    /** @return 标识符大小写策略 */
    public IdentifierCase getIdentifierCase() {
        return identifierCase;
    }

    /** @return 连接池上限 */
    public int getPoolMaxSize() {
        return poolMaxSize;
    }

    /** @return 连接池最小空闲 */
    public int getPoolMinIdle() {
        return poolMinIdle;
    }

    /** @return fetchSize 建议值 */
    public int getFetchSize() {
        return fetchSize;
    }

    /** @return 健康状态（UP/DOWN/SLOW/UNKNOWN） */
    public String getHealthStatus() {
        return healthStatus;
    }

    @Override
    public String toString() {
        return "DataSourceDef#" + id + "(" + name + "," + dbType + "," + env + "," + endpoint + ")";
    }

    /** Builder。 */
    public static final class Builder {
        private final long id;
        private final String name;
        private DbType dbType;
        private EnvType env = EnvType.UAT;
        private JdbcEndpoint endpoint;
        private String username;
        private String passwordCipher;
        private short keyVersion = 1;
        private IdentifierCase identifierCase = IdentifierCase.LOWER;
        private int poolMaxSize = 8;
        private int poolMinIdle = 2;
        private int fetchSize = 1000;
        private String healthStatus = "UNKNOWN";

        private Builder(long id, String name) {
            this.id = id;
            this.name = name;
        }

        /**
         * @param v 类型
         * @return this
         */
        public Builder dbType(DbType v) {
            this.dbType = v;
            return this;
        }

        /**
         * @param v 环境
         * @return this
         */
        public Builder env(EnvType v) {
            this.env = v;
            return this;
        }

        /**
         * @param v 端点
         * @return this
         */
        public Builder endpoint(JdbcEndpoint v) {
            this.endpoint = v;
            return this;
        }

        /**
         * @param v 用户名
         * @return this
         */
        public Builder username(String v) {
            this.username = v;
            return this;
        }

        /**
         * @param v 密码密文
         * @return this
         */
        public Builder passwordCipher(String v) {
            this.passwordCipher = v;
            return this;
        }

        /**
         * @param v 主密钥版本
         * @return this
         */
        public Builder keyVersion(short v) {
            this.keyVersion = v;
            return this;
        }

        /**
         * @param v 标识符大小写
         * @return this
         */
        public Builder identifierCase(IdentifierCase v) {
            this.identifierCase = v;
            return this;
        }

        /**
         * @param v 池上限
         * @return this
         */
        public Builder poolMaxSize(int v) {
            this.poolMaxSize = v;
            return this;
        }

        /**
         * @param v 最小空闲
         * @return this
         */
        public Builder poolMinIdle(int v) {
            this.poolMinIdle = v;
            return this;
        }

        /**
         * @param v fetchSize
         * @return this
         */
        public Builder fetchSize(int v) {
            this.fetchSize = v;
            return this;
        }

        /**
         * @param v 健康状态
         * @return this
         */
        public Builder healthStatus(String v) {
            this.healthStatus = v;
            return this;
        }

        /**
         * @return 不可变定义
         */
        public DataSourceDef build() {
            return new DataSourceDef(this);
        }
    }
}
