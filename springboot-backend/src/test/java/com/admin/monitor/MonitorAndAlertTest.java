package com.admin.monitor;

import com.admin.common.annotation.RequireRole;
import com.admin.common.lang.R;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 监控与可观测性核心边界单测：
 * 覆盖 NodeUsage 周期计算/分段统计、MonitorSettings 默认降级、OverviewService 公开脱敏、
 * MonitorController 权限/秘密屏蔽/参数校验及 AlertService 离线/到期/流量去重告警。
 */
@ExtendWith(MockitoExtension.class)
class MonitorAndAlertTest {

    @Mock
    private JdbcTemplate jdbc;

    @Mock
    private ProbeService probeService;

    @Test
    void nodeUsage_cycleStartHandlesMonthBoundariesLeapYearsAndTrafficModes() {
        ZoneId zone = ZoneId.of("UTC");
        // 当天 >= 重置日 -> 取本月重置日 00:00
        ZonedDateTime may20 = ZonedDateTime.of(2026, 5, 20, 14, 30, 0, 0, zone);
        long startThisMonth = NodeUsage.cycleStart(15, may20);
        assertEquals(ZonedDateTime.of(2026, 5, 15, 0, 0, 0, 0, zone).toInstant().toEpochMilli(), startThisMonth);

        // 当天 < 重置日 -> 回退上月，且 31 号在 2 月平年自动截断到 2 月 28 日
        ZonedDateTime mar5NonLeap = ZonedDateTime.of(2026, 3, 5, 8, 0, 0, 0, zone);
        long startFeb28 = NodeUsage.cycleStart(31, mar5NonLeap);
        assertEquals(ZonedDateTime.of(2026, 2, 28, 0, 0, 0, 0, zone).toInstant().toEpochMilli(), startFeb28);

        // 闰年 2 月截断到 2 月 29 日
        ZonedDateTime mar5Leap = ZonedDateTime.of(2028, 3, 5, 8, 0, 0, 0, zone);
        long startFeb29 = NodeUsage.cycleStart(31, mar5Leap);
        assertEquals(ZonedDateTime.of(2028, 2, 29, 0, 0, 0, 0, zone).toInstant().toEpochMilli(), startFeb29);

        // 计费方向模式
        assertEquals(300L, NodeUsage.counted(100L, 200L, "both"));
        assertEquals(200L, NodeUsage.counted(100L, 200L, "out"));
        assertEquals(250L, NodeUsage.counted(250L, 100L, "max"));
    }

    @Test
    void nodeUsage_usageSinceCombinesHourAndMinuteSegmentsWithoutOverlap() {
        NodeUsage usage = new NodeUsage();
        ReflectionTestUtils.setField(usage, "jdbc", jdbc);

        assertTrue(usage.usageSince(Collections.emptyMap()).isEmpty());

        long oldSince = System.currentTimeMillis() - 10L * 24 * MetricCollector.HOUR;
        when(jdbc.queryForList(contains("AND ts < ?"), eq(3L), eq(MetricCollector.GRAN_HOUR), eq(oldSince), anyLong()))
                .thenReturn(List.of(Map.of("rx", 1000L, "tx", 2000L)));
        when(jdbc.queryForList(contains("WHERE node_id = ? AND gran = ? AND ts >= ?"), eq(3L), eq(MetricCollector.GRAN_MIN), anyLong()))
                .thenReturn(List.of(Map.of("rx", 300L, "tx", 400L)));

        Map<Long, long[]> res = usage.usageSince(Collections.singletonMap(3L, oldSince));
        assertArrayEquals(new long[]{1300L, 2400L}, res.get(3L));
    }

    @Test
    void monitorSettings_fallsBackToDefaultsWhenTableMissingAndChecksTelegramReady() {
        MonitorSettings settings = new MonitorSettings();
        ReflectionTestUtils.setField(settings, "jdbc", jdbc);

        when(jdbc.queryForList("SELECT k, v FROM monitor_setting")).thenThrow(new RuntimeException("table missing"));
        assertEquals(3, settings.getInt(MonitorSettings.OFFLINE_MIN));
        assertEquals(0, settings.getInt("non_numeric_missing"));
        assertFalse(settings.telegramReady());

        reset(jdbc);
        when(jdbc.queryForList("SELECT k, v FROM monitor_setting")).thenReturn(List.of(
                Map.of("k", MonitorSettings.TG_TOKEN, "v", "123:bot"),
                Map.of("k", MonitorSettings.TG_CHAT, "v", "-10099")
        ));
        assertTrue(settings.telegramReady());
    }

    @Test
    void overviewService_stripsSensitiveFieldsAndHiddenNodesInPublicMode() {
        NodeUsage usage = mock(NodeUsage.class);
        OverviewService svc = new OverviewService();
        ReflectionTestUtils.setField(svc, "jdbc", jdbc);
        ReflectionTestUtils.setField(svc, "nodeUsage", usage);

        Map<String, Object> publicNode = new LinkedHashMap<>();
        publicNode.put("id", 1L);
        publicNode.put("name", "HK-Internal-1");
        publicNode.put("serverIp", "203.0.113.10");
        publicNode.put("status", 1);
        publicNode.put("version", "1.2.5");
        publicNode.put("expireDate", Date.valueOf("2026-12-31"));
        publicNode.put("quotaGb", 500);
        publicNode.put("resetDay", 1);
        publicNode.put("trafficMode", "both");
        publicNode.put("publicShow", 1);
        publicNode.put("publicName", "香港精品 1");

        Map<String, Object> hiddenNode = new LinkedHashMap<>(publicNode);
        hiddenNode.put("id", 2L);
        hiddenNode.put("publicShow", 0);
        hiddenNode.put("serverIp", "203.0.113.20");

        when(jdbc.queryForList(contains("FROM node n LEFT JOIN monitor_node_meta")))
                .thenReturn(List.of(publicNode, hiddenNode));
        when(jdbc.queryForList(contains("FROM monitor_metric x"), anyLong()))
                .thenReturn(List.of(Map.of("node_id", 1L, "cpu", 12.5, "mem", 35.0, "rxRate", 1024L, "txRate", 2048L)));
        when(jdbc.queryForList(contains("FROM monitor_probe p"), anyLong()))
                .thenReturn(Collections.emptyList());
        when(usage.usageSince(anyMap())).thenReturn(Map.of(1L, new long[]{100L, 200L}, 2L, new long[]{0L, 0L}));

        // 1. 公开状态页模式：隐藏 publicShow=0 节点，替换公开别名，严禁暴露 serverIp / expireDate / quotaGb
        List<Map<String, Object>> pubOut = svc.build(true);
        assertEquals(1, pubOut.size());
        Map<String, Object> pubItem = pubOut.get(0);
        assertEquals("香港精品 1", pubItem.get("name"));
        assertFalse(pubItem.containsKey("serverIp"));
        assertFalse(pubItem.containsKey("expireDate"));
        assertFalse(pubItem.containsKey("quotaGb"));

        // 2. 管理员模式：返回全部节点及完整周期流量与 IP
        List<Map<String, Object>> adminOut = svc.build(false);
        assertEquals(2, adminOut.size());
        assertEquals("203.0.113.10", adminOut.get(0).get("serverIp"));
        assertEquals(300L, adminOut.get(0).get("cycleUsed"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void monitorController_enforcesSuperAdminMasksTelegramTokenAndValidatesInputs() throws Exception {
        // 核验全部端点均受 @RequireRole（allowNodeAdmin=false）保护
        for (Method m : MonitorController.class.getDeclaredMethods()) {
            if (m.isAnnotationPresent(org.springframework.web.bind.annotation.PostMapping.class)) {
                RequireRole rr = m.getAnnotation(RequireRole.class);
                assertNotNull(rr, m.getName() + " 必须标注 @RequireRole");
                assertFalse(rr.allowNodeAdmin(), m.getName() + " 仅限超级管理员访问");
            }
        }

        MonitorSettings settings = mock(MonitorSettings.class);
        AlertService alertService = mock(AlertService.class);
        OverviewService overviewService = mock(OverviewService.class);

        MonitorController ctl = new MonitorController();
        ReflectionTestUtils.setField(ctl, "jdbc", jdbc);
        ReflectionTestUtils.setField(ctl, "settings", settings);
        ReflectionTestUtils.setField(ctl, "alertService", alertService);
        ReflectionTestUtils.setField(ctl, "overviewService", overviewService);
        ReflectionTestUtils.setField(ctl, "probeService", probeService);

        // 1. getSettings 绝不回传 tg_token 明文，且剥离内部去重状态键
        Map<String, String> stored = new HashMap<>();
        stored.put(MonitorSettings.TG_TOKEN, "99999:SUPER_SECRET_BOT_TOKEN");
        stored.put(MonitorSettings.TG_CHAT, "-10088");
        stored.put("traffic_alerted_1", "1700000:80");
        stored.put("probe_seeded", "1");
        when(settings.all()).thenReturn(stored);

        R getRes = ctl.getSettings();
        assertEquals(0, getRes.getCode());
        Map<String, String> data = (Map<String, String>) getRes.getData();
        assertEquals("", data.get(MonitorSettings.TG_TOKEN), "不得向前端回传 Bot Token 原文");
        assertEquals("1", data.get("tg_token_set"));
        assertFalse(data.containsKey("traffic_alerted_1"));
        assertFalse(data.containsKey("probe_seeded"));

        // 2. saveSettings：超长拒绝、空 token 不覆盖、clear_tg_token 显式清空
        assertNotEquals(0, ctl.saveSettings(null).getCode());
        assertNotEquals(0, ctl.saveSettings(Collections.singletonMap(MonitorSettings.STATUS_TITLE, "x".repeat(201))).getCode());

        Map<String, Object> keepTokenReq = new HashMap<>();
        keepTokenReq.put(MonitorSettings.TG_CHAT, "-10077");
        keepTokenReq.put(MonitorSettings.TG_TOKEN, "   ");
        assertEquals(0, ctl.saveSettings(keepTokenReq).getCode());
        verify(settings).set(MonitorSettings.TG_CHAT, "-10077");
        verify(settings, never()).set(eq(MonitorSettings.TG_TOKEN), anyString());

        Map<String, Object> clearTokenReq = new HashMap<>();
        clearTokenReq.put("clear_tg_token", true);
        assertEquals(0, ctl.saveSettings(clearTokenReq).getCode());
        verify(settings).set(MonitorSettings.TG_TOKEN, "");

        // 3. metrics / probes / saveMeta / saveTarget / deleteTarget 边界校验
        assertNotEquals(0, ctl.metrics(null).getCode());
        assertNotEquals(0, ctl.metrics(Collections.singletonMap("nodeId", "bad")).getCode());
        assertEquals("1h", MonitorController.Window.of("1h").range);
        assertEquals("7d", MonitorController.Window.of("7d").range);
        assertEquals("30d", MonitorController.Window.of("30d").range);
        assertEquals("24h", MonitorController.Window.of("unknown").range);

        assertNotEquals(0, ctl.probes(null).getCode());
        assertNotEquals(0, ctl.saveMeta(null).getCode());
        Map<String, Object> badDateMeta = Map.of("nodeId", 1L, "expireDate", "not-a-date");
        assertNotEquals(0, ctl.saveMeta(badDateMeta).getCode());
        Map<String, Object> badQuotaMeta = Map.of("nodeId", 1L, "quotaGb", "abc");
        assertNotEquals(0, ctl.saveMeta(badQuotaMeta).getCode());
        Map<String, Object> validMeta = Map.of("nodeId", 1L, "expireDate", "2026-12-31", "quotaGb", "200", "resetDay", "99", "trafficMode", "out");
        assertEquals(0, ctl.saveMeta(validMeta).getCode());

        // saveTarget 校验非法地址、端口越界与 20 个数量上限
        assertNotEquals(0, ctl.saveTarget(Map.of("name", "T", "host", "bad host;rm -rf", "port", 443)).getCode());
        assertNotEquals(0, ctl.saveTarget(Map.of("name", "T", "host", "1.1.1.1", "port", 70000)).getCode());
        when(jdbc.queryForObject("SELECT COUNT(*) FROM monitor_probe_target", Integer.class)).thenReturn(20);
        R overCap = ctl.saveTarget(Map.of("name", "T", "host", "1.1.1.1", "port", 443));
        assertNotEquals(0, overCap.getCode());
        assertTrue(overCap.getMsg().contains("20"));

        when(jdbc.queryForObject("SELECT COUNT(*) FROM monitor_probe_target", Integer.class)).thenReturn(2);
        assertEquals(0, ctl.saveTarget(Map.of("name", "CF", "host", "1.1.1.1", "port", 443)).getCode());
        verify(probeService).pushAllOnline();

        assertNotEquals(0, ctl.deleteTarget(null).getCode());
        assertEquals(0, ctl.deleteTarget(Map.of("id", 5L)).getCode());
    }

    @Test
    void alertService_deduplicatesOfflineExpiryAndTrafficNotificationsWithoutNetworkCalls() {
        MonitorSettings settings = mock(MonitorSettings.class);
        NodeUsage usage = mock(NodeUsage.class);
        AlertService alertService = Mockito.spy(new AlertService());
        ReflectionTestUtils.setField(alertService, "jdbc", jdbc);
        ReflectionTestUtils.setField(alertService, "settings", settings);
        ReflectionTestUtils.setField(alertService, "usage", usage);
        // 将启动时间拨回 10 分钟前，避开面板刚启动 5 分钟静默期
        ReflectionTestUtils.setField(alertService, "startedAt", System.currentTimeMillis() - 10 * 60_000L);

        List<String> sentMessages = new ArrayList<>();
        lenient().doAnswer(inv -> {
            sentMessages.add(inv.getArgument(0));
            return null;
        }).when(alertService).sendTelegram(anyString());

        when(settings.telegramReady()).thenReturn(true);
        when(settings.getInt(MonitorSettings.OFFLINE_MIN)).thenReturn(3);

        // 1. 节点离线超过阈值只报 1 次，恢复在线报 1 次恢复通知
        @SuppressWarnings("unchecked")
        Map<Long, Long> offlineSince = (Map<Long, Long>) ReflectionTestUtils.getField(alertService, "offlineSince");
        offlineSince.put(10L, System.currentTimeMillis() - 4 * 60_000L);
        when(jdbc.queryForList("SELECT id, name, status FROM node"))
                .thenReturn(List.of(Map.of("id", 10L, "name", "东京-1", "status", 0)));

        alertService.checkOffline();
        alertService.checkOffline();
        assertEquals(1, sentMessages.size());
        assertTrue(sentMessages.get(0).contains("已离线超过 3 分钟"));

        when(jdbc.queryForList("SELECT id, name, status FROM node"))
                .thenReturn(List.of(Map.of("id", 10L, "name", "东京-1", "status", 1)));
        alertService.checkOffline();
        assertEquals(2, sentMessages.size());
        assertTrue(sentMessages.get(1).contains("恢复在线"));

        // 2. 到期检查：今天到期提醒，已过期或远期跳过
        sentMessages.clear();
        when(settings.getInt(MonitorSettings.EXPIRE_DAYS)).thenReturn(3);
        LocalDate today = LocalDate.now();
        when(jdbc.queryForList(contains("WHERE m.expire_date IS NOT NULL"))).thenReturn(List.of(
                Map.of("name", "香港到期机", "expire_date", Date.valueOf(today)),
                Map.of("name", "远期机", "expire_date", Date.valueOf(today.plusDays(10))),
                Map.of("name", "已过期机", "expire_date", Date.valueOf(today.minusDays(1)))
        ));
        alertService.checkExpiry();
        assertEquals(1, sentMessages.size());
        assertTrue(sentMessages.get(0).contains("香港到期机"));

        // 3. 流量阈值告警：80% 触发一次后记录标记，同周期重复扫描不再刷屏，升至 100% 再升级提醒一次
        sentMessages.clear();
        when(settings.getInt(MonitorSettings.TRAFFIC_ALERT)).thenReturn(1);
        long gib = 1024L * 1024 * 1024;
        when(jdbc.queryForList(contains("WHERE m.quota_gb IS NOT NULL"))).thenReturn(List.of(
                Map.of("node_id", 20L, "name", "新加坡-1", "quota_gb", 100, "reset_day", 1, "traffic_mode", "both")
        ));
        when(usage.usageSince(anyMap())).thenReturn(Map.of(20L, new long[]{45 * gib, 40 * gib})); // 85%
        Map<String, String> stateMap = new HashMap<>();
        when(settings.all()).thenReturn(stateMap);
        doAnswer(inv -> {
            stateMap.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(settings).set(anyString(), anyString());

        alertService.checkTraffic();
        alertService.checkTraffic(); // 同周期已记录 :80，不重复发送
        assertEquals(1, sentMessages.size());
        assertTrue(sentMessages.get(0).contains("85%"));

        // 升至 105% -> 升级发送 100% 耗尽告警
        when(usage.usageSince(anyMap())).thenReturn(Map.of(20L, new long[]{60 * gib, 45 * gib}));
        alertService.checkTraffic();
        alertService.checkTraffic();
        assertEquals(2, sentMessages.size());
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(settings, atLeastOnce()).set(keyCaptor.capture(), contains(":100"));
        assertTrue(keyCaptor.getAllValues().contains("traffic_alerted_20"));
    }
}
