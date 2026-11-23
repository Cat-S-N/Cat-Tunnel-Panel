package com.admin.common.lang;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 统一响应封装 R 单元测试
 */
class RTest {

    @Test
    void okWithoutDataUsesDefaultSuccessFields() {
        long before = System.currentTimeMillis();
        R res = R.ok();
        long after = System.currentTimeMillis();

        assertEquals(0, res.getCode());
        assertEquals("操作成功", res.getMsg());
        assertNull(res.getData());
        assertTrue(res.getTs() >= before && res.getTs() <= after);
    }

    @Test
    void okWithDataPreservesPayload() {
        Map<String, Object> payload = Map.of("id", 42, "name", "node-a");
        R res = R.ok(payload);

        assertEquals(0, res.getCode());
        assertEquals("操作成功", res.getMsg());
        assertEquals(payload, res.getData());

        R nullDataRes = R.ok(null);
        assertEquals(0, nullDataRes.getCode());
        assertNull(nullDataRes.getData());
    }

    @Test
    void errWithoutArgsUsesDefaultFailureFields() {
        R res = R.err();

        assertEquals(-1, res.getCode());
        assertEquals("请求失败", res.getMsg());
        assertNull(res.getData());
    }

    @Test
    void errWithMessageSetsCodeMinusOneAndCustomMessage() {
        R res = R.err("参数校验失败");
        assertEquals(-1, res.getCode());
        assertEquals("参数校验失败", res.getMsg());
        assertNull(res.getData());

        R emptyMsgRes = R.err("");
        assertEquals(-1, emptyMsgRes.getCode());
        assertEquals("", emptyMsgRes.getMsg());
    }

    @Test
    void errWithCodeAndMessageSetsCustomCodeAndMessage() {
        R unauthorized = R.err(401, "未授权访问");
        assertEquals(401, unauthorized.getCode());
        assertEquals("未授权访问", unauthorized.getMsg());
        assertNull(unauthorized.getData());

        R serverError = R.err(500, "服务内部异常");
        assertEquals(500, serverError.getCode());
        assertEquals("服务内部异常", serverError.getMsg());
    }
}
