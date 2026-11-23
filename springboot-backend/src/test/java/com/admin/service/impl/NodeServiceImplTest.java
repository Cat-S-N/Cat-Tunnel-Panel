package com.admin.service.impl;

import com.admin.common.dto.NodeDto;
import com.admin.common.dto.NodeUpdateDto;
import com.admin.common.lang.R;
import com.admin.entity.Node;
import com.admin.mapper.TunnelMapper;
import com.admin.service.TunnelService;
import com.admin.service.ViteConfigService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * NodeServiceImpl 核心参数校验与删除保护单元测试
 */
@ExtendWith(MockitoExtension.class)
class NodeServiceImplTest {

    @Mock
    private TunnelMapper tunnelMapper;

    @Mock
    private TunnelService tunnelService;

    @Mock
    private ViteConfigService viteConfigService;

    private NodeServiceImpl nodeService;

    @BeforeEach
    void setUp() {
        nodeService = Mockito.spy(new NodeServiceImpl());
        ReflectionTestUtils.setField(nodeService, "tunnelMapper", tunnelMapper);
        ReflectionTestUtils.setField(nodeService, "tunnelService", tunnelService);
        ReflectionTestUtils.setField(nodeService, "viteConfigService", viteConfigService);
    }

    private NodeDto validNodeDto() {
        NodeDto dto = new NodeDto();
        dto.setName("node-1");
        dto.setIp(" 1.2.3.4 ");
        dto.setServerIp(" 5.6.7.8 ");
        dto.setDomain(" example.com ");
        dto.setPortSta(10000);
        dto.setPortEnd(20000);
        return dto;
    }

    @Test
    void createNode_valid_trimsAddressesAndReturnsOk() {
        doReturn(true).when(nodeService).save(any(Node.class));

        R res = nodeService.createNode(validNodeDto());

        assertEquals(0, res.getCode());
        assertEquals("节点创建成功", res.getData());
        verify(nodeService).save(argThat(node ->
                "1.2.3.4".equals(node.getIp())
                        && "5.6.7.8".equals(node.getServerIp())
                        && "example.com".equals(node.getDomain())
                        && node.getSecret() != null
                        && Integer.valueOf(0).equals(node.getStatus())
        ));
    }

    @Test
    void createNode_portStaNull_throwsException() {
        NodeDto dto = validNodeDto();
        dto.setPortSta(null);

        RuntimeException ex = assertThrows(RuntimeException.class, () -> nodeService.createNode(dto));
        assertEquals("起始端口不能为空", ex.getMessage());
        verify(nodeService, never()).save(any());
    }

    @Test
    void createNode_portEndNull_throwsException() {
        NodeDto dto = validNodeDto();
        dto.setPortEnd(null);

        RuntimeException ex = assertThrows(RuntimeException.class, () -> nodeService.createNode(dto));
        assertEquals("结束端口不能为空", ex.getMessage());
        verify(nodeService, never()).save(any());
    }

    @Test
    void createNode_portOutOfRange_throwsException() {
        NodeDto dtoLow = validNodeDto();
        dtoLow.setPortSta(0);
        RuntimeException exLow = assertThrows(RuntimeException.class, () -> nodeService.createNode(dtoLow));
        assertEquals("端口必须在1-65535范围内", exLow.getMessage());

        NodeDto dtoHigh = validNodeDto();
        dtoHigh.setPortEnd(65536);
        RuntimeException exHigh = assertThrows(RuntimeException.class, () -> nodeService.createNode(dtoHigh));
        assertEquals("端口必须在1-65535范围内", exHigh.getMessage());
    }

    @Test
    void createNode_portEndLessThanPortSta_throwsException() {
        NodeDto dto = validNodeDto();
        dto.setPortSta(20000);
        dto.setPortEnd(10000);

        RuntimeException ex = assertThrows(RuntimeException.class, () -> nodeService.createNode(dto));
        assertEquals("结束端口不能小于起始端口", ex.getMessage());
        verify(nodeService, never()).save(any());
    }

    @Test
    void updateNode_notFound_returnsError() {
        NodeUpdateDto dto = new NodeUpdateDto();
        dto.setId(999L);
        doReturn(null).when(nodeService).getById(999L);

        R res = nodeService.updateNode(dto);

        assertNotEquals(0, res.getCode());
        assertEquals("节点不存在", res.getMsg());
    }

    @Test
    void updateNode_invalidIpv6AndIpv6Sub_returnsError() {
        Node existing = new Node();
        existing.setId(1L);
        existing.setStatus(0);
        doReturn(existing).when(nodeService).getById(1L);

        NodeUpdateDto badV6 = new NodeUpdateDto();
        badV6.setId(1L);
        badV6.setServerIpv6("192.168.1.1");
        R res1 = nodeService.updateNode(badV6);
        assertNotEquals(0, res1.getCode());
        assertTrue(res1.getMsg().contains("IPv6"));

        NodeUpdateDto badSub = new NodeUpdateDto();
        badSub.setId(1L);
        badSub.setIpv6Sub(3);
        R res2 = nodeService.updateNode(badSub);
        assertNotEquals(0, res2.getCode());
        assertEquals("ipv6_sub 只能是 0、1 或 2", res2.getMsg());
    }

    @Test
    void deleteNode_notFound_returnsError() {
        doReturn(null).when(nodeService).getById(404L);

        R res = nodeService.deleteNode(404L);

        assertNotEquals(0, res.getCode());
        assertEquals("节点不存在", res.getMsg());
        verify(nodeService, never()).removeById(any());
    }

    @Test
    void deleteNode_inUseAsInNode_returnsError() {
        Node existing = new Node();
        existing.setId(1L);
        doReturn(existing).when(nodeService).getById(1L);
        when(tunnelMapper.selectCount(any(QueryWrapper.class))).thenReturn(2);

        R res = nodeService.deleteNode(1L);

        assertNotEquals(0, res.getCode());
        assertEquals("该节点还有 2 个隧道作为入口节点在使用，请先删除相关隧道", res.getMsg());
        verify(nodeService, never()).removeById(any());
    }

    @Test
    void deleteNode_inUseAsOutNode_returnsError() {
        Node existing = new Node();
        existing.setId(1L);
        doReturn(existing).when(nodeService).getById(1L);
        when(tunnelMapper.selectCount(any(QueryWrapper.class))).thenReturn(0, 3);

        R res = nodeService.deleteNode(1L);

        assertNotEquals(0, res.getCode());
        assertEquals("该节点还有 3 个隧道作为出口节点在使用，请先删除相关隧道", res.getMsg());
        verify(nodeService, never()).removeById(any());
    }

    @Test
    void deleteNode_unused_returnsOk() {
        Node existing = new Node();
        existing.setId(1L);
        doReturn(existing).when(nodeService).getById(1L);
        when(tunnelMapper.selectCount(any(QueryWrapper.class))).thenReturn(0, 0);
        doReturn(true).when(nodeService).removeById(1L);

        R res = nodeService.deleteNode(1L);

        assertEquals(0, res.getCode());
        assertEquals("节点删除成功", res.getData());
        verify(nodeService).removeById(1L);
    }

    @Test
    void getNodeById_notFound_throwsException() {
        doReturn(null).when(nodeService).getById(99L);

        RuntimeException ex = assertThrows(RuntimeException.class, () -> nodeService.getNodeById(99L));
        assertEquals("节点不存在", ex.getMessage());
    }
}
