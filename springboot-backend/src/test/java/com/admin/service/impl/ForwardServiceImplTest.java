package com.admin.service.impl;

import com.admin.common.dto.ForwardDto;
import com.admin.common.lang.R;
import com.admin.common.task.AccountPauseStore;
import com.admin.common.task.UserNodeNameStore;
import com.admin.entity.Forward;
import com.admin.entity.Tunnel;
import com.admin.mapper.InboundMapper;
import com.admin.service.NodeService;
import com.admin.service.SpeedLimitService;
import com.admin.service.TunnelService;
import com.admin.service.UserService;
import com.admin.service.UserTunnelService;
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
 * ForwardServiceImpl 参数校验、状态守卫与异常分支单元测试
 */
@ExtendWith(MockitoExtension.class)
class ForwardServiceImplTest {

    @Mock
    private TunnelService tunnelService;

    @Mock
    private UserTunnelService userTunnelService;

    @Mock
    private UserService userService;

    @Mock
    private UserNodeNameStore nodeNameStore;

    @Mock
    private AccountPauseStore accountPauseStore;

    @Mock
    private NodeService nodeService;

    @Mock
    private SpeedLimitService speedLimitService;

    @Mock
    private InboundMapper inboundMapper;

    private ForwardServiceImpl forwardService;

    @BeforeEach
    void setUp() {
        forwardService = Mockito.spy(new ForwardServiceImpl());
        ReflectionTestUtils.setField(forwardService, "tunnelService", tunnelService);
        ReflectionTestUtils.setField(forwardService, "userTunnelService", userTunnelService);
        ReflectionTestUtils.setField(forwardService, "userService", userService);
        ReflectionTestUtils.setField(forwardService, "nodeNameStore", nodeNameStore);
        ReflectionTestUtils.setField(forwardService, "accountPauseStore", accountPauseStore);
        ReflectionTestUtils.setField(forwardService, "nodeService", nodeService);
        ReflectionTestUtils.setField(forwardService, "speedLimitService", speedLimitService);
        ReflectionTestUtils.setField(forwardService, "inboundMapper", inboundMapper);
    }

    private ForwardDto sampleForwardDto(Integer tunnelId) {
        ForwardDto dto = new ForwardDto();
        dto.setTunnelId(tunnelId);
        dto.setName("fwd-1");
        dto.setRemoteAddr("1.2.3.4:8080");
        return dto;
    }

    @Test
    void createForwardForUser_tunnelNotFoundOrDisabled_returnsError() {
        when(tunnelService.getById(99)).thenReturn(null);
        R notFound = forwardService.createForwardForUser(sampleForwardDto(99), 10, "user10");
        assertNotEquals(0, notFound.getCode());
        assertEquals("隧道不存在", notFound.getMsg());

        Tunnel disabled = new Tunnel();
        disabled.setId(2L);
        disabled.setStatus(0);
        when(tunnelService.getById(2)).thenReturn(disabled);
        R disabledRes = forwardService.createForwardForUser(sampleForwardDto(2), 10, "user10");
        assertNotEquals(0, disabledRes.getCode());
        assertEquals("隧道已禁用，无法创建转发", disabledRes.getMsg());

        verify(forwardService, never()).save(any());
    }

    @Test
    void assignForwardToUser_forwardNotFound_returnsError() {
        doReturn(null).when(forwardService).getById(77L);

        R res = forwardService.assignForwardToUser(77L, 10, 1, 0L);

        assertNotEquals(0, res.getCode());
        assertEquals("转发不存在", res.getMsg());
    }

    @Test
    void setForwardClientLink_forwardNotFound_returnsError() {
        doReturn(null).when(forwardService).getById(88L);

        R res = forwardService.setForwardClientLink(88L, "vless://test");

        assertNotEquals(0, res.getCode());
        assertEquals("转发不存在", res.getMsg());
    }

    @Test
    void resumeForward_expiredForward_returnsError() {
        Forward expired = new Forward();
        expired.setId(30L);
        expired.setExpTime(System.currentTimeMillis() - 60_000L);
        doReturn(expired).when(forwardService).getById(30L);

        R res = forwardService.resumeForward(30L);

        assertNotEquals(0, res.getCode());
        assertEquals("该转发已到期,无法恢复", res.getMsg());
    }

    @Test
    void resumeForwardAsSystem_expiredForward_returnsError() {
        Forward expired = new Forward();
        expired.setId(31L);
        expired.setExpTime(System.currentTimeMillis() - 60_000L);
        doReturn(expired).when(forwardService).getById(31L);

        R res = forwardService.resumeForwardAsSystem(31L);

        assertNotEquals(0, res.getCode());
        assertEquals("该转发已到期,无法恢复", res.getMsg());
    }
}
