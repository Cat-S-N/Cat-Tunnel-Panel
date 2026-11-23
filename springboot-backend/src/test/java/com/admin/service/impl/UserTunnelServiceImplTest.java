package com.admin.service.impl;

import com.admin.common.dto.UserTunnelDto;
import com.admin.common.dto.UserTunnelUpdateDto;
import com.admin.common.lang.R;
import com.admin.common.task.UserNodeNameStore;
import com.admin.entity.UserTunnel;
import com.admin.service.ForwardService;
import com.admin.service.NodeService;
import com.admin.service.TunnelService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

/**
 * UserTunnelServiceImpl 隧道权限分配、更新与删除边界单元测试
 */
@ExtendWith(MockitoExtension.class)
class UserTunnelServiceImplTest {

    @Mock
    private ForwardService forwardService;

    @Mock
    private TunnelService tunnelService;

    @Mock
    private NodeService nodeService;

    @Mock
    private UserNodeNameStore nodeNameStore;

    private UserTunnelServiceImpl userTunnelService;

    @BeforeEach
    void setUp() {
        userTunnelService = Mockito.spy(new UserTunnelServiceImpl());
        ReflectionTestUtils.setField(userTunnelService, "forwardService", forwardService);
        ReflectionTestUtils.setField(userTunnelService, "tunnelService", tunnelService);
        ReflectionTestUtils.setField(userTunnelService, "nodeService", nodeService);
        ReflectionTestUtils.setField(userTunnelService, "nodeNameStore", nodeNameStore);
    }

    private UserTunnelDto validAssignDto(Integer userId, Integer tunnelId) {
        UserTunnelDto dto = new UserTunnelDto();
        dto.setUserId(userId);
        dto.setTunnelId(tunnelId);
        dto.setFlow(100L);
        dto.setNum(5);
        dto.setExpTime(9999999999999L);
        dto.setFlowResetTime(1L);
        dto.setSpeedId(3);
        return dto;
    }

    private UserTunnel sampleUserTunnel(Integer id, Integer userId, Integer tunnelId, Integer speedId) {
        UserTunnel ut = new UserTunnel();
        ut.setId(id);
        ut.setUserId(userId);
        ut.setTunnelId(tunnelId);
        ut.setFlow(100L);
        ut.setNum(5);
        ut.setExpTime(8888888888888L);
        ut.setFlowResetTime(1L);
        ut.setStatus(1);
        ut.setSpeedId(speedId);
        return ut;
    }

    @Test
    void assignUserTunnel_alreadyExists_returnsError() {
        doReturn(sampleUserTunnel(1, 10, 20, null)).when(userTunnelService).getOne(any(QueryWrapper.class));

        R res = userTunnelService.assignUserTunnel(validAssignDto(10, 20));

        assertNotEquals(0, res.getCode());
        assertEquals("该用户已拥有此隧道权限", res.getMsg());
        verify(userTunnelService, never()).save(any());
    }

    @Test
    void assignUserTunnel_success_setsDefaultStatusAndSaves() {
        doReturn(null).when(userTunnelService).getOne(any(QueryWrapper.class));
        doReturn(true).when(userTunnelService).save(any(UserTunnel.class));

        R res = userTunnelService.assignUserTunnel(validAssignDto(10, 20));

        assertEquals(0, res.getCode());
        verify(userTunnelService).save(argThat(ut ->
                ut.getUserId().equals(10)
                        && ut.getTunnelId().equals(20)
                        && ut.getStatus() == 1
                        && ut.getSpeedId().equals(3)
        ));
    }

    @Test
    void assignUserTunnel_saveFails_returnsError() {
        doReturn(null).when(userTunnelService).getOne(any(QueryWrapper.class));
        doReturn(false).when(userTunnelService).save(any(UserTunnel.class));

        R res = userTunnelService.assignUserTunnel(validAssignDto(10, 20));

        assertNotEquals(0, res.getCode());
        assertEquals("用户隧道权限分配失败", res.getMsg());
    }

    @Test
    void removeUserTunnel_notFound_returnsError() {
        doReturn(null).when(userTunnelService).getById(99);

        R res = userTunnelService.removeUserTunnel(99);

        assertNotEquals(0, res.getCode());
        assertEquals("未找到对应的用户隧道权限记录", res.getMsg());
        verify(userTunnelService, never()).removeById(any());
    }

    @Test
    void removeUserTunnel_success_removesPermission() {
        doReturn(sampleUserTunnel(1, 10, 20, null)).when(userTunnelService).getById(1);
        when(forwardService.list(any(QueryWrapper.class))).thenReturn(Collections.emptyList());
        doReturn(true).when(userTunnelService).removeById(1);

        R res = userTunnelService.removeUserTunnel(1);

        assertEquals(0, res.getCode());
        verify(userTunnelService).removeById(1);
    }

    @Test
    void removeUserTunnel_forwardCleanupThrows_stillRemovesPermission() {
        doReturn(sampleUserTunnel(1, 10, 20, null)).when(userTunnelService).getById(1);
        when(forwardService.list(any(QueryWrapper.class))).thenThrow(new RuntimeException("db error"));
        doReturn(true).when(userTunnelService).removeById(1);

        R res = userTunnelService.removeUserTunnel(1);

        assertEquals(0, res.getCode());
        verify(userTunnelService).removeById(1);
    }

    @Test
    void updateUserTunnel_notFound_returnsError() {
        UserTunnelUpdateDto dto = new UserTunnelUpdateDto();
        dto.setId(99);
        dto.setFlow(200L);
        dto.setNum(10);

        doReturn(null).when(userTunnelService).getById(99);

        R res = userTunnelService.updateUserTunnel(dto);

        assertNotEquals(0, res.getCode());
        assertEquals("用户隧道权限不存在", res.getMsg());
        verify(userTunnelService, never()).updateById(any());
    }

    @Test
    void updateUserTunnel_speedUnchanged_skipsForwardSyncAndPreservesNullOptionals() {
        UserTunnel existing = sampleUserTunnel(1, 10, 20, 5);
        doReturn(existing).when(userTunnelService).getById(1);
        doReturn(true).when(userTunnelService).updateById(any(UserTunnel.class));

        UserTunnelUpdateDto dto = new UserTunnelUpdateDto();
        dto.setId(1);
        dto.setFlow(300L);
        dto.setNum(8);
        dto.setSpeedId(5);

        R res = userTunnelService.updateUserTunnel(dto);

        assertEquals(0, res.getCode());
        verify(userTunnelService).updateById(argThat(ut ->
                ut.getFlow().equals(300L)
                        && ut.getNum().equals(8)
                        && ut.getExpTime().equals(8888888888888L)
                        && ut.getStatus().equals(1)
        ));
        verifyNoInteractions(forwardService);
    }

    @Test
    void updateUserTunnel_speedChanged_triggersForwardSpeedSync() {
        UserTunnel existing = sampleUserTunnel(1, 10, 20, 5);
        doReturn(existing).when(userTunnelService).getById(1);
        doReturn(true).when(userTunnelService).updateById(any(UserTunnel.class));
        when(forwardService.list(any(QueryWrapper.class))).thenReturn(Collections.emptyList());

        UserTunnelUpdateDto dto = new UserTunnelUpdateDto();
        dto.setId(1);
        dto.setFlow(300L);
        dto.setNum(8);
        dto.setSpeedId(null);

        R res = userTunnelService.updateUserTunnel(dto);

        assertEquals(0, res.getCode());
        verify(forwardService).list(any(QueryWrapper.class));
    }

    @Test
    void updateUserTunnel_updateFails_returnsError() {
        UserTunnel existing = sampleUserTunnel(1, 10, 20, 5);
        doReturn(existing).when(userTunnelService).getById(1);
        doReturn(false).when(userTunnelService).updateById(any(UserTunnel.class));

        UserTunnelUpdateDto dto = new UserTunnelUpdateDto();
        dto.setId(1);
        dto.setFlow(300L);
        dto.setNum(8);
        dto.setSpeedId(5);

        R res = userTunnelService.updateUserTunnel(dto);

        assertNotEquals(0, res.getCode());
        assertEquals("用户隧道权限更新失败", res.getMsg());
    }
}
