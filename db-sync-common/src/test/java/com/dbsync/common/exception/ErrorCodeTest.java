package com.dbsync.common.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ErrorCode / 异常体系单测：错误码唯一、段位与归属一致、模板参数个数可校验。
 */
class ErrorCodeTest {

    @Test
    @DisplayName("所有错误码唯一")
    void should_beUnique_When_scanAllCodes() {
        Set<String> codes = new HashSet<>();
        for (ErrorCode ec : ErrorCode.values()) {
            assertThat(codes.add(ec.code())).as("重复错误码 %s", ec.code()).isTrue();
        }
        assertThat(codes.size()).isGreaterThanOrEqualTo(40);
    }

    @Test
    @DisplayName("错误码格式与段位匹配")
    void should_matchSegment_When_codeGiven() {
        for (ErrorCode ec : ErrorCode.values()) {
            assertThat(ec.code()).matches("^E\\d{4}$");
            int first = Integer.parseInt(ec.code().substring(1, 2));
            assertThat(ec.segment()).isEqualTo(first * 1000);
        }
    }

    @Test
    @DisplayName("format 按占位符个数填充")
    void should_format_When_argsProvided() {
        assertThat(ErrorCode.PARAM_INVALID.argCount()).isEqualTo(1);
        assertThat(ErrorCode.PARAM_INVALID.format("id 为空")).contains("id 为空");
        assertThat(ErrorCode.DRIVER_MISSING.format("oracle.jdbc.OracleDriver"))
                .contains("oracle.jdbc.OracleDriver");
    }

    @Test
    @DisplayName("retryable 标记符合架构约定")
    void should_markRetryable_When_configured() {
        assertThat(ErrorCode.CONN_NETWORK_FAILED.retryable()).isFalse();
        assertThat(ErrorCode.POOL_LIMIT_EXCEEDED.retryable()).isFalse();
        assertThat(ErrorCode.CHANGE_APPLY_FAILED.retryable()).isTrue();
        assertThat(ErrorCode.RETRY_EXHAUSTED.retryable()).isTrue();
    }

    @Test
    @DisplayName("DbSyncException 保留错误码与上下文")
    void should_keepErrorCodeAndContext_When_wrapped() {
        IllegalStateException root = new IllegalStateException("boom");
        DbSyncException ex = new DbSyncException(ErrorCode.PRECHECK_FAILED, root, "目标表缺失")
                .with("taskId", 1001L);
        assertThat(ex.errorCode()).isEqualTo(ErrorCode.PRECHECK_FAILED);
        assertThat(ex.getCause()).isSameAs(root);
        assertThat(ex.getMessage()).contains("目标表缺失");
        assertThat(ex.context()).containsEntry("taskId", 1001L);
        assertThat(ex.isRetryable()).isFalse();
    }

    @Test
    @DisplayName("子异常保留错误码与根因")
    void should_keepErrorCode_When_subclassConstructed() {
        IllegalArgumentException cause = new IllegalArgumentException("bad");
        assertThat(new DialectException(cause, "merge").errorCode())
                .isEqualTo(ErrorCode.DIALECT_UNSUPPORTED);
        assertThat(new MetadataReadException(cause, "all_tables").getCause()).isSameAs(cause);
        assertThat(new DriverMissingException("com.ibm.db2.jcc.DB2Driver", "见 lib/").errorCode())
                .isEqualTo(ErrorCode.DRIVER_MISSING);
        assertThat(new LeaseLostException(8L, "host:8080#1").errorCode())
                .isEqualTo(ErrorCode.OPTIMISTIC_LOCK_CONFLICT);
        assertThat(new IllegalStateTransitionException(com.dbsync.common.enums.CutoverState.DRAFT,
                com.dbsync.common.enums.CutoverState.GRAY_WRITE,
                java.util.List.of(com.dbsync.common.enums.CutoverState.PRE_CHECK))
                .getMessage()).contains("allowed=[PRE_CHECK]");
        assertThat(new GateBlockedException(java.util.List.of("G4 死信非 0"),
                java.util.List.of()).context()).containsKey("reasons");
        assertThatThrownBy(() -> {
            throw new DbSyncException(ErrorCode.IDENTIFIER_INVALID, "t; DROP TABLE x");
        }).isInstanceOf(DbSyncException.class);
    }
}
