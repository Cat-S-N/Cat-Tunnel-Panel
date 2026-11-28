package com.admin.common.utils;

import com.admin.entity.User;
import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JwtUtilTest {

    private static final String TEST_SECRET = "test-jwt-secret-key-for-unit-tests";

    @BeforeEach
    void setUp() {
        JwtUtil jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "secretKey", TEST_SECRET);
        jwtUtil.init();
    }

    @Test
    void generatesAndValidatesTokenAndExtractsClaims() {
        User user = new User();
        user.setId(42L);
        user.setUser("alice");
        user.setRoleId(1);

        String token = JwtUtil.generateToken(user);
        assertNotNull(token);
        assertEquals(3, token.split("\\.").length);
        assertTrue(JwtUtil.validateToken(token));
        assertEquals(42L, JwtUtil.getUserIdFromToken(token));
        assertEquals(1, JwtUtil.getRoleIdFromToken(token));
    }

    @Test
    void rejectsNullEmptyMalformedAndTamperedTokens() {
        assertFalse(JwtUtil.validateToken(null));
        assertFalse(JwtUtil.validateToken(""));
        assertFalse(JwtUtil.validateToken("invalid"));
        assertFalse(JwtUtil.validateToken("a.b"));
        assertFalse(JwtUtil.validateToken("a.b.c.d"));

        User user = new User();
        user.setId(7L);
        user.setUser("bob");
        user.setRoleId(2);
        String token = JwtUtil.generateToken(user);
        String[] parts = token.split("\\.");

        // 篡改签名
        String tamperedSig = parts[0] + "." + parts[1] + "." + parts[2].substring(0, parts[2].length() - 1)
                + (parts[2].endsWith("a") ? "b" : "a");
        assertFalse(JwtUtil.validateToken(tamperedSig));

        // 篡改 payload
        String fakePayload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"sub\":\"999\",\"exp\":9999999999}".getBytes(StandardCharsets.UTF_8));
        assertFalse(JwtUtil.validateToken(parts[0] + "." + fakePayload + "." + parts[2]));
    }

    @Test
    void rejectsExpiredToken() throws Exception {
        Map<String, Object> header = new HashMap<>();
        header.put("alg", "HmacSHA256");
        header.put("typ", "JWT");
        String encodedHeader = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(JSON.toJSONString(header).getBytes(StandardCharsets.UTF_8));

        Map<String, Object> payload = new HashMap<>();
        payload.put("sub", "1");
        payload.put("exp", (System.currentTimeMillis() / 1000) - 60);
        String encodedPayload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(JSON.toJSONString(payload).getBytes(StandardCharsets.UTF_8));

        Mac hmac = Mac.getInstance("HmacSHA256");
        hmac.init(new SecretKeySpec(TEST_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String sig = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(hmac.doFinal((encodedHeader + "." + encodedPayload).getBytes(StandardCharsets.UTF_8)));

        assertFalse(JwtUtil.validateToken(encodedHeader + "." + encodedPayload + "." + sig));
    }

    @Test
    void claimExtractorsReturnNullOnMissingOrMalformedTokens() {
        assertNull(JwtUtil.getUserIdFromToken(null));
        assertNull(JwtUtil.getUserIdFromToken(""));
        assertNull(JwtUtil.getUserIdFromToken("malformed"));
        assertNull(JwtUtil.getRoleIdFromToken(null));
        assertNull(JwtUtil.getRoleIdFromToken("bad.payload"));
    }
}
