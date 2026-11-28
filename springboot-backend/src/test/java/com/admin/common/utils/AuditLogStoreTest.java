package com.admin.common.utils;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuditLogStoreTest {

    @Test
    void resolvesKnownMutatingControllerActions() {
        assertEquals("启停用户线路",
                AuditLogStore.resolveAction("com.admin.controller.InboundController.lineStatus"));
        assertEquals("分配节点管理权限",
                AuditLogStore.resolveAction("com.admin.controller.UserController.setManagedNodes"));
        assertEquals("删除节点",
                AuditLogStore.resolveAction("NodeController.delete"));
    }

    @Test
    void skipsReadOnlyMethods() {
        assertNull(AuditLogStore.resolveAction("com.admin.controller.NodeController.list"));
        assertNull(AuditLogStore.resolveAction("com.admin.controller.UserController.auditLogs"));
        assertNull(AuditLogStore.resolveAction("com.admin.controller.FlowController.uploadFlowData"));
    }

    @Test
    void masksSensitiveJsonFieldsAndTruncates() {
        String raw = "{\"tg_bot_token\":\"123:secret\",\"register_invite_code\":\"VIP_ONLY_888\",\"inviteCode\":\"VIP_ONLY_888\",\"confirmPassword\":\"pw123\",\"app_name\":\"Cat Tunnel\"}";
        String masked = AuditLogStore.maskSensitiveParams(raw);
        assertFalse(masked.contains("123:secret"));
        assertFalse(masked.contains("VIP_ONLY_888"));
        assertFalse(masked.contains("pw123"));
        assertTrue(masked.contains("\"tg_bot_token\":\"***\""));
        assertTrue(masked.contains("\"register_invite_code\":\"***\""));
        assertTrue(masked.contains("\"inviteCode\":\"***\""));
        assertTrue(masked.contains("Cat Tunnel"));

        String longInput = "a".repeat(1200);
        String cut = AuditLogStore.maskSensitiveParams(longInput);
        assertTrue(cut.endsWith("..."));
        assertEquals(953, cut.length());
    }

    @Test
    void writeUserRegisterRow_persistsUsernameAndIpWithoutSecrets() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AuditLogStore store = new AuditLogStore();
        ReflectionTestUtils.setField(store, "jdbc", jdbc);
        ReflectionTestUtils.setField(store, "available", true);

        long now = 1700000000000L;
        store.writeUserRegisterRow(18L, "new_rider", "203.0.113.42", "{\"username\":\"new_rider\",\"inviteUsed\":true}", now);

        verify(jdbc).update(
                contains("INSERT INTO `audit_log`"),
                eq(18L),
                eq("new_rider"),
                eq("用户"),
                eq("用户自助注册"),
                eq("UserController.register"),
                eq("203.0.113.42"),
                eq("{\"username\":\"new_rider\",\"inviteUsed\":true}"),
                eq(0),
                eq(now)
        );
    }

    @Test
    void extractsLoginUsernameFromParams() {
        assertEquals("admin", AuditLogStore.extractLoginUsername("{\"username\":\"admin\",\"password\":\"***\"}"));
        assertEquals("cat", AuditLogStore.extractLoginUsername("{\"user\":\"cat\",\"pwd\":\"***\"}"));
        assertNull(AuditLogStore.extractLoginUsername("{\"password\":\"***\"}"));
    }

    @Test
    void queryFiltersByStatusAndReturnsFailedCount() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AuditLogStore store = new AuditLogStore();
        ReflectionTestUtils.setField(store, "jdbc", jdbc);
        ReflectionTestUtils.setField(store, "available", true);

        when(jdbc.queryForObject(eq("SELECT COUNT(*) FROM `audit_log` WHERE 1=1 AND `result_code` <> 0"), eq(Long.class), any(Object[].class)))
                .thenReturn(3L);
        when(jdbc.queryForObject(eq("SELECT COUNT(*) FROM `audit_log` WHERE 1=1"), eq(Long.class), any(Object[].class)))
                .thenReturn(10L);
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(Collections.emptyList());

        Map<String, Object> allRes = store.query(1, 20, null, null, "all");
        assertEquals(10L, allRes.get("total"));
        assertEquals(3L, allRes.get("failedCount"));

        when(jdbc.queryForObject(contains("AND `result_code` = 0"), eq(Long.class), any(Object[].class)))
                .thenReturn(7L);
        Map<String, Object> successRes = store.query(1, 20, null, null, "success");
        assertEquals(7L, successRes.get("total"));
        assertEquals(3L, successRes.get("failedCount"));
        verify(jdbc).queryForList(contains("AND `result_code` = 0"), eq(20), eq(0));

        Map<String, Object> failedRes = store.query(1, 20, null, null, "failed");
        assertEquals(3L, failedRes.get("failedCount"));
        verify(jdbc).queryForList(contains("AND `result_code` <> 0"), eq(20), eq(0));
    }

    @Test
    void normalizeStatus_handlesCaseWhitespaceAndUnknownValues() {
        assertEquals("all", AuditLogStore.normalizeStatus(null));
        assertEquals("all", AuditLogStore.normalizeStatus(""));
        assertEquals("all", AuditLogStore.normalizeStatus("   "));
        assertEquals("all", AuditLogStore.normalizeStatus("ALL"));
        assertEquals("all", AuditLogStore.normalizeStatus("unknown"));
        assertEquals("success", AuditLogStore.normalizeStatus("  SUCCESS  "));
        assertEquals("failed", AuditLogStore.normalizeStatus(" Failed\t"));
    }

    @Test
    void query_normalizesCaseAndWhitespaceInStatus() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AuditLogStore store = new AuditLogStore();
        ReflectionTestUtils.setField(store, "jdbc", jdbc);
        ReflectionTestUtils.setField(store, "available", true);

        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
                .thenReturn(2L);
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(Collections.emptyList());

        Map<String, Object> upperSuccess = store.query(1, 20, null, null, " SUCCESS ");
        assertEquals(2L, upperSuccess.get("failedCount"));
        verify(jdbc).queryForList(contains("AND `result_code` = 0"), eq(20), eq(0));

        Map<String, Object> mixedFailed = store.query(1, 20, null, null, " Failed ");
        assertEquals(2L, mixedFailed.get("failedCount"));
        verify(jdbc).queryForList(contains("AND `result_code` <> 0"), eq(20), eq(0));
    }

    @Test
    void maskSensitiveParams_redactsEscapedQuotesAndAllPasswordFields() {
        String raw = "{\"newUsername\":\"alice\",\"currentPassword\":\"old\\\"Secret!99\",\"newPassword\":\"new\\\"Pass#88\",\"confirmPassword\":\"new\\\"Pass#88\",\"oldPassword\":\"legacy1\",\"passwd\":\"legacy2\"}";
        String masked = AuditLogStore.maskSensitiveParams(raw);
        assertFalse(masked.contains("Secret!99"), masked);
        assertFalse(masked.contains("Pass#88"), masked);
        assertFalse(masked.contains("legacy1"), masked);
        assertFalse(masked.contains("legacy2"), masked);
        assertTrue(masked.contains("\"newUsername\":\"alice\""), masked);
        assertTrue(masked.contains("\"currentPassword\":\"***\""), masked);
        assertTrue(masked.contains("\"newPassword\":\"***\""), masked);
        assertTrue(masked.contains("\"confirmPassword\":\"***\""), masked);
    }

    @Test
    void writePasswordChangeRow_persistsSanitizedSummaryAndResolvesRoles() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AuditLogStore store = new AuditLogStore();
        ReflectionTestUtils.setField(store, "jdbc", jdbc);
        ReflectionTestUtils.setField(store, "available", true);

        long now = 1700001234567L;
        String safeSummary = "{\"oldUsername\":\"admin_user\",\"newUsername\":\"cat_admin\",\"usernameChanged\":true}";
        store.writePasswordChangeRow(1L, "cat_admin", "超级管理员", "198.51.100.8", safeSummary, now);

        verify(jdbc).update(
                contains("INSERT INTO `audit_log`"),
                eq(1L),
                eq("cat_admin"),
                eq("超级管理员"),
                eq("修改密码"),
                eq("UserController.updatePassword"),
                eq("198.51.100.8"),
                eq(safeSummary),
                eq(0),
                eq(now)
        );

        // 普通用户与节点管理员角色解析验证
        when(jdbc.queryForList(contains("SELECT `managed_nodes`"), eq(String.class), eq(9L)))
                .thenReturn(Collections.singletonList("1,2"));
        store.writePasswordChangeRow(9L, "node_mgr", "普通/节点管理员", "198.51.100.9",
                "{\"oldUsername\":\"node_mgr\",\"newUsername\":\"node_mgr\",\"usernameChanged\":false}", now);
        verify(jdbc).update(
                contains("INSERT INTO `audit_log`"),
                eq(9L),
                eq("node_mgr"),
                eq("节点管理员"),
                eq("修改密码"),
                eq("UserController.updatePassword"),
                eq("198.51.100.9"),
                eq("{\"oldUsername\":\"node_mgr\",\"newUsername\":\"node_mgr\",\"usernameChanged\":false}"),
                eq(0),
                eq(now)
        );
    }

    @Test
    void recordIfAuditable_skipsDuplicateSuccessUpdatePasswordButKeepsFailedAttempt() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AuditLogStore store = new AuditLogStore();
        ReflectionTestUtils.setField(store, "jdbc", jdbc);
        ReflectionTestUtils.setField(store, "available", true);
        // 同步执行线程池任务以便断言
        ReflectionTestUtils.setField(store, "executor", new java.util.concurrent.AbstractExecutorService() {
            @Override public void shutdown() {}
            @Override public java.util.List<Runnable> shutdownNow() { return Collections.emptyList(); }
            @Override public boolean isShutdown() { return false; }
            @Override public boolean isTerminated() { return false; }
            @Override public boolean awaitTermination(long timeout, java.util.concurrent.TimeUnit unit) { return true; }
            @Override public void execute(Runnable command) { command.run(); }
        });

        // 成功改密由 UserServiceImpl.updatePassword 记录，AOP 层不应重复插入
        store.recordIfAuditable(null, "203.0.113.5",
                "com.admin.controller.UserController.updatePassword",
                "{\"newUsername\":\"alice\",\"newPassword\":\"PlainLeak123\"}",
                com.admin.common.lang.R.ok("账号密码修改成功"));
        org.mockito.Mockito.verifyNoInteractions(jdbc);

        // 改密失败时仍记录脱敏后的失败审计日志，且严禁泄露明文密码
        store.recordIfAuditable(null, "203.0.113.5",
                "com.admin.controller.UserController.updatePassword",
                "{\"newUsername\":\"alice\",\"currentPassword\":\"Wrong\\\"Secret99\",\"newPassword\":\"PlainLeak123\"}",
                com.admin.common.lang.R.err("当前密码错误"));
        verify(jdbc).update(
                contains("INSERT INTO `audit_log`"),
                eq(null),
                eq("匿名"),
                eq("-"),
                eq("修改密码"),
                eq("UserController.updatePassword"),
                eq("203.0.113.5"),
                eq("{\"newUsername\":\"alice\",\"currentPassword\":\"***\",\"newPassword\":\"***\"}"),
                org.mockito.ArgumentMatchers.intThat(code -> code != 0),
                org.mockito.ArgumentMatchers.anyLong()
        );
    }
}
