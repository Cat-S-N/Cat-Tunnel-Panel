package com.admin.controller;

import com.admin.common.dto.GostConfigDto;
import com.admin.common.task.AccountPauseStore;
import com.admin.common.task.CheckGostConfigAsync;
import com.admin.entity.Forward;
import com.admin.entity.Node;
import com.admin.entity.Tunnel;
import com.admin.mapper.InboundLineMapper;
import com.admin.mapper.InboundMapper;
import com.admin.mapper.InboundUserMapper;
import com.admin.service.ForwardService;
import com.admin.service.NodeService;
import com.admin.service.TunnelService;
import com.admin.service.UserService;
import com.admin.service.UserTunnelService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class FlowControllerTest {

    private FlowController controller;
    private NodeService nodeService;
    private ForwardService forwardService;
    private UserService userService;
    private TunnelService tunnelService;
    private UserTunnelService userTunnelService;
    private CheckGostConfigAsync checkGostConfigAsync;
    private InboundMapper inboundMapper;
    private InboundUserMapper inboundUserMapper;
    private InboundLineMapper inboundLineMapper;
    private AccountPauseStore accountPauseStore;

    @BeforeEach
    void setUp() {
        controller = new FlowController();
        nodeService = mock(NodeService.class);
        forwardService = mock(ForwardService.class);
        userService = mock(UserService.class);
        tunnelService = mock(TunnelService.class);
        userTunnelService = mock(UserTunnelService.class);
        checkGostConfigAsync = mock(CheckGostConfigAsync.class);
        inboundMapper = mock(InboundMapper.class);
        inboundUserMapper = mock(InboundUserMapper.class);
        inboundLineMapper = mock(InboundLineMapper.class);
        accountPauseStore = mock(AccountPauseStore.class);

        ReflectionTestUtils.setField(controller, "nodeService", nodeService);
        ReflectionTestUtils.setField(controller, "forwardService", forwardService);
        ReflectionTestUtils.setField(controller, "userService", userService);
        ReflectionTestUtils.setField(controller, "tunnelService", tunnelService);
        ReflectionTestUtils.setField(controller, "userTunnelService", userTunnelService);
        ReflectionTestUtils.setField(controller, "checkGostConfigAsync", checkGostConfigAsync);
        ReflectionTestUtils.setField(controller, "inboundMapper", inboundMapper);
        ReflectionTestUtils.setField(controller, "inboundUserMapper", inboundUserMapper);
        ReflectionTestUtils.setField(controller, "inboundLineMapper", inboundLineMapper);
        ReflectionTestUtils.setField(controller, "accountPauseStore", accountPauseStore);
    }

    @Test
    void testEndpointReturnsTestString() {
        assertEquals("test", controller.test());
    }

    @Test
    void uploadFlowDataRejectsUnknownSecret() {
        when(nodeService.count(any())).thenReturn(0);

        String result = controller.uploadFlowData("{\"n\":\"1_2_0\",\"u\":10,\"d\":20}", "bad-secret");

        assertEquals("unknown node", result);
        verifyNoInteractions(forwardService, userService, userTunnelService);
    }

    @Test
    void uploadFlowDataIgnoresWebApiHeartbeat() {
        when(nodeService.count(any())).thenReturn(1);

        String result = controller.uploadFlowData("{\"n\":\"web_api\",\"u\":100,\"d\":200}", "valid-secret");

        assertEquals("ok", result);
        verifyNoInteractions(forwardService, userService, userTunnelService);
    }

    @Test
    void uploadFlowDataThrowsOnBlankPayload() {
        when(nodeService.count(any())).thenReturn(1);

        assertThrows(IllegalArgumentException.class,
                () -> controller.uploadFlowData("   ", "valid-secret"));
    }

    @Test
    void uploadFlowDataUpdatesForwardAndUserWithoutUserTunnelWhenZero() {
        when(nodeService.count(any())).thenReturn(1);
        Forward forward = new Forward();
        forward.setId(10L);
        forward.setUserId(20);
        forward.setTunnelId(30);
        when(forwardService.getById("10")).thenReturn(forward);

        Tunnel tunnel = new Tunnel();
        tunnel.setId(30L);
        tunnel.setFlow(1);
        tunnel.setTrafficRatio(BigDecimal.ONE);
        when(tunnelService.getById(30)).thenReturn(tunnel);

        String result = controller.uploadFlowData("{\"n\":\"10_20_0\",\"u\":100,\"d\":200}", "valid-secret");

        assertEquals("ok", result);
        verify(forwardService).update(eq(null), any());
        verify(userService).update(eq(null), any());
        verify(userTunnelService, never()).update(any(), any());
    }

    @Test
    void configIgnoresUnknownNodeAndReturnsOk() {
        when(nodeService.getOne(any())).thenReturn(null);

        String result = controller.config("{\"services\":[]}", "unknown-secret");

        assertEquals("ok", result);
        verifyNoInteractions(checkGostConfigAsync);
    }

    @Test
    void configDispatchesCleanNodeConfigsForValidNode() {
        Node node = new Node();
        node.setId(7L);
        when(nodeService.getOne(any())).thenReturn(node);

        String result = controller.config("{\"services\":[],\"chains\":[],\"limiters\":[]}", "valid-secret");

        assertEquals("ok", result);
        verify(checkGostConfigAsync).cleanNodeConfigs(eq("7"), any(GostConfigDto.class));
    }
}
