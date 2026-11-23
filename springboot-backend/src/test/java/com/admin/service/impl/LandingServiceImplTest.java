package com.admin.service.impl;

import com.admin.common.dto.LandingDto;
import com.admin.common.lang.R;
import com.admin.common.utils.AESCrypto;
import com.admin.common.utils.WebSocketServer;
import com.admin.entity.Inbound;
import com.admin.entity.Landing;
import com.admin.entity.Node;
import com.admin.mapper.InboundMapper;
import com.admin.mapper.NodeMapper;
import com.admin.service.NodeService;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * LandingServiceImpl 落地解析、更新重推集合收集、占用删除保护与测速边界单元测试
 */
@ExtendWith(MockitoExtension.class)
class LandingServiceImplTest {

    @Mock
    private InboundMapper inboundMapper;

    @Mock
    private NodeMapper nodeMapper;

    private LandingServiceImpl landingService;

    @BeforeEach
    void setUp() {
        landingService = Mockito.spy(new LandingServiceImpl());
        ReflectionTestUtils.setField(landingService, "inboundMapper", inboundMapper);
        ReflectionTestUtils.setField(landingService, "nodeMapper", nodeMapper);
    }

    private LandingDto dto(Long id, String name, String link, String remark) {
        LandingDto d = new LandingDto();
        d.setId(id);
        d.setName(name);
        d.setLink(link);
        d.setRemark(remark);
        return d;
    }

    @Test
    void createLanding_invalidOrUnsupportedLink_returnsErrorWithoutSaving() {
        R nullLink = landingService.createLanding(dto(null, "落地A", null, null));
        assertNotEquals(0, nullLink.getCode());
        assertEquals("链接为空", nullLink.getMsg());

        R naiveLink = landingService.createLanding(dto(null, "落地B", "naive+https://u:p@1.2.3.4:443", null));
        assertNotEquals(0, naiveLink.getCode());
        assertTrue(naiveLink.getMsg().contains("naive"));

        R unknownBare = landingService.createLanding(dto(null, "落地C", "not-a-valid-link", null));
        assertNotEquals(0, unknownBare.getCode());
        assertTrue(unknownBare.getMsg().contains("落地链接不认识"));

        verify(landingService, never()).save(any());
    }

    @Test
    void createLanding_validSocksAndProtocolLinks_savesParsedOutbound() {
        doReturn(true).when(landingService).save(any(Landing.class));

        R socksRes = landingService.createLanding(
                dto(null, "美国住宅", "  1.2.3.4:1080:user1:pass1  ", "备注"));
        assertEquals(0, socksRes.getCode());
        Landing savedSocks = (Landing) socksRes.getData();
        assertEquals("美国住宅", savedSocks.getName());
        assertEquals("socks5", savedSocks.getType());
        assertEquals("1.2.3.4:1080:user1:pass1", savedSocks.getLink());
        assertEquals(1, savedSocks.getStatus());
        assertTrue(savedSocks.getOutboundJson().contains("\"server\":\"1.2.3.4\""));
        assertTrue(savedSocks.getOutboundJson().contains("\"server_port\":1080"));

        R trojanRes = landingService.createLanding(
                dto(null, "中转Trojan", "trojan://secret@example.com:443?sni=example.com#tag", null));
        assertEquals(0, trojanRes.getCode());
        Landing savedTrojan = (Landing) trojanRes.getData();
        assertEquals("trojan", savedTrojan.getType());
        assertTrue(savedTrojan.getOutboundJson().contains("\"password\":\"secret\""));
    }

    @Test
    void createLanding_saveFails_returnsError() {
        doReturn(false).when(landingService).save(any(Landing.class));

        R res = landingService.createLanding(dto(null, "落地", "socks5://u:p@1.2.3.4:1080", null));

        assertNotEquals(0, res.getCode());
        assertEquals("落地保存失败", res.getMsg());
    }

    @Test
    void updateLanding_missingIdOrNotFoundOrBadLink_returnsError() {
        R missingId = landingService.updateLanding(dto(null, "n", "1.2.3.4:1080", null));
        assertNotEquals(0, missingId.getCode());
        assertEquals("缺少落地 id", missingId.getMsg());

        doReturn(null).when(landingService).getById(99L);
        R notFound = landingService.updateLanding(dto(99L, "n", "1.2.3.4:1080", null));
        assertNotEquals(0, notFound.getCode());
        assertEquals("落地不存在(可能已经被删了)", notFound.getMsg());

        Landing existing = new Landing();
        existing.setId(9L);
        existing.setRemark("旧备注");
        doReturn(existing).when(landingService).getById(9L);
        R badLink = landingService.updateLanding(dto(9L, "n", "badlink", null));
        assertNotEquals(0, badLink.getCode());
        verify(landingService, never()).updateById(any());
    }

    @Test
    void updateLanding_updateFails_returnsError() {
        Landing existing = new Landing();
        existing.setId(9L);
        doReturn(existing).when(landingService).getById(9L);
        doReturn(false).when(landingService).updateById(any(Landing.class));

        R res = landingService.updateLanding(dto(9L, "新名", "socks5://1.2.3.4:1080", null));

        assertNotEquals(0, res.getCode());
        assertEquals("落地保存失败", res.getMsg());
    }

    @Test
    @SuppressWarnings("unchecked")
    void updateLanding_success_collectsDistinctNonNullNodeIds() {
        Landing existing = new Landing();
        existing.setId(9L);
        existing.setRemark("保留旧备注");
        doReturn(existing).when(landingService).getById(9L);
        doReturn(true).when(landingService).updateById(any(Landing.class));

        Inbound in1 = new Inbound();
        in1.setNodeId(101L);
        Inbound in2 = new Inbound();
        in2.setNodeId(102L);
        Inbound inDup = new Inbound();
        inDup.setNodeId(101L);
        Inbound inNullNode = new Inbound();
        inNullNode.setNodeId(null);
        when(inboundMapper.selectList(any(QueryWrapper.class)))
                .thenReturn(Arrays.asList(in1, in2, inDup, inNullNode));

        R res = landingService.updateLanding(dto(9L, "新落地", "socks5://user:pass@8.8.8.8:1080", null));

        assertEquals(0, res.getCode());
        JSONObject data = (JSONObject) res.getData();
        Landing updated = (Landing) data.get("landing");
        assertEquals("新落地", updated.getName());
        assertEquals("保留旧备注", updated.getRemark());
        Set<Long> nodeIds = (Set<Long>) data.get("nodeIds");
        assertEquals(2, nodeIds.size());
        assertTrue(nodeIds.contains(101L));
        assertTrue(nodeIds.contains(102L));
    }

    @Test
    void getLandings_returnsOrderedList() {
        Landing l1 = new Landing();
        l1.setId(2L);
        Landing l2 = new Landing();
        l2.setId(1L);
        List<Landing> list = Arrays.asList(l1, l2);
        doReturn(list).when(landingService).list(any(QueryWrapper.class));

        R res = landingService.getLandings();

        assertEquals(0, res.getCode());
        assertSame(list, res.getData());
    }

    @Test
    void deleteLanding_inUseByInbounds_blocksDelete() {
        when(inboundMapper.selectCount(any(QueryWrapper.class))).thenReturn(3);

        R res = landingService.deleteLanding(15L);

        assertNotEquals(0, res.getCode());
        assertEquals("这条落地正在被 3 个中转协议使用,先清空对应机器的中转再删", res.getMsg());
        verify(landingService, never()).removeById(any());
    }

    @Test
    void deleteLanding_unused_removesAndReturnsOk() {
        when(inboundMapper.selectCount(any(QueryWrapper.class))).thenReturn(0);
        doReturn(true).when(landingService).removeById(15L);

        R res = landingService.deleteLanding(15L);

        assertEquals(0, res.getCode());
        verify(landingService).removeById(15L);
    }

    @Test
    void testLanding_invalidLinkAndProtocolSkipAndNodeMissingAndOffline() {
        R bad = landingService.testLanding(91001L, null);
        assertNotEquals(0, bad.getCode());
        assertEquals("链接为空", bad.getMsg());

        R skipped = landingService.testLanding(91001L, "vless://11111111-1111-1111-1111-111111111111@1.2.3.4:443?security=none");
        assertEquals(0, skipped.getCode());
        JSONObject skippedData = (JSONObject) skipped.getData();
        assertEquals(true, skippedData.getBoolean("ok"));
        assertEquals(true, skippedData.getBoolean("skipped"));
        assertEquals("vless", skippedData.getString("type"));
        verify(nodeMapper, never()).selectById(any());

        when(nodeMapper.selectById(91001L)).thenReturn(null);
        R noNode = landingService.testLanding(91001L, "1.2.3.4:1080:u:p");
        assertNotEquals(0, noNode.getCode());
        assertEquals("前置机不存在", noNode.getMsg());

        Node node = new Node();
        node.setId(91001L);
        when(nodeMapper.selectById(91001L)).thenReturn(node);
        R offline = landingService.testLanding(91001L, "1.2.3.4:1080:u:p");
        assertNotEquals(0, offline.getCode());
        assertEquals("经前置机测落地不通:节点不在线", offline.getMsg());
    }

    @Test
    void testLanding_onlineNodeReturnsProbeResult() throws Exception {
        long nodeId = 91002L;
        String secret = "landing-test-secret-91002";
        Node node = new Node();
        node.setId(nodeId);
        node.setStatus(0);

        NodeService mockNodeService = mock(NodeService.class);
        when(mockNodeService.getById(nodeId)).thenReturn(node);
        when(mockNodeService.updateById(any(Node.class))).thenReturn(true);

        WebSocketServer ws = new WebSocketServer();
        ReflectionTestUtils.setField(ws, "nodeService", mockNodeService);

        WebSocketSession session = mock(WebSocketSession.class);
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("id", String.valueOf(nodeId));
        attrs.put("type", "1");
        attrs.put("nodeSecret", secret);
        when(session.getId()).thenReturn("ws-landing-" + nodeId);
        when(session.isOpen()).thenReturn(true);
        when(session.getAttributes()).thenReturn(attrs);

        AESCrypto crypto = AESCrypto.create(secret);
        assertNotNull(crypto);
        doAnswer(invocation -> {
            TextMessage msg = invocation.getArgument(0);
            JSONObject wrapper = JSONObject.parseObject(msg.getPayload());
            String plain = wrapper.getBooleanValue("encrypted")
                    ? crypto.decryptString(wrapper.getString("data"))
                    : msg.getPayload();
            JSONObject req = JSONObject.parseObject(plain);
            if ("TestOutbound".equals(req.getString("type"))) {
                JSONObject resp = new JSONObject();
                resp.put("requestId", req.getString("requestId"));
                resp.put("type", "TestOutboundResponse");
                resp.put("message", "OK");
                JSONObject data = new JSONObject();
                data.put("ok", true);
                data.put("exitIp", "45.33.12.9");
                data.put("latencyMs", 38);
                resp.put("data", data);
                ws.handleTextMessage(session, new TextMessage(resp.toJSONString()));
            }
            return null;
        }).when(session).sendMessage(any(TextMessage.class));

        when(nodeMapper.selectById(nodeId)).thenReturn(node);
        ws.afterConnectionEstablished(session);
        try {
            R res = landingService.testLanding(nodeId, "socks5://u:p@1.2.3.4:1080");
            assertEquals(0, res.getCode());
            JSONObject data = (JSONObject) res.getData();
            assertEquals(true, data.getBoolean("ok"));
            assertEquals("45.33.12.9", data.getString("exitIp"));
            assertEquals(38, data.getIntValue("latencyMs"));
            assertEquals("socks5", data.getString("type"));
        } finally {
            ws.afterConnectionClosed(session, CloseStatus.NORMAL);
        }
    }
}
