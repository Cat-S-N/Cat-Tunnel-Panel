package com.admin.common.utils;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AlertNotifierTest {

    @Test
    void targetDetectionRequiresCompleteChannel() {
        Map<String, String> empty = new HashMap<>();
        assertFalse(AlertNotifier.hasAnyTarget(empty));

        Map<String, String> onlyToken = new HashMap<>();
        onlyToken.put("tg_bot_token", "123:abc");
        assertFalse(AlertNotifier.hasAnyTarget(onlyToken));

        Map<String, String> tgReady = new HashMap<>();
        tgReady.put("tg_bot_token", "123:abc");
        tgReady.put("tg_chat_id", "10086");
        assertTrue(AlertNotifier.hasAnyTarget(tgReady));

        Map<String, String> hookReady = new HashMap<>();
        hookReady.put("webhook_url", "https://example.com/hook");
        assertTrue(AlertNotifier.hasAnyTarget(hookReady));
    }

    @Test
    void webhookPayloadAdaptsPlatformFormats() {
        String feishu = AlertNotifier.buildWebhookBody("https://open.feishu.cn/open-apis/bot/v2/hook/xxx", "T", "C");
        assertTrue(feishu.contains("\"msg_type\":\"text\""));
        assertTrue(feishu.contains("\"text\":\"T\\nC\""));

        String ding = AlertNotifier.buildWebhookBody("https://oapi.dingtalk.com/robot/send?access_token=1", "T", "C");
        assertTrue(ding.contains("\"msgtype\":\"text\""));

        String generic = AlertNotifier.buildWebhookBody("https://hooks.example.com/notify", "T", "C");
        assertTrue(generic.contains("\"title\":\"T\""));
        assertTrue(generic.contains("\"content\":\"C\""));
    }

    @Test
    void formatDurationHumanReadable() {
        assertEquals("45 秒", AlertNotifier.formatDuration(45_000));
        assertEquals("5 分钟", AlertNotifier.formatDuration(300_000));
        assertEquals("2 小时 5 分钟", AlertNotifier.formatDuration((2 * 60 + 5) * 60_000L));
    }
}
