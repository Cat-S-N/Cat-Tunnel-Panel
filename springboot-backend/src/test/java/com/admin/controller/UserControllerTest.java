package com.admin.controller;

import com.admin.common.annotation.RequireRole;
import com.admin.common.aop.RoleAspect;
import com.admin.common.dto.QuantumultXPermissionDto;
import com.admin.common.lang.R;
import com.admin.common.utils.AuditLogStore;
import com.admin.common.utils.JwtUtil;
import com.admin.entity.User;
import com.admin.mapper.UserMapper;
import com.admin.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * UserController 审计日志查询/清空权限边界、参数容错、节点管理权限分配与子接口单元测试
 */
@ExtendWith(MockitoExtension.class)
class UserControllerTest {

    @Mock
    private UserService userService;

    @Mock
    private UserMapper userMapper;

    @Mock
    private AuditLogStore auditLogStore;

    private UserController controller;
    private UserController proxiedController;

    @BeforeEach
    void setUp() {
        JwtUtil jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "secretKey", "user-controller-test-secret-key-2026");
        jwtUtil.init();

        controller = new UserController();
        ReflectionTestUtils.setField(controller, "userService", userService);
        ReflectionTestUtils.setField(controller, "auditLogStore", auditLogStore);

        RoleAspect roleAspect = new RoleAspect();
        ReflectionTestUtils.setField(roleAspect, "userMapper", userMapper);

        AspectJProxyFactory factory = new AspectJProxyFactory(controller);
        factory.setProxyTargetClass(true);
        factory.addAspect(roleAspect);
        proxiedController = factory.getProxy();
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    private void bindAuth(Long userId, int roleId) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        if (userId != null) {
            User u = new User();
            u.setId(userId);
            u.setUser("u_" + userId);
            u.setRoleId(roleId);
            req.addHeader("Authorization", JwtUtil.generateToken(u));
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
    }

    @Test
    void auditEndpointsRequireStrictSuperAdminRoleAndRejectNodeAdminsAndUsers() throws Exception {
        Method auditLogsMethod = UserController.class.getDeclaredMethod("auditLogs", Map.class);
        Method clearMethod = UserController.class.getDeclaredMethod("clearAuditLogs");
        RequireRole auditRole = auditLogsMethod.getAnnotation(RequireRole.class);
        RequireRole clearRole = clearMethod.getAnnotation(RequireRole.class);
        assertNotNull(auditRole);
        assertNotNull(clearRole);
        assertFalse(auditRole.allowNodeAdmin(), "审计日志查询严禁向节点管理员放开");
        assertFalse(clearRole.allowNodeAdmin(), "清空审计日志严禁向节点管理员放开");

        // 普通用户访问 -> 403
        bindAuth(10L, 1);
        R userDenied = proxiedController.auditLogs(Collections.emptyMap());
        assertEquals(403, userDenied.getCode());
        R userClearDenied = proxiedController.clearAuditLogs();
        assertEquals(403, userClearDenied.getCode());

        // 持有节点权限的节点管理员访问 -> 依然 403
        bindAuth(20L, 1);
        R nodeAdminDenied = proxiedController.auditLogs(Collections.emptyMap());
        assertEquals(403, nodeAdminDenied.getCode());
        verifyNoInteractions(auditLogStore);

        // 超级管理员访问 -> 放行
        bindAuth(1L, 0);
        Map<String, Object> mockResult = new LinkedHashMap<>();
        mockResult.put("total", 5L);
        when(auditLogStore.query(1, 20, null, null, null, null)).thenReturn(mockResult);
        R adminAllowed = proxiedController.auditLogs(null);
        assertEquals(0, adminAllowed.getCode());
        assertSame(mockResult, adminAllowed.getData());
    }

    @Test
    void auditLogs_handlesNullStoreMalformedParamsAndForwardsCategoryAndFilters() {
        Map<String, Object> body = new HashMap<>();
        body.put("page", " 2 ");
        body.put("size", " 50 ");
        body.put("keyword", "alice");
        body.put("status", "failed");
        body.put("category", "register");
        body.put("userId", " 88 ");

        when(auditLogStore.query(2, 50, "alice", 88L, "failed", "register"))
                .thenReturn(Collections.singletonMap("total", 1L));

        R ok = controller.auditLogs(body);
        assertEquals(0, ok.getCode());
        verify(auditLogStore).query(2, 50, "alice", 88L, "failed", "register");

        // 非法数字优雅回退默认 page=1, size=20, userId=null
        Map<String, Object> malformed = new HashMap<>();
        malformed.put("page", "not-a-num");
        malformed.put("size", "bad");
        malformed.put("userId", "null");
        controller.auditLogs(malformed);
        verify(auditLogStore).query(1, 20, null, null, null, null);

        // 未初始化兜底
        ReflectionTestUtils.setField(controller, "auditLogStore", null);
        R uninit = controller.auditLogs(Collections.emptyMap());
        assertNotEquals(0, uninit.getCode());
        R clearUninit = controller.clearAuditLogs();
        assertNotEquals(0, clearUninit.getCode());
    }

    @Test
    void clearAuditLogs_returnsDeletedCountForSuperAdmin() {
        bindAuth(1L, 0);
        when(auditLogStore.clearAll()).thenReturn(42);
        R res = proxiedController.clearAuditLogs();
        assertEquals(0, res.getCode());
        assertEquals("已清空 42 条操作记录", res.getData());
    }

    @Test
    @SuppressWarnings("unchecked")
    void myPermissions_distinguishesAnonymousSuperAdminNodeAdminAndNormalUser() {
        MockHttpServletRequest anonReq = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(anonReq));
        R anon = controller.myPermissions();
        assertEquals(401, anon.getCode());

        // 超级管理员
        bindAuth(1L, 0);
        User admin = new User();
        admin.setId(1L);
        admin.setRoleId(0);
        when(userService.getById(1L)).thenReturn(admin);
        R adminRes = controller.myPermissions();
        assertEquals(0, adminRes.getCode());
        Map<String, Object> adminData = (Map<String, Object>) adminRes.getData();
        assertEquals(true, adminData.get("isAdmin"));
        assertEquals(false, adminData.get("isNodeAdmin"));

        // 节点管理员
        bindAuth(9L, 1);
        User nodeAdmin = new User();
        nodeAdmin.setId(9L);
        nodeAdmin.setRoleId(1);
        nodeAdmin.setManagedNodes("5,2,5");
        when(userService.getById(9L)).thenReturn(nodeAdmin);
        R naRes = controller.myPermissions();
        Map<String, Object> naData = (Map<String, Object>) naRes.getData();
        assertEquals(false, naData.get("isAdmin"));
        assertEquals(true, naData.get("isNodeAdmin"));
        assertEquals(Arrays.asList(2L, 5L), naData.get("managedNodeIds"));
    }

    @Test
    void setManagedNodes_andDelete_andQuantumultX_validateBoundariesSafely() {
        // 1. setManagedNodes 参数校验与禁改超管保护
        assertNotEquals(0, controller.setManagedNodes(null).getCode());
        assertNotEquals(0, controller.setManagedNodes(Collections.singletonMap("userId", "abc")).getCode());

        when(userService.getById(99L)).thenReturn(null);
        assertEquals("用户不存在", controller.setManagedNodes(Collections.singletonMap("userId", 99L)).getMsg());

        User superAdmin = new User();
        superAdmin.setId(1L);
        superAdmin.setRoleId(0);
        when(userService.getById(1L)).thenReturn(superAdmin);
        R rejectAdmin = controller.setManagedNodes(Collections.singletonMap("userId", 1L));
        assertNotEquals(0, rejectAdmin.getCode());

        User normal = new User();
        normal.setId(8L);
        normal.setRoleId(1);
        when(userService.getById(8L)).thenReturn(normal);

        Map<String, Object> badNodeIds = new HashMap<>();
        badNodeIds.put("userId", 8L);
        badNodeIds.put("nodeIds", List.of("bad_node"));
        assertNotEquals(0, controller.setManagedNodes(badNodeIds).getCode());

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        when(userService.updateById(userCaptor.capture())).thenReturn(true);
        Map<String, Object> validNodes = new HashMap<>();
        validNodes.put("userId", 8L);
        validNodes.put("nodeIds", Arrays.asList(3, "7", 3));
        R saved = controller.setManagedNodes(validNodes);
        assertEquals(0, saved.getCode());
        assertEquals("3,7", userCaptor.getValue().getManagedNodes());

        // 2. delete 空参及格式防御
        assertNotEquals(0, controller.delete(null).getCode());
        assertNotEquals(0, controller.delete(Collections.singletonMap("id", "bad")).getCode());
        when(userService.deleteUser(8L)).thenReturn(R.ok("deleted"));
        assertEquals(0, controller.delete(Collections.singletonMap("id", 8L)).getCode());

        // 3. quantumultX 用户不存在与启用开关保存
        QuantumultXPermissionDto qxDto = new QuantumultXPermissionDto();
        qxDto.setId(404L);
        qxDto.setEnabled(true);
        when(userService.getById(404L)).thenReturn(null);
        assertNotEquals(0, controller.quantumultX(qxDto).getCode());

        qxDto.setId(8L);
        assertEquals(0, controller.quantumultX(qxDto).getCode());
        assertEquals(1, userCaptor.getValue().getQuantumultXEnabled());
    }
}
