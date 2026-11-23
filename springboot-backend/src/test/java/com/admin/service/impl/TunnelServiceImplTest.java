package com.admin.service.impl;

import com.admin.common.dto.TunnelDto;
import com.admin.common.dto.TunnelUpdateDto;
import com.admin.common.lang.R;
import com.admin.entity.Node;
import com.admin.entity.Tunnel;
import com.admin.mapper.UserTunnelMapper;
import com.admin.service.ForwardService;
import com.admin.service.NodeService;
import com.admin.service.UserTunnelService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * TunnelServiceImpl 核心参数校验与删除保护单元测试
 */
@ExtendWith(MockitoExtension.class)
class TunnelServiceImplTest {

    @Mock
    private UserTunnelMapper userTunnelMapper;

    @Mock
    private NodeService nodeService;

    @Mock
    private ForwardService forwardService;

    @Mock
    private UserTunnelService userTunnelService;

    private TunnelServiceImpl tunnelService;

    @BeforeEach
    void setUp() {
        tunnelService = Mockito.spy(new TunnelServiceImpl());
        ReflectionTestUtils.setField(tunnelService, "userTunnelMapper", userTunnelMapper);
        ReflectionTestUtils.setField(tunnelService, "nodeService", nodeService);
        ReflectionTestUtils.setField(tunnelService, "forwardService", forwardService);
        ReflectionTestUtils.setField(tunnelService, "userTunnelService", userTunnelService);
    }

    private TunnelDto validPortForwardDto() {
        TunnelDto dto = new TunnelDto();
        dto.setName("tunnel-1");
        dto.setType(1);
        dto.setInNodeId(1L);
        dto.setFlow(1);
        dto.setTrafficRatio(BigDecimal.ONE);
        return dto;
    }

    private Node onlineNode(Long id, String ip) {
        Node node = new Node();
        node.setId(id);
        node.setStatus(1);
        node.setIp(ip);
        node.setServerIp(ip);
        node.setPortSta(10000);
        node.setPortEnd(20000);
        return node;
    }

    @Test
    void createTunnel_duplicateName_returnsError() {
        doReturn(new Tunnel()).when(tunnelService).getOne(any(QueryWrapper.class));

        R res = tunnelService.createTunnel(validPortForwardDto());

        assertNotEquals(0, res.getCode());
        assertEquals("隧道名称已存在", res.getMsg());
        verify(tunnelService, never()).save(any());
    }

    @Test
    void createTunnel_tunnelForwardMissingOutNodeOrSameNode_returnsError() {
        doReturn(null).when(tunnelService).getOne(any(QueryWrapper.class));

        TunnelDto missingOut = validPortForwardDto();
        missingOut.setType(2);
        missingOut.setOutNodeId(null);
        R resMissing = tunnelService.createTunnel(missingOut);
        assertNotEquals(0, resMissing.getCode());
        assertEquals("出口节点不能为空", resMissing.getMsg());

        when(nodeService.getById(1L)).thenReturn(onlineNode(1L, "1.1.1.1"));
        TunnelDto sameNode = validPortForwardDto();
        sameNode.setType(2);
        sameNode.setProtocol("tls");
        sameNode.setInNodeId(1L);
        sameNode.setOutNodeId(1L);
        R resSame = tunnelService.createTunnel(sameNode);
        assertNotEquals(0, resSame.getCode());
        assertEquals("隧道转发模式下，入口和出口不能是同一个节点", resSame.getMsg());
    }

    @Test
    void createTunnel_inNodeNotFoundOrOffline_returnsError() {
        doReturn(null).when(tunnelService).getOne(any(QueryWrapper.class));

        when(nodeService.getById(1L)).thenReturn(null);
        R resNotFound = tunnelService.createTunnel(validPortForwardDto());
        assertNotEquals(0, resNotFound.getCode());
        assertEquals("入口节点不存在", resNotFound.getMsg());

        Node offline = onlineNode(1L, "1.1.1.1");
        offline.setStatus(0);
        when(nodeService.getById(1L)).thenReturn(offline);
        R resOffline = tunnelService.createTunnel(validPortForwardDto());
        assertNotEquals(0, resOffline.getCode());
        assertEquals("入口节点当前离线，请确保节点正常运行", resOffline.getMsg());
    }

    @Test
    void createTunnel_tunnelForwardMissingProtocol_returnsError() {
        doReturn(null).when(tunnelService).getOne(any(QueryWrapper.class));
        when(nodeService.getById(1L)).thenReturn(onlineNode(1L, "1.1.1.1"));

        TunnelDto dto = validPortForwardDto();
        dto.setType(2);
        dto.setProtocol("tls");
        dto.setOutNodeId(2L);
        dto.setProtocol(null);

        R res = tunnelService.createTunnel(dto);

        assertNotEquals(0, res.getCode());
        assertEquals("协议类型必选", res.getMsg());
    }

    @Test
    void createTunnel_outNodeNotFoundOrOffline_returnsError() {
        doReturn(null).when(tunnelService).getOne(any(QueryWrapper.class));
        when(nodeService.getById(1L)).thenReturn(onlineNode(1L, "1.1.1.1"));

        TunnelDto dto = validPortForwardDto();
        dto.setType(2);
        dto.setProtocol("tls");
        dto.setOutNodeId(2L);

        when(nodeService.getById(2L)).thenReturn(null);
        R resNotFound = tunnelService.createTunnel(dto);
        assertNotEquals(0, resNotFound.getCode());
        assertEquals("出口节点不存在", resNotFound.getMsg());

        Node offlineOut = onlineNode(2L, "2.2.2.2");
        offlineOut.setStatus(0);
        when(nodeService.getById(2L)).thenReturn(offlineOut);
        R resOffline = tunnelService.createTunnel(dto);
        assertNotEquals(0, resOffline.getCode());
        assertEquals("出口节点当前离线，请确保节点正常运行", resOffline.getMsg());
    }

    @Test
    void updateTunnel_notFoundOrDuplicateName_returnsError() {
        TunnelUpdateDto dto = new TunnelUpdateDto();
        dto.setId(99L);
        dto.setName("dup");

        doReturn(null).when(tunnelService).getById(99L);
        R resNotFound = tunnelService.updateTunnel(dto);
        assertNotEquals(0, resNotFound.getCode());
        assertEquals("隧道不存在", resNotFound.getMsg());

        Tunnel existing = new Tunnel();
        existing.setId(99L);
        doReturn(existing).when(tunnelService).getById(99L);
        doReturn(new Tunnel()).when(tunnelService).getOne(any(QueryWrapper.class));
        R resDup = tunnelService.updateTunnel(dto);
        assertNotEquals(0, resDup.getCode());
        assertEquals("隧道名称已存在", resDup.getMsg());
    }

    @Test
    void deleteTunnel_notFound_returnsError() {
        doReturn(null).when(tunnelService).getById(404L);

        R res = tunnelService.deleteTunnel(404L);

        assertNotEquals(0, res.getCode());
        assertEquals("隧道不存在", res.getMsg());
        verify(tunnelService, never()).removeById(any());
    }

    @Test
    void deleteTunnel_inUseByForwards_returnsError() {
        Tunnel existing = new Tunnel();
        existing.setId(10L);
        doReturn(existing).when(tunnelService).getById(10L);
        when(forwardService.count(any(QueryWrapper.class))).thenReturn(3);

        R res = tunnelService.deleteTunnel(10L);

        assertNotEquals(0, res.getCode());
        assertEquals("该隧道还有 3 个转发在使用，请先删除相关转发", res.getMsg());
        verify(tunnelService, never()).removeById(any());
    }

    @Test
    void deleteTunnel_inUseByUserPermissions_returnsError() {
        Tunnel existing = new Tunnel();
        existing.setId(10L);
        doReturn(existing).when(tunnelService).getById(10L);
        when(forwardService.count(any(QueryWrapper.class))).thenReturn(0);
        when(userTunnelService.count(any(QueryWrapper.class))).thenReturn(2);

        R res = tunnelService.deleteTunnel(10L);

        assertNotEquals(0, res.getCode());
        assertEquals("该隧道还有 2 个用户权限关联，请先取消用户权限分配", res.getMsg());
        verify(tunnelService, never()).removeById(any());
    }

    @Test
    void deleteTunnel_unused_returnsOk() {
        Tunnel existing = new Tunnel();
        existing.setId(10L);
        doReturn(existing).when(tunnelService).getById(10L);
        when(forwardService.count(any(QueryWrapper.class))).thenReturn(0);
        when(userTunnelService.count(any(QueryWrapper.class))).thenReturn(0);
        doReturn(true).when(tunnelService).removeById(10L);

        R res = tunnelService.deleteTunnel(10L);

        assertEquals(0, res.getCode());
        assertEquals("隧道删除成功", res.getData());
        verify(tunnelService).removeById(10L);
    }
}
