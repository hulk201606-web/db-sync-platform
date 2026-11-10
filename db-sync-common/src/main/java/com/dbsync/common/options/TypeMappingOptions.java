package com.dbsync.common.options;

/**
 * 类型映射选项（不可变）。
 */
public final class TypeMappingOptions {

    /** 无精度 NUMBER 的默认精度（架构风险 R9：Oracle 无精度 NUMBER）。 */
    public static final int DEFAULT_NUMBER_PRECISION = 38;
    /** 无精度 NUMBER 的默认小数位。 */
    public static final int DEFAULT_NUMBER_SCALE = 10;

    private final boolean preserveLob;
    private final boolean unsignedAsSigned;
    private final int defaultNumberPrecision;
    private final int defaultNumberScale;
    private final int lobSizeBytes;

    /**
     * 构造。
     *
     * @param preserveLob            是否保留 LOB 语义
     * @param unsignedAsSigned       MySQL unsigned 是否降级为有符号
     * @param defaultNumberPrecision 无精度 NUMBER 精度（R9）
     * @param defaultNumberScale     无精度 NUMBER 小数位（R9）
     * @param lobSizeBytes           LOB 目标长度
     */
    public TypeMappingOptions(boolean preserveLob, boolean unsignedAsSigned, int defaultNumberPrecision,
                              int defaultNumberScale, int lobSizeBytes) {
        this.preserveLob = preserveLob;
        this.unsignedAsSigned = unsignedAsSigned;
        this.defaultNumberPrecision = defaultNumberPrecision;
        this.defaultNumberScale = defaultNumberScale;
        this.lobSizeBytes = lobSizeBytes;
    }

    /**
     * @return 默认选项
     */
    public static TypeMappingOptions defaults() {
        return new TypeMappingOptions(true, true, DEFAULT_NUMBER_PRECISION, DEFAULT_NUMBER_SCALE,
                1024 * 1024);
    }

    /** @return 是否保留 LOB 语义 */
    public boolean isPreserveLob() {
        return preserveLob;
    }

    /** @return unsigned 是否降级为有符号 */
    public boolean isUnsignedAsSigned() {
        return unsignedAsSigned;
    }

    /** @return 无精度 NUMBER 精度（R9） */
    public int getDefaultNumberPrecision() {
        return defaultNumberPrecision;
    }

    /** @return 无精度 NUMBER 小数位（R9） */
    public int getDefaultNumberScale() {
        return defaultNumberScale;
    }

    /** @return LOB 目标长度（字节） */
    public int getLobSizeBytes() {
        return lobSizeBytes;
    }
}
