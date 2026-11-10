package com.dbsync.common.enums;

/**
 * 环境类型。{@link #PROD} 触发"破坏性操作二次确认"与"双人复核"要求。
 */
public enum EnvType {
    /** 生产。 */
    PROD,
    /** 预发/测试。 */
    UAT,
    /** 开发。 */
    DEV;

    /**
     * @return true 表示生产环境
     */
    public boolean isProd() {
        return this == PROD;
    }
}
