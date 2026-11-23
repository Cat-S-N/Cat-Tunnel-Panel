package com.admin.service.impl;

import com.admin.common.dto.ForwardDto;
import com.admin.common.dto.InboundDto;
import com.admin.common.dto.InboundUserDto;
import com.admin.common.dto.TunnelDto;
import com.admin.common.lang.R;
import com.admin.common.task.UserNodeNameStore;
import com.admin.common.utils.AESCrypto;
import com.admin.common.utils.JwtUtil;
import com.admin.common.utils.SubNames;
import com.admin.common.utils.WebSocketServer;
import com.admin.entity.Forward;
import com.admin.entity.Inbound;
import com.admin.entity.InboundLine;
import com.admin.entity.InboundUser;
import com.admin.entity.Landing;
import com.admin.entity.Node;
import com.admin.entity.Tunnel;
import com.admin.entity.User;
import com.admin.mapper.ForwardMapper;
import com.admin.mapper.InboundLineMapper;
import com.admin.mapper.InboundUserMapper;
import com.admin.mapper.LandingMapper;
import com.admin.mapper.NodeMapper;
import com.admin.mapper.TunnelMapper;
import com.admin.mapper.UserMapper;
import com.admin.service.ForwardService;
import com.admin.service.LandingService;
import com.admin.service.NodeService;
import com.admin.service.SpeedLimitService;
import com.admin.service.TunnelService;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * InboundServiceImpl 中转/入站创建、一键搭中转、删除保护、重复分配与线路续费边界单元测试
 */
@ExtendWith(MockitoExtension.class)
class InboundServiceImplTest {

    @Mock
    private InboundUserMapper inboundUserMapper;
    @Mock
    private NodeMapper nodeMapper;
    @Mock
    private TunnelMapper tunnelMapper;
    @Mock
    private TunnelService tunnelService;
    @Mock
    private ForwardService forwardService;
    @Mock
    private ForwardMapper forwardMapper;
    @Mock
    private SpeedLimitService speedLimitService;
    @Mock
    private UserMapper userMapper;
    @Mock
    private LandingMapper landingMapper;
    @Mock
    private InboundLineMapper inboundLineMapper;
    @Mock
    private LandingService landingService;
    @Mock
    private UserNodeNameStore nodeNameStore;

    private InboundServiceImpl inboundService;

    @BeforeEach
    void setUp() {
        JwtUtil jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "secretKey", "inbound-service-test-secret");
        jwtUtil.init();

        inboundService = Mockito.spy(new InboundServiceImpl());
        ReflectionTestUtils.setField(inboundService, "inboundUserMapper", inboundUserMapper);
        ReflectionTestUtils.setField(inboundService, "nodeMapper", nodeMapper);
        ReflectionTestUtils.setField(inboundService, "tunnelMapper", tunnelMapper);
        ReflectionTestUtils.setField(inboundService, "tunnelService", tunnelService);
        ReflectionTestUtils.setField(inboundService, "forwardService", forwardService);
        ReflectionTestUtils.setField(inboundService, "forwardMapper", forwardMapper);
        ReflectionTestUtils.setField(inboundService, "speedLimitService", speedLimitService);
        ReflectionTestUtils.setField(inboundService, "userMapper", userMapper);
        ReflectionTestUtils.setField(inboundService, "landingMapper", landingMapper);
        ReflectionTestUtils.setField(inboundService, "inboundLineMapper", inboundLineMapper);
        ReflectionTestUtils.setField(inboundService, "landingService", landingService);
        ReflectionTestUtils.setField(inboundService, "nodeNameStore", nodeNameStore);
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    private Node sampleNode(Long id) {
        Node n = new Node();
        n.setId(id);
        n.setName("node-" + id);
        n.setServerIp("203.0.113.10");
        n.setStatus(1);
        return n;
    }

    private User sampleUser(Long id) {
        User u = new User();
        u.setId(id);
        u.setUser("user-" + id);
        u.setRoleId(1);
        u.setStatus(1);
        return u;
    }

    private Tunnel sampleTunnel(Long id, Long nodeId) {
        Tunnel t = new Tunnel();
        t.setId(id);
        t.setInNodeId(nodeId);
        t.setType(1);
        t.setName("inbound-tunnel-node" + nodeId);
        t.setTcpListenAddr("0.0.0.0");
        t.setUdpListenAddr("0.0.0.0");
        return t;
    }

    private interface WsAutoResponder {
        JSONObject respond(JSONObject req);
    }

    private static class OnlineNodeHandle implements AutoCloseable {
        final WebSocketServer ws = new WebSocketServer();
        final WebSocketSession session = mock(WebSocketSession.class);
        final Long nodeId;

        OnlineNodeHandle(Long nodeId, WsAutoResponder responder) throws Exception {
            this.nodeId = nodeId;
            String secret = "inbound-ws-secret-" + nodeId;
            Node node = new Node();
            node.setId(nodeId);
            node.setStatus(0);

            NodeService mockNs = mock(NodeService.class);
            when(mockNs.getById(nodeId)).thenReturn(node);
            when(mockNs.updateById(any(Node.class))).thenReturn(true);
            ReflectionTestUtils.setField(ws, "nodeService", mockNs);

            Map<String, Object> attrs = new HashMap<>();
            attrs.put("id", String.valueOf(nodeId));
            attrs.put("type", "1");
            attrs.put("nodeSecret", secret);
            when(session.getId()).thenReturn("ws-in-" + nodeId);
            when(session.isOpen()).thenReturn(true);
            when(session.getAttributes()).thenReturn(attrs);

            AESCrypto crypto = AESCrypto.create(secret);
            assertNotNull(crypto);
            doAnswer(invocation -> {
                TextMessage msg = invocation.getArgument(0);
                JSONObject wrap = JSONObject.parseObject(msg.getPayload());
                String plain = wrap.getBooleanValue("encrypted")
                        ? crypto.decryptString(wrap.getString("data"))
                        : msg.getPayload();
                JSONObject req = JSONObject.parseObject(plain);
                JSONObject resp = responder.respond(req);
                if (resp != null) {
                    resp.put("requestId", req.getString("requestId"));
                    ws.handleTextMessage(session, new TextMessage(resp.toJSONString()));
                }
                return null;
            }).when(session).sendMessage(any(TextMessage.class));

            ws.afterConnectionEstablished(session);
        }

        @Override
        public void close() {
            ws.afterConnectionClosed(session, CloseStatus.NORMAL);
            WebSocketServer.forgetNodeV6(nodeId);
        }
    }

    @Test
    void pushNodeConfig_missingIdOrOfflineNode_returnsError() {
        R missing = inboundService.pushNodeConfig(null);
        assertNotEquals(0, missing.getCode());
        assertEquals("缺少节点 id", missing.getMsg());

        doReturn(Collections.emptyList()).when(inboundService).list(any(QueryWrapper.class));
        R offline = inboundService.pushNodeConfig(92001L);
        assertNotEquals(0, offline.getCode());
        assertEquals("下发 sing-box 配置失败:节点不在线", offline.getMsg());
    }

    @Test
    void createInbound_validationAndRollbackOnPushFailure() {
        when(nodeMapper.selectById(92001L)).thenReturn(null);
        InboundDto missingNodeDto = new InboundDto();
        missingNodeDto.setNodeId(92001L);
        assertEquals("节点不存在", inboundService.createInbound(missingNodeDto).getMsg());

        when(nodeMapper.selectById(92001L)).thenReturn(sampleNode(92001L));
        doReturn(Collections.emptyList()).when(inboundService).list(any(QueryWrapper.class));
        InboundDto badProto = new InboundDto();
        badProto.setNodeId(92001L);
        badProto.setProtocol("wireguard");
        assertEquals("暂不支持的协议:wireguard", inboundService.createInbound(badProto).getMsg());

        InboundDto noSniVless = new InboundDto();
        noSniVless.setNodeId(92001L);
        noSniVless.setProtocol("vless");
        assertEquals("Reality 协议需要 SNI", inboundService.createInbound(noSniVless).getMsg());

        InboundDto vlessOffline = new InboundDto();
        vlessOffline.setNodeId(92001L);
        vlessOffline.setProtocol("vless");
        vlessOffline.setSni("www.apple.com");
        R kpFail = inboundService.createInbound(vlessOffline);
        assertTrue(kpFail.getMsg().contains("生成 Reality 密钥失败:节点不在线"));

        // 默认空协议 -> shadowsocks，保存失败直接返回
        InboundDto ssSaveFail = new InboundDto();
        ssSaveFail.setNodeId(92001L);
        doReturn(false).when(inboundService).save(any(Inbound.class));
        assertEquals("入站保存失败", inboundService.createInbound(ssSaveFail).getMsg());

        // vmess + ws 保存成功，但推节点离线失败 -> 回滚 removeById
        InboundDto vmessWs = new InboundDto();
        vmessWs.setNodeId(92001L);
        vmessWs.setProtocol("vmess");
        vmessWs.setTransport("ws");
        vmessWs.setWsPath("custom");
        vmessWs.setWsHost("cdn.example.com");
        doAnswer(invocation -> {
            Inbound saved = invocation.getArgument(0);
            saved.setId(777L);
            return true;
        }).when(inboundService).save(any(Inbound.class));
        doReturn(true).when(inboundService).removeById(777L);

        R pushFail = inboundService.createInbound(vmessWs);
        assertNotEquals(0, pushFail.getCode());
        assertEquals("下发 sing-box 配置失败:节点不在线", pushFail.getMsg());
        verify(inboundService).removeById(777L);
    }

    @Test
    void createInbound_onlineNodeHysteria2_sanitizesSniAndPushesConfig() throws Exception {
        long nodeId = 92002L;
        when(nodeMapper.selectById(nodeId)).thenReturn(sampleNode(nodeId));
        Inbound existing = new Inbound();
        existing.setListenPort(40005);
        doReturn(Collections.singletonList(existing)).when(inboundService).list(any(QueryWrapper.class));
        doAnswer(invocation -> {
            Inbound saved = invocation.getArgument(0);
            saved.setId(888L);
            return true;
        }).when(inboundService).save(any(Inbound.class));

        try (OnlineNodeHandle ignored = new OnlineNodeHandle(nodeId, req -> {
            JSONObject resp = new JSONObject();
            resp.put("message", "OK");
            return resp;
        })) {
            InboundDto dto = new InboundDto();
            dto.setNodeId(nodeId);
            dto.setProtocol("hysteria2");
            dto.setSni("bad sni (说明文字)");

            R res = inboundService.createInbound(dto);

            assertEquals(0, res.getCode());
            Inbound created = (Inbound) res.getData();
            assertEquals("tls", created.getSecurity());
            assertEquals("www.bing.com", created.getSni());
            assertEquals(40006, created.getListenPort());
        }
    }

    @Test
    void oneClickCreateAndOneClickRelay_coverFailuresAndRelaySuccess() throws Exception {
        when(nodeMapper.selectById(92001L)).thenReturn(null);
        assertEquals("节点不存在", inboundService.oneClickCreate(92001L, null).getMsg());
        assertEquals("前置机不存在", inboundService.oneClickRelay(92001L, "1.2.3.4:1080", null, null).getMsg());

        when(nodeMapper.selectById(92001L)).thenReturn(sampleNode(92001L));
        doReturn(Collections.emptyList()).when(inboundService).list(any(QueryWrapper.class));
        R interrupted = inboundService.oneClickCreate(92001L, null);
        assertTrue(interrupted.getMsg().contains("一键添加中断(vless)"));

        when(landingService.createLanding(any())).thenReturn(R.err("链接为空"));
        R badRelayLanding = inboundService.oneClickRelay(92001L, "", null, null);
        assertEquals("落地解析失败:链接为空", badRelayLanding.getMsg());

        long nodeId = 92003L;
        when(nodeMapper.selectById(nodeId)).thenReturn(sampleNode(nodeId));
        Landing landing = new Landing();
        landing.setId(55L);
        landing.setOutboundJson("{\"type\":\"socks\"}");
        when(landingService.createLanding(any())).thenReturn(R.ok(landing));
        when(landingMapper.selectById(55L)).thenReturn(landing);
        List<Inbound> stored = new ArrayList<>();
        doAnswer(inv -> stored).when(inboundService).list(any(QueryWrapper.class));
        doAnswer(inv -> {
            Inbound in = inv.getArgument(0);
            in.setId((long) (stored.size() + 1));
            stored.add(in);
            return true;
        }).when(inboundService).save(any(Inbound.class));

        try (OnlineNodeHandle ignored = new OnlineNodeHandle(nodeId, req -> {
            JSONObject resp = new JSONObject();
            resp.put("message", "OK");
            if ("GenerateRealityKeypair".equals(req.getString("type"))) {
                JSONObject d = new JSONObject();
                d.put("privateKey", "priv-1");
                d.put("publicKey", "pub-1");
                resp.put("data", d);
            }
            return resp;
        })) {
            R relayOk = inboundService.oneClickRelay(nodeId, "socks5://1.2.3.4:1080", "  ", null);
            assertEquals(0, relayOk.getCode());
            List<?> created = (List<?>) relayOk.getData();
            assertEquals(6, created.size());
            verify(landingService).createLanding(argThat(l -> "落地-node-92003".equals(l.getName())));
        }
    }

    @Test
    void deleteInboundAndByNode_protectWhenGostRejectsAndCleanWhenUnreachable() {
        doReturn(null).when(inboundService).getById(404L);
        R idempotent = inboundService.deleteInbound(404L);
        assertEquals(0, idempotent.getCode());

        // 2 个用户：u1 节点离线兜底删除，u2 gost 明确报错保留入站与分配
        Inbound in = new Inbound();
        in.setId(10L);
        in.setNodeId(92001L);
        in.setLandingId(5L);
        doReturn(in).when(inboundService).getById(10L);

        InboundUser u1 = new InboundUser();
        u1.setId(101L);
        u1.setUserId(1L);
        u1.setInboundId(10L);
        u1.setGostForwardId(1001L);

        InboundUser u2 = new InboundUser();
        u2.setId(102L);
        u2.setUserId(2L);
        u2.setInboundId(10L);
        u2.setGostForwardId(1002L);

        when(inboundUserMapper.selectList(any(QueryWrapper.class))).thenReturn(Arrays.asList(u1, u2), Collections.emptyList());
        when(forwardMapper.selectById(1001L)).thenReturn(new Forward());
        when(forwardMapper.selectById(1002L)).thenReturn(new Forward());
        when(forwardService.deleteForward(1001L)).thenReturn(R.err("节点不在线"));
        when(forwardService.deleteForward(1002L)).thenReturn(R.err("chain 1002 is in use"));
        doReturn(Collections.emptyList()).when(inboundService).list(any(QueryWrapper.class));

        R blocked = inboundService.deleteInbound(10L);
        assertNotEquals(0, blocked.getCode());
        assertTrue(blocked.getMsg().contains("有 1 条转发没删掉,协议先保留"));
        verify(forwardMapper).deleteById(1001L);
        verify(inboundUserMapper).deleteById(101L);
        verify(inboundUserMapper, never()).deleteById(102L);
        verify(inboundService, never()).removeById(10L);

        // 只剩可删用户时整条删除成功并清理节点别名
        when(inboundUserMapper.selectList(any(QueryWrapper.class))).thenReturn(Collections.singletonList(u1), Collections.emptyList());
        doReturn(true).when(inboundService).removeById(10L);
        R deleted = inboundService.deleteInbound(10L);
        assertEquals(0, deleted.getCode());
        verify(inboundService).removeById(10L);
        verify(nodeNameStore).deleteInbound(10L);

        // deleteInboundsByNode 聚合失败原因
        Inbound i1 = new Inbound();
        i1.setId(10L);
        doReturn(Collections.singletonList(i1)).when(inboundService).list(any(QueryWrapper.class));
        doReturn(R.err("转发报错")).when(inboundService).deleteInbound(10L);
        R batchErr = inboundService.deleteInboundsByNode(92001L, true, 5L);
        assertNotEquals(0, batchErr.getCode());
        assertTrue(batchErr.getMsg().contains("协议[10]转发报错"));
    }

    @Test
    void assignUser_handlesNotFoundDuplicateOrphanSelfHealAndSuccess() throws Exception {
        InboundUserDto dto = new InboundUserDto();
        dto.setInboundId(10L);
        dto.setUserId(2L);

        doReturn(null).when(inboundService).getById(10L);
        assertEquals("入站不存在", inboundService.assignUser(dto).getMsg());

        Inbound in = new Inbound();
        in.setId(10L);
        in.setNodeId(92001L);
        in.setProtocol("vless");
        in.setListenPort(40001);
        in.setSni("www.apple.com");
        in.setPublicKey("pk");
        in.setShortId("abcd");
        doReturn(in).when(inboundService).getById(10L);

        when(userMapper.selectById(2L)).thenReturn(null);
        assertEquals("用户不存在", inboundService.assignUser(dto).getMsg());

        User user = sampleUser(2L);
        when(userMapper.selectById(2L)).thenReturn(user);
        when(nodeMapper.selectById(92001L)).thenReturn(null);
        assertEquals("节点不存在", inboundService.assignUser(dto).getMsg());

        // 1. 重复分配（已有记录 + 对应 Forward 存在）直接复用不重复建转发
        Node node = sampleNode(92001L);
        when(nodeMapper.selectById(92001L)).thenReturn(node);
        InboundUser existed = new InboundUser();
        existed.setId(50L);
        existed.setInboundId(10L);
        existed.setUserId(2L);
        existed.setUuid("uuid-old");
        existed.setGostForwardId(500L);
        existed.setSubToken("tok-old");
        Forward existedFwd = new Forward();
        existedFwd.setId(500L);
        existedFwd.setInPort(20001);
        when(inboundUserMapper.selectOne(any(QueryWrapper.class))).thenReturn(existed);
        when(forwardMapper.selectById(500L)).thenReturn(existedFwd);
        doReturn(Collections.singletonList(in)).when(inboundService).list(any(QueryWrapper.class));
        when(inboundUserMapper.selectList(any(QueryWrapper.class))).thenReturn(Collections.singletonList(existed));

        R reused = inboundService.assignUser(dto);
        assertEquals(0, reused.getCode());
        JSONObject reusedData = (JSONObject) reused.getData();
        assertEquals(50L, reusedData.getLong("inboundUserId"));
        assertEquals("tok-old", reusedData.getString("subToken"));
        assertTrue(reusedData.getString("link").contains("uuid-old@203.0.113.10:20001"));
        verify(forwardService, never()).createForwardForUser(any(), any(), any());

        // 2. 孤儿自愈：existed 的转发已被删，清理孤儿后走新建流程，若隧道创建失败则返回明确错误
        when(forwardMapper.selectById(500L)).thenReturn(null);
        when(tunnelMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);
        when(tunnelService.createTunnel(any(TunnelDto.class))).thenReturn(R.err("节点离线"));
        R tunnelFail = inboundService.assignUser(dto);
        verify(inboundUserMapper).deleteById(50L);
        assertEquals("创建入站转发隧道失败", tunnelFail.getMsg());

        // 3. 限速器下发失败拦截
        when(inboundUserMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);
        when(tunnelMapper.selectOne(any(QueryWrapper.class))).thenReturn(sampleTunnel(3L, 92001L));
        dto.setSpeedId(9);
        when(speedLimitService.pushUserLimiter(eq(9), anyLong(), eq(92001L))).thenReturn(R.err("限速规则不存在"));
        assertEquals("下发限速器失败:限速规则不存在", inboundService.assignUser(dto).getMsg());
    }

    @Test
    void assignAllToUser_validatesParamsEmptyGroupAndRenewalUpdate() {
        InboundUserDto dto = new InboundUserDto();
        dto.setUserId(2L);
        when(userMapper.selectById(2L)).thenReturn(null);
        assertEquals("用户不存在", inboundService.assignAllToUser(dto).getMsg());

        User user = sampleUser(2L);
        when(userMapper.selectById(2L)).thenReturn(user);
        dto.setRelay(true);
        dto.setLandingId(null);
        assertEquals("中转分配缺落地", inboundService.assignAllToUser(dto).getMsg());

        dto.setRelay(false);
        dto.setNodeId(92001L);
        doReturn(Collections.emptyList()).when(inboundService).list(any(QueryWrapper.class));
        assertTrue(inboundService.assignAllToUser(dto).getMsg().contains("还没有直连协议"));

        dto.setRelay(true);
        dto.setLandingId(5L);
        assertEquals("这条中转还没有协议", inboundService.assignAllToUser(dto).getMsg());

        // 已全部分配过时传 speedId / expTime 触发续费重推分支
        Inbound in = new Inbound();
        in.setId(10L);
        in.setNodeId(92001L);
        in.setLandingId(5L);
        in.setStatus(1);
        doReturn(Collections.singletonList(in)).when(inboundService).list(any(QueryWrapper.class));
        InboundLine line = new InboundLine();
        line.setId(1L);
        line.setSubToken("line-tok");
        when(inboundLineMapper.selectOne(any(QueryWrapper.class))).thenReturn(line);

        InboundUser existed = new InboundUser();
        existed.setId(77L);
        existed.setGostForwardId(700L);
        when(inboundUserMapper.selectOne(any(QueryWrapper.class))).thenReturn(existed);
        Forward f = new Forward();
        f.setId(700L);
        f.setStatus(0);
        when(forwardMapper.selectById(700L)).thenReturn(f);
        when(nodeMapper.selectById(92001L)).thenReturn(sampleNode(92001L));
        when(speedLimitService.pushUserLimiter(eq(6), anyLong(), eq(92001L))).thenReturn(R.ok());

        dto.setSpeedId(6);
        dto.setExpTime(9999999999999L);
        dto.setFlow(100L);
        R renewRes = inboundService.assignAllToUser(dto);
        assertEquals(0, renewRes.getCode());
        JSONObject data = (JSONObject) renewRes.getData();
        assertEquals(0, data.getIntValue("assigned"));
        assertEquals(1, data.getIntValue("skipped"));
        assertEquals(1, data.getIntValue("updated"));
        verify(forwardService).updateForwardA(f);
    }

    @Test
    void lineLifecycle_updateLineSetStatusAndDeleteLine() {
        assertEquals("参数不全", inboundService.updateLine(null, 1L, null, 10L, null, null).getMsg());
        when(inboundLineMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);
        assertEquals("线路不存在", inboundService.updateLine(2L, 92001L, null, 10L, null, null).getMsg());

        InboundLine line = new InboundLine();
        line.setId(11L);
        line.setUserId(2L);
        line.setNodeId(92001L);
        line.setStatus(0);
        when(inboundLineMapper.selectOne(any(QueryWrapper.class))).thenReturn(line);
        when(inboundUserMapper.selectList(any(QueryWrapper.class))).thenReturn(Collections.emptyList());
        assertTrue(inboundService.updateLine(2L, 92001L, null, 10L, null, null).getMsg().contains("没有协议"));

        Inbound in = new Inbound();
        in.setId(10L);
        in.setNodeId(92001L);
        InboundUser iu = new InboundUser();
        iu.setId(1L);
        iu.setInboundId(10L);
        iu.setUserId(2L);
        iu.setGostForwardId(501L);
        Forward pausedFwd = new Forward();
        pausedFwd.setId(501L);
        pausedFwd.setStatus(0);
        pausedFwd.setInFlow(100L);
        pausedFwd.setOutFlow(100L);

        when(inboundUserMapper.selectList(any(QueryWrapper.class))).thenReturn(Collections.singletonList(iu));
        doReturn(in).when(inboundService).getById(10L);
        when(forwardService.getById(501L)).thenReturn(pausedFwd);
        when(speedLimitService.pushUserLimiter(eq(4), anyLong(), eq(92001L))).thenReturn(R.ok());

        R resumed = inboundService.updateLine(2L, 92001L, null, 50L, System.currentTimeMillis() + 86_400_000L, 4);
        assertEquals(0, resumed.getCode());
        JSONObject rd = (JSONObject) resumed.getData();
        assertEquals(1, rd.getIntValue("status"));
        assertEquals(true, rd.getBoolean("resumed"));
        verify(forwardService).resumeForward(501L);

        // setLineStatus
        assertEquals("参数不全", inboundService.setLineStatus(2L, 92001L, null, null).getMsg());
        assertEquals(0, inboundService.setLineStatus(2L, 92001L, null, 0).getCode());
        verify(forwardService).pauseForward(501L);

        // deleteLine 转发删除报错保护
        when(forwardMapper.selectById(501L)).thenReturn(pausedFwd);
        when(forwardService.deleteForward(501L)).thenReturn(R.err("gost busy"));
        R delBlocked = inboundService.deleteLine(2L, 92001L, null);
        assertTrue(delBlocked.getMsg().contains("有 1 条转发没删掉,线路先保留"));
        verify(inboundLineMapper, never()).deleteById(any());
    }

    @Test
    void renameResetTokenSocksOpsAndNodeNames_coverBoundaryChecks() {
        assertEquals("参数不全", inboundService.renameInbound(null, "a").getMsg());
        doReturn(null).when(inboundService).getById(99L);
        assertEquals("协议不存在", inboundService.renameInbound(99L, "a").getMsg());

        Inbound normal = new Inbound();
        normal.setId(99L);
        normal.setProtocol("vless");
        doReturn(normal).when(inboundService).getById(99L);
        doReturn(true).when(inboundService).updateById(normal);
        assertEquals(0, inboundService.renameInbound(99L, "  新名称  ").getCode());
        assertEquals("新名称", normal.getRemark());

        // resetSubToken
        assertEquals("缺少用户ID", inboundService.resetSubToken(null, 1L, null, false).getMsg());
        when(userMapper.selectById(2L)).thenReturn(null);
        assertEquals("用户不存在", inboundService.resetSubToken(2L, 1L, null, false).getMsg());
        when(userMapper.selectById(2L)).thenReturn(sampleUser(2L));
        assertEquals("缺少节点ID", inboundService.resetSubToken(2L, null, null, false).getMsg());
        assertEquals(0, inboundService.resetSubToken(2L, null, null, true).getCode());

        // resetSocksPassword & testSocksNode
        assertEquals("缺少分配记录ID", inboundService.resetSocksPassword(null).getMsg());
        InboundUser iu = new InboundUser();
        iu.setId(88L);
        iu.setInboundId(99L);
        iu.setUserId(2L);
        when(inboundUserMapper.selectById(88L)).thenReturn(iu);
        assertTrue(inboundService.resetSocksPassword(88L).getMsg().contains("不是独立代理协议"));

        Inbound socksIn = new Inbound();
        socksIn.setId(100L);
        socksIn.setNodeId(92001L);
        socksIn.setProtocol("socks");
        iu.setInboundId(100L);
        doReturn(socksIn).when(inboundService).getById(100L);
        User otherUser = sampleUser(999L);
        when(userMapper.selectById(999L)).thenReturn(otherUser);
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Authorization", JwtUtil.generateToken(otherUser));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
        R forbidden = inboundService.testSocksNode(88L, 999L, false);
        assertEquals(403, forbidden.getCode());

        // saveUserNodeNames
        assertEquals("参数不全", inboundService.saveUserNodeNames(null, Collections.emptyList()).getMsg());
        when(nodeNameStore.isAvailable()).thenReturn(false);
        assertTrue(inboundService.saveUserNodeNames(2L, Collections.emptyList()).getMsg().contains("车友节点名表暂时不可用"));

        when(nodeNameStore.isAvailable()).thenReturn(true);
        List<Map<String, Object>> oversized = new ArrayList<>();
        for (int i = 0; i < 501; i++) {
            oversized.add(Collections.emptyMap());
        }
        assertEquals("一次改得太多了", inboundService.saveUserNodeNames(2L, oversized).getMsg());

        Map<String, Object> badScope = new HashMap<>();
        badScope.put("scope", "unknown");
        badScope.put("name", "节点A");
        assertEquals("不认识的类型:unknown", inboundService.saveUserNodeNames(2L, Collections.singletonList(badScope)).getMsg());
    }
}
