package com.admin.common.task;

import com.admin.common.dto.ConfigItem;
import com.admin.common.dto.GostConfigDto;
import com.admin.entity.Forward;
import com.admin.entity.Node;
import com.admin.entity.SpeedLimit;
import com.admin.entity.User;
import com.admin.service.ForwardService;
import com.admin.service.NodeService;
import com.admin.service.SpeedLimitService;
import com.admin.service.TunnelService;
import com.admin.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CheckGostConfigAsyncTest {

    private CheckGostConfigAsync task;
    private NodeService nodeService;
    private ForwardService forwardService;
    private SpeedLimitService speedLimitService;
    private TunnelService tunnelService;
    private UserService userService;

    @BeforeEach
    void setUp() {
        task = new CheckGostConfigAsync();
        nodeService = mock(NodeService.class);
        forwardService = mock(ForwardService.class);
        speedLimitService = mock(SpeedLimitService.class);
        tunnelService = mock(TunnelService.class);
        userService = mock(UserService.class);

        ReflectionTestUtils.setField(task, "nodeService", nodeService);
        ReflectionTestUtils.setField(task, "forwardService", forwardService);
        ReflectionTestUtils.setField(task, "speedLimitService", speedLimitService);
        ReflectionTestUtils.setField(task, "tunnelService", tunnelService);
        ReflectionTestUtils.setField(task, "userService", userService);
    }

    @Test
    void cleanNodeConfigsSkipsWhenNodeMissing() {
        when(nodeService.getById("99")).thenReturn(null);

        task.cleanNodeConfigs("99", new GostConfigDto());

        verifyNoInteractions(forwardService, speedLimitService, userService);
    }

    @Test
    void cleanNodeConfigsHandlesEmptyConfigSafely() {
        Node node = new Node();
        node.setId(1L);
        when(nodeService.getById("1")).thenReturn(node);

        task.cleanNodeConfigs("1", new GostConfigDto());

        verifyNoInteractions(forwardService, speedLimitService, userService);
    }

    @Test
    void cleanOrphanedServicesSkipsWebApiAndKeepsExistingForward() {
        Node node = new Node();
        node.setId(5L);
        when(nodeService.getById("5")).thenReturn(node);

        Forward activeForward = new Forward();
        activeForward.setId(12L);
        when(forwardService.getById("12")).thenReturn(activeForward);

        GostConfigDto dto = new GostConfigDto();
        dto.setServices(Arrays.asList(
                configItem("web_api"),
                configItem("12_1_0_tcp"),
                configItem("12_1_0_tls")
        ));

        task.cleanNodeConfigs("5", dto);

        verify(forwardService, never()).getById("web_api");
        verify(forwardService, org.mockito.Mockito.times(2)).getById("12");
    }

    @Test
    void cleanOrphanedChainsKeepsExistingForwardChain() {
        Node node = new Node();
        node.setId(6L);
        when(nodeService.getById("6")).thenReturn(node);

        Forward active = new Forward();
        active.setId(21L);
        when(forwardService.getById("21")).thenReturn(active);

        GostConfigDto dto = new GostConfigDto();
        dto.setChains(Collections.singletonList(configItem("21_2_3_chains")));

        task.cleanNodeConfigs("6", dto);

        verify(forwardService).getById("21");
    }

    @Test
    void cleanOrphanedLimitersRespectsPerUserLimiterBaseAndActiveSpeedLimit() {
        Node node = new Node();
        node.setId(8L);
        when(nodeService.getById("8")).thenReturn(node);

        User activeUser = new User();
        activeUser.setId(42L);
        when(userService.getById(42L)).thenReturn(activeUser);

        SpeedLimit activeLimit = new SpeedLimit();
        activeLimit.setId(100L);
        when(speedLimitService.getById("100")).thenReturn(activeLimit);

        long keepPerUser = CheckGostConfigAsync.PER_USER_LIMITER_BASE + 42L;

        GostConfigDto dto = new GostConfigDto();
        dto.setLimiters(Arrays.asList(
                configItem(String.valueOf(keepPerUser)),
                configItem("100")
        ));

        task.cleanNodeConfigs("8", dto);

        verify(userService).getById(42L);
        verify(speedLimitService, never()).getById(String.valueOf(keepPerUser));
        verify(speedLimitService).getById("100");
    }

    private static ConfigItem configItem(String name) {
        ConfigItem item = new ConfigItem();
        item.setName(name);
        return item;
    }
}
