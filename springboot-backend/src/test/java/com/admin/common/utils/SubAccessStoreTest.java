package com.admin.common.utils;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import javax.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SubAccessStoreTest {

    @Test
    void extractsFirstValidIp() {
        assertEquals("1.2.3.4", SubAccessStore.firstHeaderIp("1.2.3.4, 10.0.0.1"));
        assertEquals("2001:db8::1", SubAccessStore.firstHeaderIp(" 2001:db8::1 "));
        assertEquals("", SubAccessStore.firstHeaderIp("unknown"));
        assertEquals("", SubAccessStore.firstHeaderIp("UNKNOWN"));
        assertEquals("", SubAccessStore.firstHeaderIp("unknown, 1.2.3.4"));
        assertEquals("", SubAccessStore.firstHeaderIp("   "));
        assertEquals("", SubAccessStore.firstHeaderIp(null));
    }

    @Test
    void safeTruncatesStrings() {
        assertEquals("", SubAccessStore.safeTruncate(null, 10));
        assertEquals("abc", SubAccessStore.safeTruncate("  abc  ", 10));
        assertEquals("Clash", SubAccessStore.safeTruncate("ClashForWindows/1.0", 5));
    }

    @Test
    void extractClientIpResolvesHeaderPriorityAndFallbacks() {
        assertEquals("unknown", SubAccessStore.extractClientIp(null));

        HttpServletRequest reqRealIp = mock(HttpServletRequest.class);
        when(reqRealIp.getHeader("X-Real-IP")).thenReturn("9.8.7.6, 10.0.0.1");
        when(reqRealIp.getHeader("CF-Connecting-IP")).thenReturn("1.1.1.1");
        assertEquals("9.8.7.6", SubAccessStore.extractClientIp(reqRealIp));

        HttpServletRequest reqCfIp = mock(HttpServletRequest.class);
        when(reqCfIp.getHeader("X-Real-IP")).thenReturn("unknown");
        when(reqCfIp.getHeader("CF-Connecting-IP")).thenReturn("2.2.2.2");
        assertEquals("2.2.2.2", SubAccessStore.extractClientIp(reqCfIp));

        HttpServletRequest reqRemote = mock(HttpServletRequest.class);
        when(reqRemote.getRemoteAddr()).thenReturn("  127.0.0.1  ");
        assertEquals("127.0.0.1", SubAccessStore.extractClientIp(reqRemote));

        HttpServletRequest reqEmpty = mock(HttpServletRequest.class);
        when(reqEmpty.getRemoteAddr()).thenReturn("   ");
        assertEquals("unknown", SubAccessStore.extractClientIp(reqEmpty));

        HttpServletRequest reqLong = mock(HttpServletRequest.class);
        String longIp = "a".repeat(80);
        when(reqLong.getHeader("X-Real-IP")).thenReturn(longIp);
        assertEquals(64, SubAccessStore.extractClientIp(reqLong).length());
    }

    @Test
    void userHasNodeHandlesNullsAndJdbcResults() {
        SubAccessStore store = new SubAccessStore();
        assertFalse(store.userHasNode(1L, 2L));

        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ReflectionTestUtils.setField(store, "jdbc", jdbc);

        assertFalse(store.userHasNode(null, 2L));
        assertFalse(store.userHasNode(1L, null));

        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(1L), eq(2L))).thenReturn(1);
        assertTrue(store.userHasNode(1L, 2L));

        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(1L), eq(3L))).thenReturn(0);
        assertFalse(store.userHasNode(1L, 3L));

        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(1L), eq(4L)))
                .thenThrow(new RuntimeException("db error"));
        assertFalse(store.userHasNode(1L, 4L));
    }

    @Test
    void queryByUserReturnsDefaultAndQueriesWithOptionalNodeId() {
        SubAccessStore store = new SubAccessStore();
        Map<String, Object> empty = store.queryByUser(null, null);
        assertEquals(0L, empty.get("pulls24h"));
        assertEquals(0L, empty.get("uniqueIps24h"));
        assertEquals(Collections.emptyList(), empty.get("logs"));

        // jdbc 为 null 时返回默认空结构
        Map<String, Object> noJdbc = store.queryByUser(10L, 1L);
        assertEquals(0L, noJdbc.get("pulls24h"));

        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ReflectionTestUtils.setField(store, "jdbc", jdbc);
        ReflectionTestUtils.setField(store, "available", true);

        Map<String, Object> statsWithNode = new LinkedHashMap<>();
        statsWithNode.put("pulls", 5L);
        statsWithNode.put("ips", 2L);
        List<Map<String, Object>> logsWithNode = List.of(Map.of("ip", "1.2.3.4", "subType", "clash"));
        when(jdbc.queryForMap(contains("node_id"), eq(10L), eq(3L), anyLong())).thenReturn(statsWithNode);
        when(jdbc.queryForList(contains("LIMIT 50"), eq(10L), eq(3L))).thenReturn(logsWithNode);

        Map<String, Object> resNode = store.queryByUser(10L, 3L);
        assertEquals(5L, resNode.get("pulls24h"));
        assertEquals(2L, resNode.get("uniqueIps24h"));
        assertEquals(logsWithNode, resNode.get("logs"));

        Map<String, Object> statsAll = new LinkedHashMap<>();
        statsAll.put("pulls", 12L);
        statsAll.put("ips", 4L);
        when(jdbc.queryForMap(contains("WHERE `user_id` = ? AND `created_time`"), eq(10L), anyLong()))
                .thenReturn(statsAll);
        when(jdbc.queryForList(contains("WHERE l.`user_id` = ? ORDER BY"), eq(10L)))
                .thenReturn(Collections.emptyList());

        Map<String, Object> resAll = store.queryByUser(10L, null);
        assertEquals(12L, resAll.get("pulls24h"));
        assertEquals(4L, resAll.get("uniqueIps24h"));
        assertEquals(Collections.emptyList(), resAll.get("logs"));
    }

    @Test
    void record_resolvesTokenOwnerTriggersLeakAlertOncePerDayAndIgnoresInvalidTokens() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AlertNotifier alertNotifier = mock(AlertNotifier.class);
        SubAccessStore store = new SubAccessStore();
        ReflectionTestUtils.setField(store, "jdbc", jdbc);
        ReflectionTestUtils.setField(store, "alertNotifier", alertNotifier);
        ReflectionTestUtils.setField(store, "available", true);
        ReflectionTestUtils.setField(store, "executor", new java.util.concurrent.AbstractExecutorService() {
            @Override public void shutdown() {}
            @Override public java.util.List<Runnable> shutdownNow() { return Collections.emptyList(); }
            @Override public boolean isShutdown() { return false; }
            @Override public boolean isTerminated() { return false; }
            @Override public boolean awaitTermination(long timeout, java.util.concurrent.TimeUnit unit) { return true; }
            @Override public void execute(Runnable command) { command.run(); }
        });

        // 空令牌与超长令牌直接忽略
        store.record(null, "sub", null);
        store.record("   ", "sub", null);
        store.record("t".repeat(129), "sub", null);
        verifyNoInteractions(jdbc);

        // 聚合订阅 token 归属解析 + 24h 独立 IP 达到 4 触发泄露预警 + 24h 内冷却不重复发送
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("X-Real-IP")).thenReturn("203.0.113.77");
        when(req.getHeader("User-Agent")).thenReturn("ClashMeta/1.18");

        when(jdbc.queryForList(contains("WHERE `all_sub_token` = ?"), eq(Long.class), eq("agg_tok_1")))
                .thenReturn(Collections.singletonList(55L));
        when(jdbc.queryForObject(contains("COUNT(DISTINCT `ip`)"), eq(Long.class), eq(55L), anyLong()))
                .thenReturn(4L);
        when(jdbc.queryForList(contains("SELECT `user` FROM `user`"), eq(String.class), eq(55L)))
                .thenReturn(Collections.singletonList("rider_55"));

        store.record("  agg_tok_1  ", "clash", req);
        store.record("agg_tok_1", "clash", req);

        verify(jdbc, times(2)).update(
                contains("INSERT INTO `sub_access_log`"),
                eq(55L), eq(0L), eq("clash"), eq("203.0.113.77"), eq("ClashMeta/1.18"), anyLong());
        // 冷却机制保证 24 小时内只发 1 次告警
        verify(alertNotifier, times(1)).notifyAsync(contains("泄露预警"), contains("rider_55"));
    }
}
