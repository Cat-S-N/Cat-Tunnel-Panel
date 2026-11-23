package com.admin.monitor;

import com.admin.common.lang.R;
import com.admin.common.utils.WebSocketServer;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ParameterizedPreparedStatementSetter;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.PreparedStatement;
import java.sql.Types;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * ProbeService 版本匹配/探针上报/汇总、MetricCollector 桶聚合/清零重算与 StatusController 公开状态缓存单元测试
 */
@ExtendWith(MockitoExtension.class)
class ProbeAndMetricReportTest {

    @Mock
    private JdbcTemplate jdbc;

    @Mock
    private MonitorSettings settings;

    @Mock
    private OverviewService overview;

    @AfterEach
    void tearDown() {
        ReflectionTestUtils.setField(ProbeService.class, "INSTANCE", null);
        ReflectionTestUtils.setField(MetricCollector.class, "INSTANCE", null);
    }

    @Test
    void probeService_versionCompatibilityChecks() {
        assertFalse(ProbeService.supports(null));
        assertFalse(ProbeService.supports("1.2.4"));
        assertFalse(ProbeService.supports("v1.2.4"));
        assertTrue(ProbeService.supports("1.2.5"));
        assertTrue(ProbeService.supports("v1.2.5-beta"));
        assertTrue(ProbeService.supports("1.10.0"));
        assertTrue(ProbeService.supports("2.0"));
    }

    @Test
    void probeService_onNodeConnectedSkipsUnsupportedAndPushReadsTargetsSafely() {
        ProbeService ps = new ProbeService();
        ReflectionTestUtils.setField(ps, "jdbc", jdbc);
        ps.init();

        ProbeService.onNodeConnected(null, "1.2.5");
        ProbeService.onNodeConnected(94001L, "1.2.4");
        verifyNoInteractions(jdbc);

        Map<String, Object> target = new HashMap<>();
        target.put("id", 1);
        target.put("host", "1.1.1.1");
        target.put("port", 443);
        when(jdbc.queryForList(contains("monitor_probe_target")))
                .thenReturn(Collections.singletonList(target));
        assertDoesNotThrow(() -> ps.push(94001L));
        verify(jdbc).queryForList(contains("monitor_probe_target"));

        when(jdbc.queryForList(contains("monitor_probe_target")))
                .thenThrow(new RuntimeException("db error"));
        assertDoesNotThrow(() -> ps.push(94001L));
    }

    @Test
    @SuppressWarnings("unchecked")
    void probeService_onProbeReport_validatesInputSetsNullOnNegativeAvgAndSwallowsDbError() throws Exception {
        ProbeService ps = new ProbeService();
        ReflectionTestUtils.setField(ps, "jdbc", jdbc);
        ps.init();

        // 无效上报忽略
        ProbeService.onProbeReport(null, new JSONObject());
        ProbeService.onProbeReport(1L, null);
        JSONObject badTs = new JSONObject();
        badTs.put("ts", 0L);
        badTs.put("results", new JSONArray());
        ProbeService.onProbeReport(1L, badTs);
        verifyNoInteractions(jdbc);

        // 有效上报：包含正常延迟与超时(avg = -1)
        JSONObject r1 = new JSONObject();
        r1.put("id", 10);
        r1.put("avg", 23.5);
        r1.put("loss", 0.0);

        JSONObject r2 = new JSONObject();
        r2.put("id", 11);
        r2.put("avg", -1.0);
        r2.put("loss", 100.0);

        JSONArray results = new JSONArray();
        results.add(r1);
        results.add(r2);
        JSONObject report = new JSONObject();
        report.put("ts", 125_000L);
        report.put("results", results);

        ArgumentCaptor<ParameterizedPreparedStatementSetter<Object>> setterCaptor =
                ArgumentCaptor.forClass(ParameterizedPreparedStatementSetter.class);
        ProbeService.onProbeReport(7L, report);

        verify(jdbc).batchUpdate(contains("INSERT INTO monitor_probe"), any(Collection.class), eq(2), setterCaptor.capture());
        PreparedStatement statement = mock(PreparedStatement.class);
        setterCaptor.getValue().setValues(statement, r1);
        verify(statement).setLong(1, 7L);
        verify(statement).setInt(2, 10);
        verify(statement).setLong(4, 120_000L);
        verify(statement).setDouble(5, 23.5);

        PreparedStatement statementLoss = mock(PreparedStatement.class);
        setterCaptor.getValue().setValues(statementLoss, r2);
        verify(statementLoss).setNull(5, Types.FLOAT);
        verify(statementLoss).setDouble(6, 100.0);

        // DB 异常安全吞掉
        doThrow(new RuntimeException("insert failed")).when(jdbc)
                .batchUpdate(anyString(), any(Collection.class), anyInt(), any());
        assertDoesNotThrow(() -> ProbeService.onProbeReport(7L, report));
    }

    @Test
    void probeService_rollupAndPrune_executesFiveStatementsAndHandlesError() {
        ProbeService ps = new ProbeService();
        ReflectionTestUtils.setField(ps, "jdbc", jdbc);

        ps.rollupAndPrune();
        verify(jdbc).update(contains("INSERT INTO monitor_probe"), any(), any(), any(), any(), any(), any());
        verify(jdbc, times(2)).update(contains("DELETE FROM monitor_probe WHERE gran"), any(Object.class), any(Object.class));
        verify(jdbc, times(2)).update(anyString());

        reset(jdbc);
        when(jdbc.update(contains("INSERT INTO monitor_probe"), any(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("prune fail"));
        assertDoesNotThrow(ps::rollupAndPrune);
    }

    @Test
    @SuppressWarnings("unchecked")
    void metricCollector_aggregatesBucketsHandlesCounterResetAndFlushes() throws Exception {
        MetricCollector mc = new MetricCollector();
        ReflectionTestUtils.setField(mc, "jdbc", jdbc);
        mc.init();

        ProbeService ps = new ProbeService();
        ReflectionTestUtils.setField(ps, "jdbc", jdbc);
        ps.init();

        // 缺少 memory_usage 跳过
        JSONObject noMem = new JSONObject();
        noMem.put("cpu_usage", 10.0);
        MetricCollector.onNodeInfo(9L, noMem);
        assertTrue(mc.drain(System.currentTimeMillis() + MetricCollector.MINUTE * 2).isEmpty());

        // 同分钟累加 + 计数器回绕归零
        long min1 = 600_000L;
        JSONObject p1 = new JSONObject();
        p1.put("cpu_usage", 20.0);
        p1.put("memory_usage", 40.0);
        p1.put("bytes_received", 1000L);
        p1.put("bytes_transmitted", 2000L);
        mc.add(9L, p1, min1 + 1000);

        JSONObject p2 = new JSONObject();
        p2.put("cpu_usage", 50.0);
        p2.put("memory_usage", 60.0);
        p2.put("bytes_received", 1500L);
        p2.put("bytes_transmitted", 2800L);
        mc.add(9L, p2, min1 + 3000);

        // 网卡计数器重启减小 -> 本次增量算 0
        JSONObject p3 = new JSONObject();
        p3.put("cpu_usage", 10.0);
        p3.put("memory_usage", 20.0);
        p3.put("bytes_received", 100L);
        p3.put("bytes_transmitted", 200L);
        mc.add(9L, p3, min1 + 5000);

        // 同一分钟 drain 不会弹出尚未结束的桶
        assertTrue(mc.drain(min1 + 10_000).isEmpty());

        // 推进到下一分钟，旧桶进入待落库列表
        List<MetricCollector.Bucket> drained = mc.drain(min1 + MetricCollector.MINUTE + 1000);
        assertEquals(1, drained.size());
        MetricCollector.Bucket b = drained.get(0);
        assertEquals(3, b.n);
        assertEquals(50.0, b.cpuMax);
        assertEquals(80.0, b.cpuSum);
        assertEquals(120.0, b.memSum);
        assertEquals(500L, b.rx);
        assertEquals(800L, b.tx);

        // 通过 onNodeInfo 写入并触发探针转发 + flush
        JSONObject withProbe = new JSONObject();
        withProbe.put("cpu_usage", 30.0);
        withProbe.put("memory_usage", 50.0);
        withProbe.put("bytes_received", 100L);
        withProbe.put("bytes_transmitted", 100L);
        JSONObject probeObj = new JSONObject();
        probeObj.put("ts", 120_000L);
        JSONArray arr = new JSONArray();
        JSONObject pr = new JSONObject();
        pr.put("id", 1);
        pr.put("avg", 12.0);
        pr.put("loss", 0.0);
        arr.add(pr);
        probeObj.put("results", arr);
        withProbe.put("probe", probeObj);
        MetricCollector.onNodeInfo(9L, withProbe);
        verify(jdbc).batchUpdate(contains("INSERT INTO monitor_probe"), any(Collection.class), eq(1), any());

        // 空桶 flush 不调用 batchUpdate
        mc.flush();
        verify(jdbc, never()).batchUpdate(contains("INSERT INTO monitor_metric"), any(Collection.class), anyInt(), any());

        // 通过跨分钟加点让旧桶归档到 done，再执行 flush 写入指标表
        long oldMin = System.currentTimeMillis() - MetricCollector.MINUTE * 2;
        mc.add(9L, p1, oldMin);
        mc.flush();
        verify(jdbc, times(1)).batchUpdate(contains("INSERT INTO monitor_metric"), any(Collection.class), eq(2), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void statusController_respectsToggleAndCachesWithinTenSeconds() {
        StatusController sc = new StatusController();
        ReflectionTestUtils.setField(sc, "settings", settings);
        ReflectionTestUtils.setField(sc, "overview", overview);

        when(settings.all()).thenReturn(Collections.singletonMap(MonitorSettings.STATUS_ENABLED, "0"));
        R disabled = sc.status();
        assertEquals(0, disabled.getCode());
        assertEquals(false, ((Map<String, Object>) disabled.getData()).get("enabled"));
        verifyNoInteractions(overview);

        Map<String, String> enabledMap = new HashMap<>();
        enabledMap.put(MonitorSettings.STATUS_ENABLED, "1");
        enabledMap.put(MonitorSettings.STATUS_TITLE, "测试状态页");
        when(settings.all()).thenReturn(enabledMap);
        when(overview.build(true)).thenReturn(Collections.singletonList(Collections.singletonMap("name", "香港1")));

        R first = sc.status();
        R second = sc.status();

        assertEquals(0, first.getCode());
        Map<String, Object> d1 = (Map<String, Object>) first.getData();
        assertEquals(true, d1.get("enabled"));
        assertEquals("测试状态页", d1.get("title"));
        assertSame(d1, second.getData());
        verify(overview, times(1)).build(true);
    }
}
