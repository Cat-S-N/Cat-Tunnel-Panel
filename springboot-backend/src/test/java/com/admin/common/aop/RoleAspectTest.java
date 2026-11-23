package com.admin.common.aop;

import com.admin.common.annotation.RequireRole;
import com.admin.common.lang.R;
import com.admin.common.utils.JwtUtil;
import com.admin.common.utils.NodePermissionHelper;
import com.admin.entity.User;
import com.admin.mapper.UserMapper;
import org.aspectj.lang.ProceedingJoinPoint;
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

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * RoleAspect 角色拦截、节点管理员放行与 NodePermissionHelper 解析工具单元测试
 */
@ExtendWith(MockitoExtension.class)
class RoleAspectTest {

    @Mock
    private UserMapper userMapper;

    @Mock
    private ProceedingJoinPoint joinPoint;

    private RoleAspect roleAspect;

    static class AnnotatedTarget {
        @RequireRole
        public R adminOnly() {
            return R.ok("admin-ok");
        }

        @RequireRole(allowNodeAdmin = true)
        public R allowNodeAdmin() {
            return R.ok("node-admin-ok");
        }
    }

    @BeforeEach
    void setUp() {
        JwtUtil jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "secretKey", "role-aspect-test-secret");
        jwtUtil.init();

        roleAspect = new RoleAspect();
        ReflectionTestUtils.setField(roleAspect, "userMapper", userMapper);
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    private RequireRole annotationOf(String methodName) throws Exception {
        Method m = AnnotatedTarget.class.getDeclaredMethod(methodName);
        return m.getAnnotation(RequireRole.class);
    }

    private void bindRequestToken(Long uid, int roleId) {
        User u = new User();
        u.setId(uid);
        u.setUser("u" + uid);
        u.setRoleId(roleId);
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Authorization", JwtUtil.generateToken(u));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
    }

    @Test
    void requireRoleAnnotation_hasExpectedMetadata() throws Exception {
        Target target = RequireRole.class.getAnnotation(Target.class);
        Retention retention = RequireRole.class.getAnnotation(Retention.class);
        assertArrayEquals(new ElementType[]{ElementType.METHOD}, target.value());
        assertEquals(RetentionPolicy.RUNTIME, retention.value());
        assertFalse(annotationOf("adminOnly").allowNodeAdmin());
        assertTrue(annotationOf("allowNodeAdmin").allowNodeAdmin());
    }

    @Test
    void checkRole_noRequestContext_returns500() throws Throwable {
        Object res = roleAspect.checkRole(joinPoint, annotationOf("adminOnly"));

        assertTrue(res instanceof R);
        assertEquals(500, ((R) res).getCode());
        assertEquals("无法获取请求信息", ((R) res).getMsg());
        verify(joinPoint, never()).proceed();
    }

    @Test
    void checkRole_superAdmin_proceedsForBothModes() throws Throwable {
        bindRequestToken(1L, 0);
        when(joinPoint.proceed()).thenReturn(R.ok("passed"));

        R r1 = (R) roleAspect.checkRole(joinPoint, annotationOf("adminOnly"));
        R r2 = (R) roleAspect.checkRole(joinPoint, annotationOf("allowNodeAdmin"));

        assertEquals(0, r1.getCode());
        assertEquals("passed", r1.getData());
        assertEquals(0, r2.getCode());
        verify(joinPoint, times(2)).proceed();
        verifyNoInteractions(userMapper);
    }

    @Test
    void checkRole_normalUser_blockedOnBothModes() throws Throwable {
        bindRequestToken(100L, 1);

        R r1 = (R) roleAspect.checkRole(joinPoint, annotationOf("adminOnly"));
        assertEquals(403, r1.getCode());
        assertEquals("权限不足，仅管理员可操作", r1.getMsg());

        User plainUser = new User();
        plainUser.setId(100L);
        plainUser.setManagedNodes("   ");
        when(userMapper.selectById(100L)).thenReturn(plainUser);
        R r2 = (R) roleAspect.checkRole(joinPoint, annotationOf("allowNodeAdmin"));
        assertEquals(403, r2.getCode());

        when(userMapper.selectById(100L)).thenReturn(null);
        R r3 = (R) roleAspect.checkRole(joinPoint, annotationOf("allowNodeAdmin"));
        assertEquals(403, r3.getCode());

        verify(joinPoint, never()).proceed();
    }

    @Test
    void checkRole_nodeAdmin_blockedOnAdminOnlyAndAllowedWhenPermitted() throws Throwable {
        bindRequestToken(20L, 1);
        User nodeAdmin = new User();
        nodeAdmin.setId(20L);
        nodeAdmin.setManagedNodes("2,5");
        when(userMapper.selectById(20L)).thenReturn(nodeAdmin);
        when(joinPoint.proceed()).thenReturn(R.ok("node-admin-ok"));

        R denied = (R) roleAspect.checkRole(joinPoint, annotationOf("adminOnly"));
        assertEquals(403, denied.getCode());
        verify(joinPoint, never()).proceed();

        R allowed = (R) roleAspect.checkRole(joinPoint, annotationOf("allowNodeAdmin"));
        assertEquals(0, allowed.getCode());
        assertEquals("node-admin-ok", allowed.getData());
        verify(joinPoint, times(1)).proceed();
    }

    @Test
    void checkRole_propagatesExceptionFromProceed() throws Throwable {
        bindRequestToken(1L, 0);
        when(joinPoint.proceed()).thenThrow(new IllegalStateException("business boom"));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> roleAspect.checkRole(joinPoint, annotationOf("adminOnly")));
        assertEquals("business boom", ex.getMessage());
    }

    @Test
    void nodePermissionHelper_parsesJoinsAndChecksManagedNodes() {
        assertEquals(Collections.emptySet(), NodePermissionHelper.parseNodeIds(null));
        assertEquals(Collections.emptySet(), NodePermissionHelper.parseNodeIds(" , -1 , 0 , abc "));

        Set<Long> parsed = NodePermissionHelper.parseNodeIds(" 5, 2, bad, 5, 9 ");
        assertEquals(new LinkedHashSet<>(Arrays.asList(5L, 2L, 9L)), parsed);
        assertEquals(Arrays.asList(2L, 5L, 9L), NodePermissionHelper.toSortedList(parsed));
        assertEquals("5,2,9", NodePermissionHelper.joinNodeIds(Arrays.asList(null, 0L, 5L, 2L, 5L, 9L)));
        assertEquals("", NodePermissionHelper.joinNodeIds(Collections.emptyList()));

        bindRequestToken(30L, 1);
        User u = new User();
        u.setId(30L);
        u.setManagedNodes("3,7");
        when(userMapper.selectById(30L)).thenReturn(u);
        assertFalse(NodePermissionHelper.canManageNode(userMapper, null));
        assertTrue(NodePermissionHelper.canManageNode(userMapper, 3L));
        assertFalse(NodePermissionHelper.canManageNode(userMapper, 4L));

        bindRequestToken(1L, 0);
        assertTrue(NodePermissionHelper.canManageNode(userMapper, 999L));
    }
}
