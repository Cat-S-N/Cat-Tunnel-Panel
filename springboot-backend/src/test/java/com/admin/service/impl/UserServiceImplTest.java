package com.admin.service.impl;

import cloud.tianai.captcha.application.ImageCaptchaApplication;
import com.admin.common.dto.LoginDto;
import com.admin.common.dto.ResetFlowDto;
import com.admin.common.dto.UserDto;
import com.admin.common.dto.UserUpdateDto;
import com.admin.common.lang.R;
import com.admin.common.task.AccountLineResume;
import com.admin.common.task.AccountPauseStore;
import com.admin.common.task.UserNodeNameStore;
import com.admin.common.utils.Md5Util;
import com.admin.entity.User;
import com.admin.entity.UserTunnel;
import com.admin.entity.ViteConfig;
import com.admin.mapper.ForwardMapper;
import com.admin.mapper.InboundLineMapper;
import com.admin.mapper.InboundUserMapper;
import com.admin.mapper.UserTunnelMapper;
import com.admin.service.StatisticsFlowService;
import com.admin.service.UserTunnelService;
import com.admin.service.ViteConfigService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

/**
 * UserServiceImpl 登录、创建、更新、删除保护与流量重置单元测试
 */
@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    @Mock
    private ForwardMapper forwardMapper;

    @Mock
    private UserTunnelMapper userTunnelMapper;

    @Mock
    private UserNodeNameStore nodeNameStore;

    @Mock
    private InboundUserMapper inboundUserMapper;

    @Mock
    private InboundLineMapper inboundLineMapper;

    @Mock
    private AccountLineResume accountLineResume;

    @Mock
    private AccountPauseStore accountPauseStore;

    @Mock
    private UserTunnelService userTunnelService;

    @Mock
    private ViteConfigService viteConfigService;

    @Mock
    private StatisticsFlowService statisticsFlowService;

    @Mock
    private ImageCaptchaApplication application;

    private UserServiceImpl userService;

    @BeforeEach
    void setUp() {
        userService = Mockito.spy(new UserServiceImpl());
        ReflectionTestUtils.setField(userService, "forwardMapper", forwardMapper);
        ReflectionTestUtils.setField(userService, "userTunnelMapper", userTunnelMapper);
        ReflectionTestUtils.setField(userService, "nodeNameStore", nodeNameStore);
        ReflectionTestUtils.setField(userService, "inboundUserMapper", inboundUserMapper);
        ReflectionTestUtils.setField(userService, "inboundLineMapper", inboundLineMapper);
        ReflectionTestUtils.setField(userService, "accountLineResume", accountLineResume);
        ReflectionTestUtils.setField(userService, "accountPauseStore", accountPauseStore);
        ReflectionTestUtils.setField(userService, "userTunnelService", userTunnelService);
        ReflectionTestUtils.setField(userService, "viteConfigService", viteConfigService);
        ReflectionTestUtils.setField(userService, "statisticsFlowService", statisticsFlowService);
        ReflectionTestUtils.setField(userService, "application", application);
    }

    private User sampleUser(Long id, String username, String rawPwd, int roleId, int status) {
        User u = new User();
        u.setId(id);
        u.setUser(username);
        u.setPwd(Md5Util.md5(rawPwd));
        u.setRoleId(roleId);
        u.setStatus(status);
        u.setFlow(100L);
        u.setInFlow(10L);
        u.setOutFlow(20L);
        return u;
    }

    @Test
    void login_captchaEnabledAndBlankId_returnsError() {
        ViteConfig cfg = new ViteConfig();
        cfg.setName("captcha_enabled");
        cfg.setValue("true");
        when(viteConfigService.getOne(any(QueryWrapper.class))).thenReturn(cfg);

        LoginDto dto = new LoginDto();
        dto.setUsername("alice");
        dto.setPassword("secret");
        dto.setCaptchaId(" ");

        R res = userService.login(dto);

        assertNotEquals(0, res.getCode());
        assertEquals("验证码校验失败", res.getMsg());
        verify(userService, never()).getOne(any());
    }

    @Test
    void login_userNotFoundOrWrongPassword_returnsError() {
        when(viteConfigService.getOne(any(QueryWrapper.class))).thenReturn(null);
        doReturn(null).when(userService).getOne(any(QueryWrapper.class));

        LoginDto dto = new LoginDto();
        dto.setUsername("alice");
        dto.setPassword("secret");

        R notFound = userService.login(dto);
        assertNotEquals(0, notFound.getCode());
        assertEquals("账号或密码错误", notFound.getMsg());

        doReturn(sampleUser(1L, "alice", "other", 1, 1)).when(userService).getOne(any(QueryWrapper.class));
        R wrongPwd = userService.login(dto);
        assertNotEquals(0, wrongPwd.getCode());
        assertEquals("账号或密码错误", wrongPwd.getMsg());
    }

    @Test
    void login_disabledAccount_returnsError() {
        when(viteConfigService.getOne(any(QueryWrapper.class))).thenReturn(null);
        doReturn(sampleUser(1L, "alice", "secret", 1, 0)).when(userService).getOne(any(QueryWrapper.class));

        LoginDto dto = new LoginDto();
        dto.setUsername("alice");
        dto.setPassword("secret");

        R res = userService.login(dto);

        assertNotEquals(0, res.getCode());
        assertEquals("账户停用", res.getMsg());
    }

    @Test
    @SuppressWarnings("unchecked")
    void login_success_returnsTokenAndRequirePasswordChangeFlag() {
        when(viteConfigService.getOne(any(QueryWrapper.class))).thenReturn(null);
        doReturn(sampleUser(1L, "admin_user", "admin_user", 0, 1))
                .when(userService).getOne(any(QueryWrapper.class));

        LoginDto defaultDto = new LoginDto();
        defaultDto.setUsername("admin_user");
        defaultDto.setPassword("admin_user");

        R defaultRes = userService.login(defaultDto);
        assertEquals(0, defaultRes.getCode());
        Map<String, Object> defaultData = (Map<String, Object>) defaultRes.getData();
        assertNotNull(defaultData.get("token"));
        assertEquals("admin_user", defaultData.get("name"));
        assertEquals(0, defaultData.get("role_id"));
        assertEquals(true, defaultData.get("requirePasswordChange"));

        doReturn(sampleUser(2L, "bob", "strongPwd", 1, 1))
                .when(userService).getOne(any(QueryWrapper.class));
        LoginDto normalDto = new LoginDto();
        normalDto.setUsername("bob");
        normalDto.setPassword("strongPwd");

        R normalRes = userService.login(normalDto);
        assertEquals(0, normalRes.getCode());
        Map<String, Object> normalData = (Map<String, Object>) normalRes.getData();
        assertEquals(false, normalData.get("requirePasswordChange"));
    }

    @Test
    void createUser_duplicateUsername_returnsError() {
        doReturn(sampleUser(1L, "alice", "123", 1, 1)).when(userService).getOne(any(QueryWrapper.class));

        UserDto dto = new UserDto();
        dto.setUser("alice");
        dto.setPwd("123456");
        dto.setFlow(100L);
        dto.setNum(5);
        dto.setExpTime(9999999999999L);

        R res = userService.createUser(dto);

        assertNotEquals(0, res.getCode());
        assertEquals("用户名已存在", res.getMsg());
        verify(userService, never()).save(any());
    }

    @Test
    void createUser_success_encryptsPasswordAndSetsDefaults() {
        doReturn(null).when(userService).getOne(any(QueryWrapper.class));
        doReturn(true).when(userService).save(any(User.class));

        UserDto dto = new UserDto();
        dto.setUser("bob");
        dto.setPwd("plainPwd");
        dto.setFlow(200L);
        dto.setNum(10);
        dto.setExpTime(9999999999999L);

        R res = userService.createUser(dto);

        assertEquals(0, res.getCode());
        verify(userService).save(argThat(u ->
                "bob".equals(u.getUser())
                        && Md5Util.md5("plainPwd").equals(u.getPwd())
                        && u.getRoleId() == 1
                        && u.getStatus() == 1
                        && u.getCreatedTime() != null
                        && u.getUpdatedTime() != null
        ));
    }

    @Test
    void createUser_saveFails_returnsError() {
        doReturn(null).when(userService).getOne(any(QueryWrapper.class));
        doReturn(false).when(userService).save(any(User.class));

        UserDto dto = new UserDto();
        dto.setUser("bob");
        dto.setPwd("plainPwd");
        dto.setFlow(200L);
        dto.setNum(10);
        dto.setExpTime(9999999999999L);

        R res = userService.createUser(dto);

        assertNotEquals(0, res.getCode());
        assertEquals("用户创建失败", res.getMsg());
    }

    @Test
    void updateUser_notFoundOrDuplicateOrAdmin_returnsError() {
        UserUpdateDto dto = new UserUpdateDto();
        dto.setId(99L);
        dto.setUser("alice");
        dto.setFlow(100L);
        dto.setNum(5);

        doReturn(null).when(userService).getById(99L);
        R notFound = userService.updateUser(dto);
        assertNotEquals(0, notFound.getCode());
        assertEquals("用户不存在", notFound.getMsg());

        doReturn(sampleUser(99L, "old", "123", 1, 1)).when(userService).getById(99L);
        doReturn(sampleUser(2L, "alice", "123", 1, 1)).when(userService).getOne(any(QueryWrapper.class));
        R duplicate = userService.updateUser(dto);
        assertNotEquals(0, duplicate.getCode());
        assertEquals("用户名已被其他用户使用", duplicate.getMsg());

        doReturn(sampleUser(99L, "admin", "123", 0, 1)).when(userService).getById(99L);
        doReturn(null).when(userService).getOne(any(QueryWrapper.class));
        R adminProtected = userService.updateUser(dto);
        assertNotEquals(0, adminProtected.getCode());
        assertEquals("不能修改管理员用户信息", adminProtected.getMsg());
        verify(userService, never()).updateById(any());
    }

    @Test
    void updateUser_blankPassword_keepsNullPwdAndResumesLines() {
        User existing = sampleUser(10L, "alice", "oldPwd", 1, 1);
        doReturn(existing).when(userService).getById(10L);
        doReturn(null).when(userService).getOne(any(QueryWrapper.class));
        doReturn(true).when(userService).updateById(any(User.class));

        UserUpdateDto dto = new UserUpdateDto();
        dto.setId(10L);
        dto.setUser("alice");
        dto.setPwd("   ");
        dto.setFlow(500L);
        dto.setNum(20);

        R res = userService.updateUser(dto);

        assertEquals(0, res.getCode());
        verify(userService).updateById(argThat(u -> u.getId().equals(10L) && u.getPwd() == null));
        verify(accountLineResume).resumeIfAccountOk(10L);
    }

    @Test
    void updateUser_nonBlankPassword_encryptsPassword() {
        User existing = sampleUser(10L, "alice", "oldPwd", 1, 1);
        doReturn(existing).when(userService).getById(10L);
        doReturn(null).when(userService).getOne(any(QueryWrapper.class));
        doReturn(true).when(userService).updateById(any(User.class));

        UserUpdateDto dto = new UserUpdateDto();
        dto.setId(10L);
        dto.setUser("alice");
        dto.setPwd("newPwd");
        dto.setFlow(500L);
        dto.setNum(20);

        R res = userService.updateUser(dto);

        assertEquals(0, res.getCode());
        verify(userService).updateById(argThat(u -> Md5Util.md5("newPwd").equals(u.getPwd())));
    }

    @Test
    void deleteUser_notFoundOrAdmin_returnsError() {
        doReturn(null).when(userService).getById(99L);
        R notFound = userService.deleteUser(99L);
        assertNotEquals(0, notFound.getCode());
        assertEquals("用户不存在", notFound.getMsg());

        doReturn(sampleUser(1L, "admin", "123", 0, 1)).when(userService).getById(1L);
        R adminProtected = userService.deleteUser(1L);
        assertNotEquals(0, adminProtected.getCode());
        assertEquals("不能删除管理员用户", adminProtected.getMsg());
        verify(userService, never()).removeById(any());
    }

    @Test
    void deleteUser_normalUser_cascadesAndDeletes() {
        doReturn(sampleUser(5L, "alice", "123", 1, 1)).when(userService).getById(5L);
        when(forwardMapper.selectList(any())).thenReturn(Collections.emptyList());
        doReturn(true).when(userService).removeById(5L);

        R res = userService.deleteUser(5L);

        assertEquals(0, res.getCode());
        verify(userTunnelMapper).delete(any());
        verify(inboundUserMapper).delete(any());
        verify(inboundLineMapper).delete(any());
        verify(nodeNameStore).deleteUser(5L);
        verify(statisticsFlowService).remove(any());
        verify(accountPauseStore).removeByUser(5L);
        verify(userService).removeById(5L);
    }

    @Test
    void reset_accountAndTunnelFlow_handlesNotFoundAndSuccess() {
        ResetFlowDto accountDto = new ResetFlowDto();
        accountDto.setType(1);
        accountDto.setId(10);

        doReturn(null).when(userService).getById(10);
        R accountNotFound = userService.reset(accountDto);
        assertNotEquals(0, accountNotFound.getCode());
        assertEquals("用户不存在", accountNotFound.getMsg());

        User user = sampleUser(10L, "alice", "123", 1, 1);
        doReturn(user).when(userService).getById(10);
        doReturn(true).when(userService).updateById(any(User.class));
        R accountOk = userService.reset(accountDto);
        assertEquals(0, accountOk.getCode());
        verify(userService).updateById(argThat(u -> u.getInFlow() == 0L && u.getOutFlow() == 0L));
        verify(accountLineResume).resumeIfAccountOk(10L);

        ResetFlowDto tunnelDto = new ResetFlowDto();
        tunnelDto.setType(2);
        tunnelDto.setId(20);

        when(userTunnelService.getById(20)).thenReturn(null);
        R tunnelNotFound = userService.reset(tunnelDto);
        assertNotEquals(0, tunnelNotFound.getCode());
        assertEquals("隧道不存在", tunnelNotFound.getMsg());

        UserTunnel ut = new UserTunnel();
        ut.setId(20);
        ut.setInFlow(100L);
        ut.setOutFlow(200L);
        when(userTunnelService.getById(20)).thenReturn(ut);
        R tunnelOk = userService.reset(tunnelDto);
        assertEquals(0, tunnelOk.getCode());
        verify(userTunnelService).updateById(argThat(t -> t.getInFlow() == 0L && t.getOutFlow() == 0L));
    }

    @Test
    @SuppressWarnings("unchecked")
    void getAllUsers_stripsPasswordHashesFromReturnedList() {
        User u1 = sampleUser(10L, "alice", "secret1", 1, 1);
        User u2 = sampleUser(11L, "bob", "secret2", 1, 0);
        doReturn(java.util.Arrays.asList(u1, u2)).when(userService).list(any(QueryWrapper.class));

        R res = userService.getAllUsers();
        assertEquals(0, res.getCode());
        java.util.List<User> list = (java.util.List<User>) res.getData();
        assertEquals(2, list.size());
        assertNull(list.get(0).getPwd(), "列表接口不得返回用户密码哈希");
        assertNull(list.get(1).getPwd(), "列表接口不得返回用户密码哈希");
        assertEquals("alice", list.get(0).getUser());
        assertEquals("bob", list.get(1).getUser());
    }
}
