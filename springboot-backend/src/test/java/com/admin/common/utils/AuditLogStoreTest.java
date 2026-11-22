package com.admin.common.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

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
}
