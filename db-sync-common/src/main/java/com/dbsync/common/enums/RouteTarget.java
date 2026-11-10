package com.dbsync.common.enums;

/**
 * 路由目标：读/写分别指向源库或目标库。
 */
public enum RouteTarget {
    /** 源库（旧库）。 */
    SOURCE,
    /** 目标库（新库）。 */
    TARGET;

    /**
     * @return 另一个目标
     */
    public RouteTarget opposite() {
        return this == SOURCE ? TARGET : SOURCE;
    }
}
