package com.admin.service.impl;

import com.admin.common.dto.SpeedLimitDto;
import com.admin.common.dto.SpeedLimitUpdateDto;
import com.admin.common.lang.R;
import com.admin.entity.SpeedLimit;
import com.admin.entity.Tunnel;
import com.admin.service.NodeService;
import com.admin.service.SpeedLimitService;
import com.admin.service.TunnelService;
import com.admin.service.UserTunnelService;
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
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

/**
 * SpeedLimitServiceImpl 核心参数校验、更新保护与删除保护单元测试
 */
@ExtendWith(MockitoExtension.class)
class SpeedLimitServiceImplTest {

    @Mock
    private TunnelService tunnelService;

    @Mock
    private NodeService nodeService;

    @Mock
    private UserTunnelService userTunnelService;

    @Mock
    private SpeedLimitService selfSpeedLimitService;

    private SpeedLimitServiceImpl speedLimitService;

    @BeforeEach
    void setUp() {
        speedLimitService = Mockito.spy(new SpeedLimitServiceImpl());
        ReflectionTestUtils.setField(speedLimitService, "tunnelService", tunnelService);
        ReflectionTestUtils.setField(speedLimitService, "nodeService", nodeService);
        ReflectionTestUtils.setField(speedLimitService, "userTunnelService", userTunnelService);
        ReflectionTestUtils.setField(speedLimitService, "speedLimitService", selfSpeedLimitService);
    }

    private SpeedLimitDto validDto(Long tunnelId, String tunnelName) {
        SpeedLimitDto dto = new SpeedLimitDto();
        dto.setName("limit-10m");
        dto.setSpeed(10);
        dto.setTunnelId(tunnelId);
        dto.setTunnelName(tunnelName);
        return dto;
    }

    private Tunnel sampleTunnel(Long id, String name, Long inNodeId) {
        Tunnel t = new Tunnel();
        t.setId(id);
        t.setName(name);
        t.setInNodeId(inNodeId);
        return t;
    }

    @Test
    void createSpeedLimit_withoutTunnel_savesWithDefaultsAndSkipsGost() {
        doReturn(true).when(speedLimitService).save(any(SpeedLimit.class));

        R res = speedLimitService.createSpeedLimit(validDto(null, null));

        assertEquals(0, res.getCode());
        verify(speedLimitService).save(argThat(sl ->
                sl.getStatus() == 1 && sl.getMode() == 0 && sl.getTotal() == 0
        ));
        verifyNoInteractions(tunnelService, nodeService);
    }

    @Test
    void createSpeedLimit_tunnelNotFoundOrNameMismatch_returnsError() {
        when(tunnelService.getById(99L)).thenReturn(null);
        R notFound = speedLimitService.createSpeedLimit(validDto(99L, "t1"));
        assertNotEquals(0, notFound.getCode());
        assertEquals("指定的隧道不存在", notFound.getMsg());

        when(tunnelService.getById(1L)).thenReturn(sampleTunnel(1L, "real-name", 10L));
        R mismatch = speedLimitService.createSpeedLimit(validDto(1L, "wrong-name"));
        assertNotEquals(0, mismatch.getCode());
        assertEquals("隧道名称与隧道ID不匹配", mismatch.getMsg());

        verify(speedLimitService, never()).save(any());
    }

    @Test
    void createSpeedLimit_saveFails_returnsError() {
        doReturn(false).when(speedLimitService).save(any(SpeedLimit.class));

        R res = speedLimitService.createSpeedLimit(validDto(null, null));

        assertNotEquals(0, res.getCode());
        assertEquals("限速规则创建失败", res.getMsg());
    }

    @Test
    void updateSpeedLimit_notFound_returnsError() {
        doReturn(null).when(speedLimitService).getById(88L);
        SpeedLimitUpdateDto dto = new SpeedLimitUpdateDto();
        dto.setId(88L);

        R res = speedLimitService.updateSpeedLimit(dto);

        assertNotEquals(0, res.getCode());
        assertEquals("限速规则不存在", res.getMsg());
    }

    @Test
    void updateSpeedLimit_withoutTunnel_updatesDbDirectly() {
        SpeedLimit existing = new SpeedLimit();
        existing.setId(5L);
        existing.setSpeed(10);
        doReturn(existing).when(speedLimitService).getById(5L);
        doReturn(true).when(speedLimitService).updateById(any(SpeedLimit.class));

        SpeedLimitUpdateDto dto = new SpeedLimitUpdateDto();
        dto.setId(5L);
        dto.setName("limit-20m");
        dto.setSpeed(20);

        R res = speedLimitService.updateSpeedLimit(dto);

        assertEquals(0, res.getCode());
        assertEquals("限速规则更新成功", res.getData());
        verify(speedLimitService).updateById(any(SpeedLimit.class));
        verifyNoInteractions(tunnelService, nodeService);
    }

    @Test
    void updateSpeedLimit_tunnelMismatch_returnsErrorWithoutUpdating() {
        SpeedLimit existing = new SpeedLimit();
        existing.setId(5L);
        doReturn(existing).when(speedLimitService).getById(5L);
        when(tunnelService.getById(1L)).thenReturn(sampleTunnel(1L, "real-name", 10L));

        SpeedLimitUpdateDto dto = new SpeedLimitUpdateDto();
        dto.setId(5L);
        dto.setTunnelId(1L);
        dto.setTunnelName("wrong-name");

        R res = speedLimitService.updateSpeedLimit(dto);

        assertNotEquals(0, res.getCode());
        assertEquals("隧道名称与隧道ID不匹配", res.getMsg());
        verify(speedLimitService, never()).updateById(any());
    }

    @Test
    void deleteSpeedLimit_notFoundOrInUse_returnsError() {
        doReturn(null).when(speedLimitService).getById(1L);
        R notFound = speedLimitService.deleteSpeedLimit(1L);
        assertNotEquals(0, notFound.getCode());
        assertEquals("限速规则不存在", notFound.getMsg());

        SpeedLimit sl = new SpeedLimit();
        sl.setId(2L);
        doReturn(sl).when(speedLimitService).getById(2L);
        when(userTunnelService.count(any(QueryWrapper.class))).thenReturn(3);

        R inUse = speedLimitService.deleteSpeedLimit(2L);
        assertNotEquals(0, inUse.getCode());
        assertEquals("该限速规则还有用户在使用 请先取消分配", inUse.getMsg());
        verify(speedLimitService, never()).removeById(2L);
    }

    @Test
    void deleteSpeedLimit_tunnelMissing_removesDbRecordDirectly() {
        SpeedLimit sl = new SpeedLimit();
        sl.setId(2L);
        sl.setTunnelId(9L);
        doReturn(sl).when(speedLimitService).getById(2L);
        when(userTunnelService.count(any(QueryWrapper.class))).thenReturn(0);
        when(tunnelService.getById(9L)).thenReturn(null);
        doReturn(true).when(speedLimitService).removeById(2L);

        R res = speedLimitService.deleteSpeedLimit(2L);

        assertEquals(0, res.getCode());
        verify(speedLimitService).removeById(2L);
    }

    @Test
    void ensureLimiterOnNode_nullArgsAndNotFound() {
        assertEquals(0, speedLimitService.ensureLimiterOnNode(null, 1L).getCode());
        assertEquals(0, speedLimitService.ensureLimiterOnNode(1, null).getCode());

        doReturn(null).when(speedLimitService).getById(99L);
        R missing = speedLimitService.ensureLimiterOnNode(99, 1L);
        assertNotEquals(0, missing.getCode());
        assertEquals("限速规则不存在", missing.getMsg());
    }

    @Test
    void pushUserLimiter_nullArgsAndNotFound() {
        assertEquals(0, speedLimitService.pushUserLimiter(null, 900000001L, 10L).getCode());
        assertEquals(0, speedLimitService.pushUserLimiter(1, null, 10L).getCode());
        assertEquals(0, speedLimitService.pushUserLimiter(1, 900000001L, null).getCode());

        doReturn(null).when(speedLimitService).getById(77L);
        R missing = speedLimitService.pushUserLimiter(77, 900000001L, 10L);
        assertNotEquals(0, missing.getCode());
        assertEquals("限速规则不存在", missing.getMsg());
    }
}
