package com.admin.common.interceptor;

import com.admin.common.exception.GlobalExceptionHandler;
import com.admin.common.exception.UnauthorizedException;
import com.admin.common.lang.R;
import com.admin.common.utils.JwtUtil;
import com.admin.config.WebMvcConfig;
import com.admin.entity.User;
import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.handler.MappedInterceptor;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JwtInterceptor 令牌拦截校验、WebMvcConfig 路由白名单放行与 GlobalExceptionHandler 401 转换单元测试
 */
class JwtInterceptorTest {

    private static final String TEST_SECRET = "jwt-interceptor-test-secret-key";

    private JwtInterceptor interceptor;

    @BeforeEach
    void setUp() {
        JwtUtil jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "secretKey", TEST_SECRET);
        jwtUtil.init();
        interceptor = new JwtInterceptor();
    }

    private String issueToken(Long userId, int roleId) {
        User u = new User();
        u.setId(userId);
        u.setUser("tester");
        u.setRoleId(roleId);
        return JwtUtil.generateToken(u);
    }

    private String signRaw(String headerJson, String payloadJson, String secret) throws Exception {
        String encodedHeader = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(headerJson.getBytes(StandardCharsets.UTF_8));
        String encodedPayload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        Mac hmac = Mac.getInstance("HmacSHA256");
        hmac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String sig = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(hmac.doFinal((encodedHeader + "." + encodedPayload).getBytes(StandardCharsets.UTF_8)));
        return encodedHeader + "." + encodedPayload + "." + sig;
    }

    @Test
    void preHandle_missingOrBlankToken_throwsUnauthorized() {
        MockHttpServletResponse res = new MockHttpServletResponse();

        MockHttpServletRequest noHeader = new MockHttpServletRequest();
        UnauthorizedException ex1 = assertThrows(UnauthorizedException.class,
                () -> interceptor.preHandle(noHeader, res, new Object()));
        assertEquals("未登录或token已过期", ex1.getMessage());

        MockHttpServletRequest blankHeader = new MockHttpServletRequest();
        blankHeader.addHeader("Authorization", "   ");
        UnauthorizedException ex2 = assertThrows(UnauthorizedException.class,
                () -> interceptor.preHandle(blankHeader, res, new Object()));
        assertEquals("未登录或token已过期", ex2.getMessage());
    }

    @Test
    void preHandle_malformedOrTamperedToken_throwsUnauthorized() {
        MockHttpServletResponse res = new MockHttpServletResponse();
        String validToken = issueToken(10L, 1);
        String[] parts = validToken.split("\\.");

        String tamperedSig = parts[0] + "." + parts[1] + "."
                + parts[2].substring(0, parts[2].length() - 1)
                + (parts[2].endsWith("a") ? "b" : "a");
        String fakePayload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"sub\":\"1\",\"role_id\":0,\"exp\":9999999999}".getBytes(StandardCharsets.UTF_8));

        for (String bad : new String[]{"invalid", "a.b", "Bearer " + validToken, tamperedSig,
                parts[0] + "." + fakePayload + "." + parts[2]}) {
            MockHttpServletRequest req = new MockHttpServletRequest();
            req.addHeader("Authorization", bad);
            UnauthorizedException ex = assertThrows(UnauthorizedException.class,
                    () -> interceptor.preHandle(req, res, new Object()), "应拦截非法令牌: " + bad);
            assertEquals("无效的token或token已过期", ex.getMessage());
        }
    }

    @Test
    void preHandle_expiredToken_throwsUnauthorized() throws Exception {
        Map<String, Object> header = new HashMap<>();
        header.put("alg", "HmacSHA256");
        header.put("typ", "JWT");
        Map<String, Object> payload = new HashMap<>();
        payload.put("sub", "1");
        payload.put("role_id", 0);
        payload.put("exp", (System.currentTimeMillis() / 1000) - 120);

        String expired = signRaw(JSON.toJSONString(header), JSON.toJSONString(payload), TEST_SECRET);
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Authorization", expired);

        UnauthorizedException ex = assertThrows(UnauthorizedException.class,
                () -> interceptor.preHandle(req, new MockHttpServletResponse(), new Object()));
        assertEquals("无效的token或token已过期", ex.getMessage());
    }

    @Test
    void preHandle_validToken_returnsTrue() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Authorization", issueToken(42L, 0));

        assertTrue(interceptor.preHandle(req, new MockHttpServletResponse(), new Object()));
    }

    @Test
    void webMvcConfig_excludesPublicEndpointsAndProtectsApiRoutes() {
        class ExposingRegistry extends InterceptorRegistry {
            List<Object> expose() {
                return super.getInterceptors();
            }
        }
        ExposingRegistry registry = new ExposingRegistry();
        new WebMvcConfig().addInterceptors(registry);

        List<Object> interceptors = registry.expose();
        assertEquals(1, interceptors.size());
        MappedInterceptor mapped = (MappedInterceptor) interceptors.get(0);
        AntPathMatcher matcher = new AntPathMatcher();

        // 公开免鉴权接口放行
        for (String publicPath : new String[]{
                "/api/v1/user/login",
                "/api/v1/config/get",
                "/api/v1/captcha/check",
                "/api/v1/captcha/generate",
                "/api/v1/open_api/status",
                "/api/v1/open_api/sub/token123",
                "/flow/upload"
        }) {
            assertFalse(mapped.matches(publicPath, matcher), "公开接口应免鉴权放行: " + publicPath);
        }

        // 受保护业务接口必须拦截
        for (String protectedPath : new String[]{
                "/api/v1/node/list",
                "/api/v1/inbound/create",
                "/api/v1/landing/delete",
                "/api/v1/user/list"
        }) {
            assertTrue(mapped.matches(protectedPath, matcher), "受保护接口应触发 JWT 拦截: " + protectedPath);
        }
    }

    @Test
    void globalExceptionHandler_convertsUnauthorizedTo401Response() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        UnauthorizedException thrown = assertThrows(UnauthorizedException.class,
                () -> interceptor.preHandle(req, new MockHttpServletResponse(), new Object()));

        R response = new GlobalExceptionHandler().handleUnauthorizedException(thrown);
        assertEquals(401, response.getCode());
        assertEquals("未登录或token已过期", response.getMsg());
    }
}
