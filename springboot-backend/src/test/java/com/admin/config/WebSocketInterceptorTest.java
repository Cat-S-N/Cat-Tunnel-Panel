package com.admin.config;

import com.admin.common.utils.JwtUtil;
import com.admin.entity.Node;
import com.admin.entity.User;
import com.admin.service.NodeService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.WebSocketHandler;

import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * WebSocketInterceptor 节点密钥校验、管理员 JWT 握手鉴权与客户端 IP 提取单元测试
 */
@ExtendWith(MockitoExtension.class)
class WebSocketInterceptorTest {

    @Mock
    private NodeService nodeService;

    @Mock
    private ServerHttpResponse response;

    @Mock
    private WebSocketHandler wsHandler;

    private WebSocketInterceptor interceptor;

    @BeforeEach
    void setUp() {
        JwtUtil jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "secretKey", "ws-interceptor-test-secret");
        jwtUtil.init();

        interceptor = new WebSocketInterceptor();
        ReflectionTestUtils.setField(interceptor, "nodeService", nodeService);
    }

    @Test
    void beforeHandshake_nodeInvalidOrMissingSecret_rejected() throws Exception {
        MockHttpServletRequest raw = new MockHttpServletRequest();
        raw.setParameter("type", "1");
        raw.setParameter("secret", "wrong-secret");
        raw.setParameter("version", "1.2.5");
        when(nodeService.getOne(any(QueryWrapper.class))).thenReturn(null);

        Map<String, Object> attrs = new HashMap<>();
        boolean allowed = interceptor.beforeHandshake(new ServletServerHttpRequest(raw), response, wsHandler, attrs);

        assertFalse(allowed);
        assertTrue(attrs.isEmpty());
    }

    @Test
    void beforeHandshake_nodeValidSecret_populatesAttributes() throws Exception {
        Node node = new Node();
        node.setId(88L);
        node.setSecret("node-sec-88");
        when(nodeService.getOne(any(QueryWrapper.class))).thenReturn(node);

        MockHttpServletRequest raw = new MockHttpServletRequest();
        raw.setParameter("type", "1");
        raw.setParameter("secret", "node-sec-88");
        raw.setParameter("version", "1.2.6");
        raw.setParameter("http", "1");
        raw.setParameter("tls", "1");
        raw.setParameter("socks", "0");
        raw.setRemoteAddr("203.0.113.88");

        Map<String, Object> attrs = new HashMap<>();
        boolean allowed = interceptor.beforeHandshake(new ServletServerHttpRequest(raw), response, wsHandler, attrs);

        assertTrue(allowed);
        assertEquals(88L, attrs.get("id"));
        assertEquals("node-sec-88", attrs.get("nodeSecret"));
        assertEquals("1.2.6", attrs.get("nodeVersion"));
        assertEquals("1", attrs.get("http"));
        assertEquals("1", attrs.get("tls"));
        assertEquals("0", attrs.get("socks"));
        assertEquals("1", attrs.get("type"));
    }

    @Test
    void beforeHandshake_adminJwtValidation_rejectsInvalidAndAcceptsValid() throws Exception {
        MockHttpServletRequest badReq = new MockHttpServletRequest();
        badReq.setParameter("type", "0");
        badReq.setParameter("secret", "invalid.jwt.token");
        Map<String, Object> badAttrs = new HashMap<>();

        assertFalse(interceptor.beforeHandshake(new ServletServerHttpRequest(badReq), response, wsHandler, badAttrs));
        assertTrue(badAttrs.isEmpty());
        verifyNoInteractions(nodeService);

        User admin = new User();
        admin.setId(15L);
        admin.setUser("admin");
        admin.setRoleId(0);
        String jwt = JwtUtil.generateToken(admin);

        MockHttpServletRequest okReq = new MockHttpServletRequest();
        okReq.setParameter("secret", jwt);
        Map<String, Object> okAttrs = new HashMap<>();

        assertTrue(interceptor.beforeHandshake(new ServletServerHttpRequest(okReq), response, wsHandler, okAttrs));
        assertEquals(15L, okAttrs.get("id"));
        assertNull(okAttrs.get("type"));
    }

    @Test
    void getClientIpAndAfterHandshake_handleNullAndValidAddresses() {
        ServerHttpRequest withAddr = mock(ServerHttpRequest.class);
        when(withAddr.getRemoteAddress()).thenReturn(new InetSocketAddress("198.51.100.5", 4321));
        assertEquals("198.51.100.5", interceptor.getClientIp(withAddr));

        ServerHttpRequest withoutAddr = mock(ServerHttpRequest.class);
        when(withoutAddr.getRemoteAddress()).thenReturn(null);
        assertNull(interceptor.getClientIp(withoutAddr));

        assertDoesNotThrow(() -> interceptor.afterHandshake(withAddr, response, wsHandler, null));
    }
}
