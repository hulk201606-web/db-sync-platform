package com.dbsync.metadata.dao;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 用户 DAO 测试：种子 admin 可读、口令可轮换、禁用后 enabled=0。
 */
class UserDaoTest extends MetaDaoTestSupport {

    @Test
    @DisplayName("should_findSeededAdmin：V2 种子的 admin 可查且带盐")
    void should_findSeededAdmin() {
        UserDao dao = new UserDao(jdbc);
        UserDao.UserRow admin = dao.findByUsername("admin").orElseThrow();
        assertThat(admin.getRole()).isEqualTo("ADMIN");
        assertThat(admin.isEnabled()).isTrue();
        assertThat(admin.getSalt()).hasSize(32);
        assertThat(dao.findAll()).hasSize(1);
        assertThat(dao.findById(admin.getId())).isPresent();
    }

    @Test
    @DisplayName("should_insertAndRotatePassword：新增用户、换口令换盐、可禁用")
    void should_insertAndRotatePassword() {
        UserDao dao = new UserDao(jdbc);
        assertThat(dao.insert("alice", "Alice", "hash1", "salt1", "OPERATOR")).isEqualTo(1);
        assertThat(dao.findByUsername("alice").orElseThrow().getDisplayName()).isEqualTo("Alice");
        assertThat(dao.updatePassword("alice", "hash2", "salt2")).isEqualTo(1);
        UserDao.UserRow alice = dao.findByUsername("alice").orElseThrow();
        assertThat(alice.getPasswordHash()).isEqualTo("hash2");
        assertThat(alice.getSalt()).isEqualTo("salt2");
        assertThat(dao.setEnabled("alice", false)).isEqualTo(1);
        assertThat(dao.findByUsername("alice").orElseThrow().isEnabled()).isFalse();
        assertThat(dao.updateLastLogin("alice")).isEqualTo(1);
        assertThat(dao.findByUsername("alice").orElseThrow().getLastLoginAt()).isNotNull();
    }

    @Test
    @DisplayName("should_rejectDuplicateUsername：uk_user_name 生效")
    void should_rejectDuplicateUsername() {
        UserDao dao = new UserDao(jdbc);
        Throwable thrown = null;
        try {
            dao.insert("admin", "另一个 admin", "h", "s", "ADMIN");
        } catch (RuntimeException e) {
            thrown = e;
        }
        assertThat(thrown).isNotNull();
    }
}
