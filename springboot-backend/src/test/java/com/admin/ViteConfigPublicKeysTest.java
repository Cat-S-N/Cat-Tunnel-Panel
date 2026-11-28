package com.admin;

import com.admin.common.lang.R;
import com.admin.common.utils.JwtUtil;
import com.admin.controller.BaseController;
import com.admin.controller.ViteConfigController;
import com.admin.entity.User;
import com.admin.entity.ViteConfig;
import com.admin.service.ViteConfigService;
import com.admin.service.impl.ViteConfigServiceImpl;
import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;

import java.lang.reflect.Field;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * /api/v1/config/get 免登录,但未登录 / 非管理员只能读白名单里的键 —— vite_config.ip 存着面板的公网 IP,
 * 原来任何人一个请求就能拿到。管理员照旧什么键都能读(网页「网站配置」页要用)。
 */
class ViteConfigPublicKeysTest {

    static ViteConfigController ctl;
    static ViteConfigService svc;
    static Object oldSecret;

    @BeforeAll
    static void setUp() throws Exception {
        Field f = JwtUtil.class.getDeclaredField("SECRET_KEY");
        f.setAccessible(true);
        oldSecret = f.get(null);
        f.set(null, "unit-test-secret-key-0123456789abcdef");
        svc = Mockito.mock(ViteConfigService.class);
        Mockito.when(svc.getConfigByName(Mockito.anyString())).thenAnswer((inv) -> R.ok("value-of-" + inv.getArgument(0)));
        ctl = new ViteConfigController();
        Field s = BaseController.class.getDeclaredField("viteConfigService");
        s.setAccessible(true);
        s.set(ctl, svc);
    }

    // 测完把密钥还原,别影响排在后面、靠 Spring 注入密钥的测试
    @AfterAll
    static void tearDown() throws Exception {
        Field f = JwtUtil.class.getDeclaredField("SECRET_KEY");
        f.setAccessible(true);
        f.set(null, oldSecret);
    }

    static String token(int roleId) {
        User u = new User();
        u.setId(roleId == 0 ? 1L : 7L);
        u.setUser(roleId == 0 ? "admin_user" : "rider");
        u.setRoleId(roleId);
        return JwtUtil.generateToken(u);
    }

    static R get(String name, String auth) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        if (auth != null) req.addHeader("Authorization", auth);
        Map<String, Object> p = new HashMap<>();
        p.put("name", name);
        return ctl.getConfigByName(p, req);
    }

    static boolean denied(R r) { return r.getCode() != 0 && "配置不存在".equals(r.getMsg()); }

    @Test
    void anonymousCanReadLoginPageKeysOnly() {
        assertFalse(denied(get("app_name", null)));
        assertFalse(denied(get("captcha_enabled", null)));
        assertFalse(denied(get("captcha_type", null)));
        assertFalse(denied(get("sub_url", null)));
        assertFalse(denied(get("register_enabled", null)));
        assertFalse(denied(get("register_invite_required", null)));
        assertTrue(denied(get("register_invite_code", null)), "注册邀请码原文绝不能向未登录访客暴露");
        assertTrue(denied(get("ip", null)), "面板 IP 不能给未登录的人");
        assertTrue(denied(get("anything_else", null)));
    }

    @Test
    void riderTokenIsNotAdmin() {
        assertTrue(denied(get("ip", token(1))));
        assertTrue(denied(get("register_invite_code", token(1))), "普通用户同样无权读取注册邀请码原文");
        assertFalse(denied(get("register_invite_required", token(1))));
        assertFalse(denied(get("app_name", token(1))));
    }

    @Test
    void adminReadsEverything() {
        assertFalse(denied(get("ip", token(0))));
        assertFalse(denied(get("register_invite_code", token(0))));
    }

    @Test
    void publicInviteRequiredSignalNeverLeaksSecretInviteCodes() throws Exception {
        ViteConfigServiceImpl realSvc = Mockito.spy(new ViteConfigServiceImpl());
        ViteConfigController localCtl = new ViteConfigController();
        Field s = BaseController.class.getDeclaredField("viteConfigService");
        s.setAccessible(true);
        s.set(localCtl, realSvc);

        // 1. 未配置邀请码 -> 返回 "false"
        Mockito.doReturn(null).when(realSvc).getOne(Mockito.any(QueryWrapper.class));
        MockHttpServletRequest req = new MockHttpServletRequest();
        Map<String, Object> query = new HashMap<>();
        query.put("name", "register_invite_required");
        R r1 = localCtl.getConfigByName(query, req);
        assertEquals(0, r1.getCode());
        assertEquals("false", ((ViteConfig) r1.getData()).getValue());

        // 2. 配置了多个敏感邀请码 -> 仅返回 "true"，序列化结果绝不含邀请码原文，直接请求 register_invite_code 被拒绝
        ViteConfig secretCfg = new ViteConfig();
        secretCfg.setName("register_invite_code");
        secretCfg.setValue("TOP_SECRET_ALPHA_999,VIP_OMEGA_777");
        Mockito.doReturn(secretCfg).when(realSvc).getOne(Mockito.any(QueryWrapper.class));

        R r2 = localCtl.getConfigByName(query, req);
        assertEquals(0, r2.getCode());
        assertEquals("true", ((ViteConfig) r2.getData()).getValue());
        String json = JSON.toJSONString(r2);
        assertFalse(json.contains("TOP_SECRET_ALPHA_999"), json);
        assertFalse(json.contains("VIP_OMEGA_777"), json);

        Map<String, Object> stealAttempt = new HashMap<>();
        stealAttempt.put("name", "register_invite_code");
        R r3 = localCtl.getConfigByName(stealAttempt, req);
        assertTrue(denied(r3));
        assertFalse(JSON.toJSONString(r3).contains("TOP_SECRET_ALPHA_999"));
    }

    @Test
    void forgedOrBrokenTokensAreNotAdmin() {
        String real = token(1);
        String[] parts = real.split("\\.");
        // 把载荷里的 role_id 改成 0 但签名不变 → 验签失败
        String payload = new String(Base64.getUrlDecoder().decode(parts[1])).replace("\"role_id\":1", "\"role_id\":0");
        String forged = parts[0] + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes()) + "." + parts[2];
        assertTrue(denied(get("ip", forged)));
        assertTrue(denied(get("ip", "garbage")));
        assertTrue(denied(get("ip", "a.b")));
        assertTrue(denied(get("ip", "")));
    }

    @Test
    void missingNameDoesNotThrow() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        R r = ctl.getConfigByName(new HashMap<>(), req);
        assertNotEquals(0, r.getCode());
    }
}
