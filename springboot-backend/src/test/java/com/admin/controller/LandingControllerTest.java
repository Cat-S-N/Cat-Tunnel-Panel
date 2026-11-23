package com.admin.controller;

import com.admin.common.dto.LandingDto;
import com.admin.common.lang.R;
import com.admin.common.utils.JwtUtil;
import com.admin.entity.User;
import com.admin.mapper.UserMapper;
import com.admin.service.InboundService;
import com.admin.service.LandingService;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * LandingController 落地创建、更新联动重推、删除及测速节点权限单元测试
 */
@ExtendWith(MockitoExtension.class)
class LandingControllerTest {

    @Mock
    private LandingService landingService;

    @Mock
    private InboundService inboundService;

    @Mock
    private UserMapper userMapper;

    private LandingController controller;

    @BeforeEach
    void setUp() {
        JwtUtil jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "secretKey", "landing-controller-test-secret");
        jwtUtil.init();

        controller = new LandingController();
        ReflectionTestUtils.setField(controller, "landingService", landingService);
        ReflectionTestUtils.setField(controller, "inboundService", inboundService);
        ReflectionTestUtils.setField(controller, "userMapper", userMapper);
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    private void bindRequestWithToken(Long userId, int roleId) {
        User u = new User();
        u.setId(userId);
        u.setUser("user-" + userId);
        u.setRoleId(roleId);
        String token = JwtUtil.generateToken(u);
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Authorization", token);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
    }

    @Test
    void createListDelete_delegateToService() {
        LandingDto dto = new LandingDto();
        dto.setName("hk");
        dto.setLink("1.2.3.4:1080");
        when(landingService.createLanding(dto)).thenReturn(R.ok("created"));
        when(landingService.getLandings()).thenReturn(R.ok(Collections.emptyList()));
        when(landingService.deleteLanding(12L)).thenReturn(R.ok());

        assertEquals("created", controller.create(dto).getData());
        assertEquals(0, controller.list().getCode());
        Map<String, Object> delBody = new HashMap<>();
        delBody.put("id", 12);
        assertEquals(0, controller.delete(delBody).getCode());
        verify(landingService).deleteLanding(12L);
    }

    @Test
    void update_serviceFailure_returnsErrorWithoutPushing() {
        LandingDto dto = new LandingDto();
        dto.setId(1L);
        when(landingService.updateLanding(dto)).thenReturn(R.err("落地不存在(可能已经被删了)"));

        R res = controller.update(dto);

        assertNotEquals(0, res.getCode());
        assertEquals("落地不存在(可能已经被删了)", res.getMsg());
        verify(inboundService, never()).pushNodeConfig(any());
    }

    @Test
    void update_pushesAffectedNodesAndReportsPartialFailures() {
        LandingDto dto = new LandingDto();
        dto.setId(5L);

        JSONObject allOkData = new JSONObject();
        allOkData.put("nodeIds", Arrays.asList(10L, 20L));
        when(landingService.updateLanding(dto)).thenReturn(R.ok(allOkData));
        when(inboundService.pushNodeConfig(10L)).thenReturn(R.ok());
        when(inboundService.pushNodeConfig(20L)).thenReturn(R.ok());

        R okRes = controller.update(dto);
        assertEquals(0, okRes.getCode());
        verify(inboundService).pushNodeConfig(10L);
        verify(inboundService).pushNodeConfig(20L);

        JSONObject partialFailData = new JSONObject();
        partialFailData.put("nodeIds", Arrays.asList(10L, "bad-id", 30L));
        when(landingService.updateLanding(dto)).thenReturn(R.ok(partialFailData));
        when(inboundService.pushNodeConfig(30L)).thenReturn(R.err("节点不在线"));

        R failRes = controller.update(dto);
        assertNotEquals(0, failRes.getCode());
        assertTrue(failRes.getMsg().contains("30"));
        assertFalse(failRes.getMsg().contains("10"));
    }

    @Test
    void test_checksNodeAdminPermissionAndDelegatesWhenAllowed() {
        Map<String, Object> body = new HashMap<>();
        body.put("nodeId", 7L);
        body.put("link", "1.2.3.4:1080");

        // 1. 节点管理员仅有节点 8 权限，访问节点 7 返回 403
        bindRequestWithToken(20L, 1);
        User nodeAdmin = new User();
        nodeAdmin.setId(20L);
        nodeAdmin.setManagedNodes("8");
        when(userMapper.selectById(20L)).thenReturn(nodeAdmin);

        R denied = controller.test(body);
        assertEquals(403, denied.getCode());
        assertEquals("无权管理该节点", denied.getMsg());
        verify(landingService, never()).testLanding(any(), any());

        // 2. 授权节点管理员访问节点 7 放行
        nodeAdmin.setManagedNodes("7,8");
        when(landingService.testLanding(7L, "1.2.3.4:1080")).thenReturn(R.ok("ok"));
        R allowedByNodeAdmin = controller.test(body);
        assertEquals(0, allowedByNodeAdmin.getCode());

        // 3. 超级管理员直接放行
        bindRequestWithToken(1L, 0);
        R allowedBySuper = controller.test(body);
        assertEquals(0, allowedBySuper.getCode());
        verify(landingService, times(2)).testLanding(7L, "1.2.3.4:1080");
    }
}
