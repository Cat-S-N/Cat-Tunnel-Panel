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
        String raw = "{\"tg_bot_token\":\"123:secret\",\"app_name\":\"Cat Tunnel\"}";
        String masked = AuditLogStore.maskSensitiveParams(raw);
        assertFalse(masked.contains("123:secret"));
        assertTrue(masked.contains("\"tg_bot_token\":\"***\""));
        assertTrue(masked.contains("Cat Tunnel"));

        String longInput = "a".repeat(1200);
        String cut = AuditLogStore.maskSensitiveParams(longInput);
        assertTrue(cut.endsWith("..."));
        assertEquals(953, cut.length());
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
}
