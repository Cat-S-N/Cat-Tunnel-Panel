package com.admin.common.utils;

import com.admin.common.dto.GostDto;
import com.admin.entity.Node;
import com.admin.service.NodeService;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * WebSocketServer 建连/覆盖防误离线、异常关闭、探针心跳与加密指令分发边界单元测试
 */
@ExtendWith(MockitoExtension.class)
class WebSocketServerTest {

    @Mock
    private NodeService nodeService;

    private WebSocketServer ws;
    private final List<WebSocketSession> adminSessionsToClean = new ArrayList<>();
    private final List<Long> nodeIdsToClean = new ArrayList<>();

    @BeforeEach
    void setUp() {
        ws = new WebSocketServer();
        ReflectionTestUtils.setField(ws, "nodeService", nodeService);
    }

    @AfterEach
    void tearDown() {
        for (WebSocketSession s : adminSessionsToClean) {
            try {
                ws.afterConnectionClosed(s, CloseStatus.NORMAL);
            } catch (Exception ignored) {
            }
        }
        adminSessionsToClean.clear();
        for (Long nid : nodeIdsToClean) {
            WebSocketServer.forgetNodeV6(nid);
            try {
                @SuppressWarnings("unchecked")
                Map<Long, WebSocketSession> nodeSessions =
                        (Map<Long, WebSocketSession>) ReflectionTestUtils.getField(WebSocketServer.class, "nodeSessions");
                if (nodeSessions != null) {
                    WebSocketSession s = nodeSessions.remove(nid);
                    if (s != null) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> locks =
                                (Map<String, Object>) ReflectionTestUtils.getField(WebSocketServer.class, "sessionLocks");
                        if (locks != null) {
                            locks.remove(s.getId());
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
        nodeIdsToClean.clear();
    }

    private WebSocketSession mockNodeSession(String sessionId, Long nodeId, String secret, String version) {
        nodeIdsToClean.add(nodeId);
        WebSocketSession s = mock(WebSocketSession.class);
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("id", String.valueOf(nodeId));
        attrs.put("type", "1");
        attrs.put("nodeSecret", secret);
        attrs.put("nodeVersion", version);
        lenient().when(s.getId()).thenReturn(sessionId);
        lenient().when(s.isOpen()).thenReturn(true);
        lenient().when(s.getAttributes()).thenReturn(attrs);
        return s;
    }

    private WebSocketSession mockAdminSession(String sessionId, Long userId) {
        WebSocketSession s = mock(WebSocketSession.class);
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("id", String.valueOf(userId));
        attrs.put("type", "0");
        lenient().when(s.getId()).thenReturn(sessionId);
        lenient().when(s.isOpen()).thenReturn(true);
        lenient().when(s.getAttributes()).thenReturn(attrs);
        adminSessionsToClean.add(s);
        return s;
    }

    @Test
    void afterConnectionEstablished_updatesOnlineAndCleansMissingOrBadSessions() throws Exception {
        WebSocketSession admin = mockAdminSession("adm-1", 1L);
        List<String> adminMessages = new ArrayList<>();
        doAnswer(inv -> {
            adminMessages.add(((TextMessage) inv.getArgument(0)).getPayload());
            return null;
        }).when(admin).sendMessage(any(TextMessage.class));
        ws.afterConnectionEstablished(admin);

        long nodeId = 93001L;
        Node node = new Node();
        node.setId(nodeId);
        node.setStatus(0);
        when(nodeService.getById(nodeId)).thenReturn(node);
        when(nodeService.updateById(any(Node.class))).thenReturn(true);

        WebSocketSession nodeSession = mockNodeSession("node-93001", nodeId, "sec-93001", "1.2.6");
        nodeSession.getAttributes().put("http", "1");
        nodeSession.getAttributes().put("tls", "1");
        nodeSession.getAttributes().put("socks", "0");

        ws.afterConnectionEstablished(nodeSession);

        assertEquals(1, node.getStatus());
        assertEquals("1.2.6", node.getVersion());
        assertEquals(1, node.getHttp());
        assertEquals(1, node.getTls());
        assertEquals(0, node.getSocks());
        assertTrue(adminMessages.stream().anyMatch(m -> m.contains("\"id\":\"93001\"") && m.contains("\"data\":1")));

        // 节点不存在 -> 移除无效会话，不发上线广播
        long missingNodeId = 93002L;
        when(nodeService.getById(missingNodeId)).thenReturn(null);
        WebSocketSession missingSession = mockNodeSession("node-93002", missingNodeId, "sec", "1.2.5");
        ws.afterConnectionEstablished(missingSession);
        assertEquals("节点不在线", WebSocketServer.send_msg(missingNodeId, "x", "Ping").getMsg());

        // 建连属性解析异常 -> 捕获并清理会话
        long badAttrNodeId = 93003L;
        Node badNode = new Node();
        badNode.setId(badAttrNodeId);
        when(nodeService.getById(badAttrNodeId)).thenReturn(badNode);
        WebSocketSession badAttrSession = mockNodeSession("node-93003", badAttrNodeId, "sec", "1.2.5");
        badAttrSession.getAttributes().put("http", "not-an-integer");
        assertDoesNotThrow(() -> ws.afterConnectionEstablished(badAttrSession));
        assertEquals("节点不在线", WebSocketServer.send_msg(badAttrNodeId, "x", "Ping").getMsg());
    }

    @Test
    void staleOldSessionClose_doesNotMarkNodeOfflineAfterNewSessionOverrides() throws Exception {
        long nodeId = 93004L;
        Node node = new Node();
        node.setId(nodeId);
        when(nodeService.getById(nodeId)).thenReturn(node);
        when(nodeService.updateById(any(Node.class))).thenReturn(true);

        WebSocketSession admin = mockAdminSession("adm-stale", 2L);
        List<String> broadcasts = new ArrayList<>();
        doAnswer(inv -> {
            broadcasts.add(((TextMessage) inv.getArgument(0)).getPayload());
            return null;
        }).when(admin).sendMessage(any(TextMessage.class));
        ws.afterConnectionEstablished(admin);

        WebSocketSession oldSession = mockNodeSession("old-93004", nodeId, "sec-1", "1.2.5");
        doThrow(new IOException("socket already dead")).when(oldSession).close();
        ws.afterConnectionEstablished(oldSession);

        WebSocketSession newSession = mockNodeSession("new-93004", nodeId, "sec-2", "1.2.6");
        ws.afterConnectionEstablished(newSession);
        verify(oldSession).close();

        // 模拟旧连接迟到的关闭回调
        broadcasts.clear();
        clearInvocations(nodeService);
        ws.afterConnectionClosed(oldSession, CloseStatus.NORMAL);

        // 会话不匹配 -> 不修改状态、不广播离线，且新连接仍然在线
        verify(nodeService, never()).updateById(any());
        assertTrue(broadcasts.isEmpty());

        AESCrypto newCrypto = AESCrypto.create("sec-2");
        doAnswer(inv -> {
            JSONObject wrap = JSONObject.parseObject(((TextMessage) inv.getArgument(0)).getPayload());
            JSONObject req = JSONObject.parseObject(newCrypto.decryptString(wrap.getString("data")));
            JSONObject resp = new JSONObject();
            resp.put("requestId", req.getString("requestId"));
            resp.put("message", "OK");
            ws.handleTextMessage(newSession, new TextMessage(resp.toJSONString()));
            return null;
        }).when(newSession).sendMessage(any(TextMessage.class));

        GostDto ping = WebSocketServer.send_msg(nodeId, new JSONObject(), "Check");
        assertEquals("OK", ping.getMsg());
    }

    @Test
    void afterConnectionClosed_activeSessionNormalOrAbnormal_marksOfflineAndBroadcasts() throws Exception {
        long nodeId = 93005L;
        Node node = new Node();
        node.setId(nodeId);
        node.setStatus(1);
        when(nodeService.getById(nodeId)).thenReturn(node);
        when(nodeService.updateById(any(Node.class))).thenReturn(true);

        WebSocketSession admin = mockAdminSession("adm-close", 3L);
        List<String> broadcasts = new ArrayList<>();
        doAnswer(inv -> {
            broadcasts.add(((TextMessage) inv.getArgument(0)).getPayload());
            return null;
        }).when(admin).sendMessage(any(TextMessage.class));
        ws.afterConnectionEstablished(admin);

        WebSocketSession active = mockNodeSession("active-93005", nodeId, "sec-93005", "1.2.6");
        ws.afterConnectionEstablished(active);
        broadcasts.clear();

        ws.afterConnectionClosed(active, CloseStatus.SESSION_NOT_RELIABLE);

        assertEquals(0, node.getStatus());
        assertTrue(broadcasts.stream().anyMatch(m -> m.contains("\"id\":\"93005\"") && m.contains("\"data\":0")));
        assertEquals("节点不在线", WebSocketServer.send_msg(nodeId, new JSONObject(), "Ping").getMsg());

        // 损坏 session 属性不抛异常
        WebSocketSession broken = mock(WebSocketSession.class);
        when(broken.getAttributes()).thenReturn(Collections.emptyMap());
        assertDoesNotThrow(() -> ws.afterConnectionClosed(broken, CloseStatus.SERVER_ERROR));
    }

    @Test
    void handleTextMessage_heartbeatStatusesEncryptionAndBroadcast() throws Exception {
        long nodeId = 93006L;
        String secret = "sec-hb-93006";
        Node node = new Node();
        node.setId(nodeId);
        when(nodeService.getById(nodeId)).thenReturn(node);
        when(nodeService.updateById(any(Node.class))).thenReturn(true);

        WebSocketSession admin = mockAdminSession("adm-hb", 4L);
        List<String> adminMsgs = new ArrayList<>();
        doAnswer(inv -> {
            adminMsgs.add(((TextMessage) inv.getArgument(0)).getPayload());
            return null;
        }).when(admin).sendMessage(any(TextMessage.class));
        ws.afterConnectionEstablished(admin);

        WebSocketSession nodeSession = mockNodeSession("hb-93006", nodeId, secret, "1.2.6");
        List<String> nodeReplies = new ArrayList<>();
        doAnswer(inv -> {
            nodeReplies.add(((TextMessage) inv.getArgument(0)).getPayload());
            return null;
        }).when(nodeSession).sendMessage(any(TextMessage.class));
        ws.afterConnectionEstablished(nodeSession);

        // 1. 明文心跳 + singbox 状态 + caps + ipv6
        String heartbeat = "{\"uptime\":12,\"memory_usage\":45.2,"
                + "\"singbox_installed\":true,\"singbox_installing\":false,"
                + "\"singbox_install_err\":\"old err\",\"singbox_running\":true,"
                + "\"caps\":[\"listen_switch\"],\"ipv6_addrs\":[\"2001:db8::6\"]}";
        ws.handleTextMessage(nodeSession, new TextMessage(heartbeat));

        assertEquals(Boolean.TRUE, WebSocketServer.getSingboxInstalled(nodeId));
        assertEquals(Boolean.FALSE, WebSocketServer.getSingboxInstalling(nodeId));
        assertEquals("old err", WebSocketServer.getSingboxInstallErr(nodeId));
        assertEquals(Boolean.TRUE, WebSocketServer.getSingboxRunning(nodeId));
        assertEquals(Collections.singletonList("listen_switch"), WebSocketServer.nodeCaps(nodeId));
        assertEquals(Collections.singletonList("2001:db8::6"), WebSocketServer.nodeV6Addrs(nodeId));
        assertTrue(adminMsgs.stream().anyMatch(m -> m.contains("\"type\":\"info\"") && m.contains("memory_usage")));

        AESCrypto crypto = AESCrypto.create(secret);
        assertNotNull(crypto);
        assertFalse(nodeReplies.isEmpty());
        JSONObject replyWrap = JSONObject.parseObject(nodeReplies.get(0));
        assertTrue(replyWrap.getBooleanValue("encrypted"));
        assertEquals("{\"type\":\"call\"}", crypto.decryptString(replyWrap.getString("data")));

        // 2. 加密上行消息 + 清除 singbox_install_err
        adminMsgs.clear();
        String clearPayload = "{\"uptime\":15,\"memory_usage\":40.0,\"singbox_install_err\":\"\"}";
        JSONObject encryptedIn = new JSONObject();
        encryptedIn.put("encrypted", true);
        encryptedIn.put("data", crypto.encrypt(clearPayload));
        ws.handleTextMessage(nodeSession, new TextMessage(encryptedIn.toJSONString()));

        assertNull(WebSocketServer.getSingboxInstallErr(nodeId));
        assertTrue(adminMsgs.stream().anyMatch(m -> m.contains("memory_usage")));

        // 3. 空消息和非法 JSON 安全跳过
        assertDoesNotThrow(() -> ws.handleTextMessage(nodeSession, new TextMessage("   ")));
        assertDoesNotThrow(() -> ws.handleTextMessage(nodeSession, new TextMessage("not-json-at-all")));
    }

    @Test
    void sendMsgAndSendToUser_handleDisconnectedPingResponseDefaultAndSendError() throws Exception {
        long nodeId = 93007L;
        String secret = "sec-cmd-93007";
        Node node = new Node();
        node.setId(nodeId);
        when(nodeService.getById(nodeId)).thenReturn(node);
        when(nodeService.updateById(any(Node.class))).thenReturn(true);

        // 1. 会话已关闭 (!isOpen) -> 清理会话并返回节点连接已断开
        WebSocketSession closedSoon = mockNodeSession("closed-93007", nodeId, secret, "1.2.6");
        ws.afterConnectionEstablished(closedSoon);
        when(closedSoon.isOpen()).thenReturn(false);
        assertEquals("节点连接已断开", WebSocketServer.send_msg(nodeId, new JSONObject(), "Check").getMsg());
        assertEquals("节点不在线", WebSocketServer.send_msg(nodeId, new JSONObject(), "Check").getMsg());

        // 2. 在线响应 PingResponse (message=null -> 默认 OK) 与普通响应 (message=null -> 默认无响应消息)
        WebSocketSession live = mockNodeSession("live-93007", nodeId, secret, "1.2.6");
        ws.afterConnectionEstablished(live);
        AESCrypto crypto = AESCrypto.create(secret);
        doAnswer(inv -> {
            JSONObject wrap = JSONObject.parseObject(((TextMessage) inv.getArgument(0)).getPayload());
            JSONObject req = JSONObject.parseObject(crypto.decryptString(wrap.getString("data")));
            String cmdType = req.getString("type");
            JSONObject resp = new JSONObject();
            resp.put("requestId", req.getString("requestId"));
            if ("TcpPing".equals(cmdType)) {
                resp.put("type", "PingResponse");
                JSONObject data = new JSONObject();
                data.put("avgTime", 21);
                resp.put("data", data);
            } else {
                resp.put("type", "PlainResponse");
            }
            ws.handleTextMessage(live, new TextMessage(resp.toJSONString()));
            return null;
        }).when(live).sendMessage(any(TextMessage.class));

        GostDto pingRes = WebSocketServer.send_msg(nodeId, new JSONObject(), "TcpPing");
        assertEquals("OK", pingRes.getMsg());
        assertNotNull(pingRes.getData());

        GostDto plainRes = WebSocketServer.send_msg(nodeId, new JSONObject(), "OtherCmd");
        assertEquals("无响应消息", plainRes.getMsg());

        // 3. sendToUser 发送异常时自动清理失效节点会话
        doThrow(new IOException("broken pipe")).when(live).sendMessage(any(TextMessage.class));
        assertDoesNotThrow(() -> WebSocketServer.sendToUser(live, "hello", secret));
        assertEquals("节点不在线", WebSocketServer.send_msg(nodeId, new JSONObject(), "Check").getMsg());
    }
}
