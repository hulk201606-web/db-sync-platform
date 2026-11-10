package com.dbsync.common.options;

/**
 * 触发器选项（不可变）。
 */
public final class TriggerOptions {

    private final String logTableSuffix;
    private final boolean includeBeforeImage;
    private final boolean dropLogTableOnUninstall;

    /**
     * 构造。
     *
     * @param logTableSuffix            日志表后缀
     * @param includeBeforeImage        是否记录前像
     * @param dropLogTableOnUninstall   卸载时是否删日志表
     */
    public TriggerOptions(String logTableSuffix, boolean includeBeforeImage,
                          boolean dropLogTableOnUninstall) {
        this.logTableSuffix = logTableSuffix == null || logTableSuffix.isBlank()
                ? "_SYNC_LOG" : logTableSuffix;
        this.includeBeforeImage = includeBeforeImage;
        this.dropLogTableOnUninstall = dropLogTableOnUninstall;
    }

    /**
     * @return 默认选项
     */
    public static TriggerOptions defaults() {
        return new TriggerOptions("_SYNC_LOG", true, false);
    }

    /** @return 日志表后缀 */
    public String getLogTableSuffix() {
        return logTableSuffix;
    }

    /** @return 是否记录前像 */
    public boolean isIncludeBeforeImage() {
        return includeBeforeImage;
    }

    /** @return 卸载时是否删日志表 */
    public boolean isDropLogTableOnUninstall() {
        return dropLogTableOnUninstall;
    }
}
