package com.admin.controller;

import com.admin.common.lang.R;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class VersionControllerTest {

    @BeforeEach
    @AfterEach
    void resetStaticCache() throws Exception {
        setStaticField("cachedLatest", null);
        setStaticField("cachedAt", 0L);
        setStaticField("lastCheckFailed", false);
    }

    private static void setStaticField(String name, Object value) throws Exception {
        Field field = VersionController.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }

    @Test
    void normalizeCommit_handlesNullBlankShortAndLong() {
        assertEquals("dev", VersionController.normalizeCommit(null));
        assertEquals("dev", VersionController.normalizeCommit("   "));
        assertEquals("abc123", VersionController.normalizeCommit(" abc123 "));
        assertEquals("1234567", VersionController.normalizeCommit("1234567890abcdef"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void info_refreshTrueBypassesCacheAndUpdatesLatest() {
        AtomicInteger callCount = new AtomicInteger(0);
        AtomicReference<String> nextSha = new AtomicReference<>("11111112222222");

        VersionController controller = new VersionController() {
            @Override
            JSONObject fetchJson(String apiUrl) {
                callCount.incrementAndGet();
                JSONObject run = new JSONObject();
                run.put("head_sha", nextSha.get());
                JSONArray runs = new JSONArray();
                runs.add(run);
                JSONObject root = new JSONObject();
                root.put("workflow_runs", runs);
                return root;
            }
        };

        R first = controller.info(false);
        Map<String, Object> firstData = (Map<String, Object>) first.getData();
        assertEquals("1111111", firstData.get("latest"));
        assertEquals(false, firstData.get("checkFailed"));
        assertEquals(1, callCount.get());

        // 缓存窗口内 refresh=false 应命中缓存，不再请求
        nextSha.set("aaaaaaa9999999");
        R cached = controller.info(false);
        Map<String, Object> cachedData = (Map<String, Object>) cached.getData();
        assertEquals("1111111", cachedData.get("latest"));
        assertEquals(1, callCount.get());

        // refresh=true 应忽略缓存并刷新为最新值
        R refreshed = controller.info(true);
        Map<String, Object> refreshedData = (Map<String, Object>) refreshed.getData();
        assertEquals("aaaaaaa", refreshedData.get("latest"));
        assertEquals(false, refreshedData.get("checkFailed"));
        assertEquals(2, callCount.get());
    }

    @Test
    @SuppressWarnings("unchecked")
    void info_fetchFailureFallsBackSafelyWithoutThrowing() throws Exception {
        setStaticField("cachedLatest", "7654321");
        setStaticField("cachedAt", System.currentTimeMillis());

        VersionController controller = new VersionController() {
            @Override
            JSONObject fetchJson(String apiUrl) throws Exception {
                throw new RuntimeException("network timeout");
            }
        };

        R result = assertDoesNotThrow(() -> controller.info(true));
        Map<String, Object> data = (Map<String, Object>) result.getData();
        assertEquals("7654321", data.get("latest"));
        assertEquals(true, data.get("checkFailed"));
    }
}
