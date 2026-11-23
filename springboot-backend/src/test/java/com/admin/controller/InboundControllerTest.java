package com.admin.controller;

import com.admin.common.dto.InboundDto;
import com.admin.common.dto.InboundUserDto;
import com.admin.common.lang.R;
import com.admin.common.utils.JwtUtil;
import com.admin.common.utils.SubAccessStore;
import com.admin.entity.Inbound;
import com.admin.entity.InboundUser;
import com.admin.entity.User;
import com.admin.mapper.InboundUserMapper;
import com.admin.mapper.UserMapper;
import com.admin.service.InboundService;
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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * InboundController 节点权限控制、入站过滤、自助分配与订阅日志查询单元测试
 */
@ExtendWith(MockitoExtension.class)
class InboundControllerTest {

    @Mock
    private InboundService inboundService;

    @Mock
    private UserMapper userMapper;

    @Mock
    private InboundUserMapper inboundUserMapper;

    @Mock
    private SubAccessStore subAccessStore;

    private InboundController controller;

    @BeforeEach
    void setUp() {
        JwtUtil jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "secretKey", "inbound-controller-test-secret");
        jwtUtil.init();

        controller = new InboundController();
        ReflectionTestUtils.setField(controller, "inboundService", inboundService);
        ReflectionTestUtils.setField(controller, "userMapper", userMapper);
        ReflectionTestUtils.setField(controller, "inboundUserMapper", inboundUserMapper);
        ReflectionTestUtils.setField(controller, "subAccessStore", subAccessStore);
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    private void bindUser(Long uid, int roleId, String managedNodes) {
        User u = new User();
        u.setId(uid);
        u.setUser("u" + uid);
        u.setRoleId(roleId);
        u.setManagedNodes(managedNodes);
        String token = JwtUtil.generateToken(u);
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Authorization", token);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
        if (roleId != 0) {
            lenient().when(userMapper.selectById(uid)).thenReturn(u);
        }
    }

    @Test
    void nodeAdminDeniedOnUnauthorizedNodesAcrossEndpoints() {
        bindUser(50L, 1, "1");

        InboundDto dto = new InboundDto();
        dto.setNodeId(9L);
        assertEquals(403, controller.create(dto).getCode());

        Map<String, Object> nodeBody = new HashMap<>();
        nodeBody.put("nodeId", 9L);
        nodeBody.put("link", "1.2.3.4:1080");
        assertEquals(403, controller.oneClick(nodeBody).getCode());
        assertEquals(403, controller.oneClickRelay(nodeBody).getCode());
        assertEquals(403, controller.deleteByNode(nodeBody).getCode());
        assertEquals(403, controller.pushConfig(nodeBody).getCode());
        assertEquals(403, controller.nodeUsers(nodeBody).getCode());
        assertEquals(403, controller.lineStatus(nodeBody).getCode());
        assertEquals(403, controller.lineUpdate(nodeBody).getCode());
        assertEquals(403, controller.lineDelete(nodeBody).getCode());

        Inbound in = new Inbound();
        in.setId(100L);
        in.setNodeId(9L);
        when(inboundService.getById(100L)).thenReturn(in);
        InboundUser iu = new InboundUser();
        iu.setId(200L);
        iu.setInboundId(100L);
        when(inboundUserMapper.selectById(200L)).thenReturn(iu);

        Map<String, Object> idBody = Collections.singletonMap("id", 100L);
        assertEquals(403, controller.delete(idBody).getCode());
        InboundUserDto assignDto = new InboundUserDto();
        assignDto.setInboundId(100L);
        assignDto.setNodeId(9L);
        assertEquals(403, controller.assign(assignDto).getCode());
        assertEquals(403, controller.assignAll(assignDto).getCode());
        assertEquals(403, controller.assignSelf(assignDto).getCode());

        Map<String, Object> iuBody = Collections.singletonMap("id", 200L);
        assertEquals(403, controller.unassign(iuBody).getCode());
        assertEquals(403, controller.resetSocksPassword(iuBody).getCode());

        verify(inboundService, never()).createInbound(any());
        verify(inboundService, never()).deleteInbound(any());
        verify(inboundService, never()).assignUser(any());
        verify(inboundService, never()).unassignUser(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void list_filtersForNodeAdminAndKeepsAllForSuperAdmin() {
        Inbound allowedEntity = new Inbound();
        allowedEntity.setId(1L);
        allowedEntity.setNodeId(2L);
        Inbound deniedEntity = new Inbound();
        deniedEntity.setId(2L);
        deniedEntity.setNodeId(9L);
        Map<String, Object> allowedMap = Collections.singletonMap("nodeId", "3");
        Map<String, Object> deniedMap = Collections.singletonMap("nodeId", "8");

        when(inboundService.getInbounds()).thenReturn(
                R.ok(Arrays.asList(allowedEntity, deniedEntity, allowedMap, deniedMap)));

        bindUser(10L, 1, "2,3");
        R filteredRes = controller.list();
        assertEquals(0, filteredRes.getCode());
        List<Object> filtered = (List<Object>) filteredRes.getData();
        assertEquals(2, filtered.size());
        assertTrue(filtered.contains(allowedEntity));
        assertTrue(filtered.contains(allowedMap));

        bindUser(1L, 0, null);
        R superRes = controller.list();
        assertEquals(4, ((List<?>) superRes.getData()).size());
    }

    @Test
    void assignSelf_clearsLimitsAndInjectsCurrentUserId() {
        bindUser(77L, 1, "5");
        InboundUserDto dto = new InboundUserDto();
        dto.setNodeId(5L);
        dto.setSpeedId(3);
        dto.setExpTime(999999L);
        dto.setFlow(100L);
        when(inboundService.assignAllToUser(any())).thenReturn(R.ok());

        R res = controller.assignSelf(dto);

        assertEquals(0, res.getCode());
        assertEquals(77L, dto.getUserId());
        assertNull(dto.getSpeedId());
        assertNull(dto.getExpTime());
        assertNull(dto.getFlow());
        verify(inboundService).assignAllToUser(dto);
    }

    @Test
    void resetSubToken_enforcesSuperAdminForResetAllAndNodePermissionForLine() {
        Map<String, Object> resetAllBody = new HashMap<>();
        resetAllBody.put("resetAll", true);
        resetAllBody.put("userId", 8L);

        bindUser(10L, 1, "1");
        R denied = controller.resetSubToken(resetAllBody);
        assertEquals(403, denied.getCode());
        assertEquals("仅超级管理员可重置聚合订阅链接", denied.getMsg());

        bindUser(1L, 0, null);
        when(inboundService.resetSubToken(8L, null, null, true)).thenReturn(R.ok());
        assertEquals(0, controller.resetSubToken(resetAllBody).getCode());

        bindUser(10L, 1, "1");
        Map<String, Object> lineBody = new HashMap<>();
        lineBody.put("userId", 8L);
        lineBody.put("nodeId", 2L);
        assertEquals(403, controller.resetSubToken(lineBody).getCode());

        lineBody.put("nodeId", 1L);
        lineBody.put("landingId", "null");
        when(inboundService.resetSubToken(8L, 1L, null, false)).thenReturn(R.ok());
        assertEquals(0, controller.resetSubToken(lineBody).getCode());
    }

    @Test
    void userNamesAndSave_validateInputs() {
        R badUserId = controller.userNames(Collections.singletonMap("userId", "abc"));
        assertNotEquals(0, badUserId.getCode());
        assertEquals("车友编号不对", badUserId.getMsg());

        Map<String, Object> badItems = new HashMap<>();
        badItems.put("userId", 1L);
        badItems.put("items", "not-a-list");
        R badItemsRes = controller.userNamesSave(badItems);
        assertNotEquals(0, badItemsRes.getCode());
        assertEquals("参数不全", badItemsRes.getMsg());

        Map<String, Object> badSaveUid = new HashMap<>();
        badSaveUid.put("userId", "xyz");
        badSaveUid.put("items", Collections.emptyList());
        assertEquals("车友编号不对", controller.userNamesSave(badSaveUid).getMsg());
    }

    @Test
    void subLogs_validatesParamsStoreAndPermissions() {
        bindUser(1L, 0, null);
        assertEquals("参数格式不正确", controller.subLogs(Collections.singletonMap("userId", "bad")).getMsg());
        assertEquals("缺少 userId", controller.subLogs(new HashMap<>()).getMsg());

        InboundController noStoreController = new InboundController();
        ReflectionTestUtils.setField(noStoreController, "userMapper", userMapper);
        assertEquals("订阅记录服务未初始化",
                noStoreController.subLogs(Collections.singletonMap("userId", 5L)).getMsg());

        bindUser(20L, 1, "3");
        Map<String, Object> missingNode = new HashMap<>();
        missingNode.put("userId", 5L);
        assertEquals(403, controller.subLogs(missingNode).getCode());

        Map<String, Object> unownedNode = new HashMap<>();
        unownedNode.put("userId", 5L);
        unownedNode.put("nodeId", 9L);
        assertEquals(403, controller.subLogs(unownedNode).getCode());

        Map<String, Object> notAssigned = new HashMap<>();
        notAssigned.put("userId", 5L);
        notAssigned.put("nodeId", 3L);
        when(subAccessStore.userHasNode(5L, 3L)).thenReturn(false);
        R notAssignedRes = controller.subLogs(notAssigned);
        assertEquals(403, notAssignedRes.getCode());
        assertEquals("该用户未分配到此节点", notAssignedRes.getMsg());

        when(subAccessStore.userHasNode(5L, 3L)).thenReturn(true);
        when(subAccessStore.queryByUser(5L, 3L)).thenReturn(Collections.singletonMap("pulls24h", 2L));
        R okNodeAdmin = controller.subLogs(notAssigned);
        assertEquals(0, okNodeAdmin.getCode());

        bindUser(1L, 0, null);
        when(subAccessStore.queryByUser(eq(5L), isNull())).thenReturn(Collections.singletonMap("pulls24h", 9L));
        R okSuper = controller.subLogs(Collections.singletonMap("userId", 5L));
        assertEquals(0, okSuper.getCode());
    }
}
