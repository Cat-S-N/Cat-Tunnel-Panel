package com.admin.service.impl;

import cloud.tianai.captcha.application.ImageCaptchaApplication;
import com.admin.common.dto.ChangePasswordDto;
import com.admin.common.dto.LoginDto;
import com.admin.common.dto.ResetFlowDto;
import com.admin.common.dto.UserDto;
import com.admin.common.dto.UserUpdateDto;
import com.admin.common.lang.R;
import com.admin.common.task.AccountLineResume;
import com.admin.common.task.AccountPauseStore;
import com.admin.common.task.UserNodeNameStore;
import com.admin.common.utils.AuditLogStore;
import com.admin.common.utils.JwtUtil;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

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

    @Mock
    private AuditLogStore auditLogStore;

    private UserServiceImpl userService;

    @BeforeEach
    void setUp() {
        JwtUtil jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "secretKey", "user-service-impl-test-secret-key");
        jwtUtil.init();

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
        ReflectionTestUtils.setField(userService, "auditLogStore", auditLogStore);
        userService.clearLoginRateLimitForTest();
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    private void bindAuthenticatedRequest(User tokenUser, String clientIp) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        if (tokenUser != null) {
            req.addHeader("Authorization", JwtUtil.generateToken(tokenUser));
        }
        if (clientIp != null) {
            req.addHeader("X-Forwarded-For", clientIp);
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
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

    @Test
    void login_rateLimitsBruteForceAndClearsFailuresAfterSuccessfulLogin() {
        when(viteConfigService.getOne(any(QueryWrapper.class))).thenReturn(null);
        bindAuthenticatedRequest(null, "203.0.113.10");

        User alice = sampleUser(1L, "alice", "CorrectPass123", 1, 1);
        doReturn(alice).when(userService).getOne(any(QueryWrapper.class));

        LoginDto wrongDto = new LoginDto();
        wrongDto.setUsername("alice");
        wrongDto.setPassword("WrongPass");

        // 连续 9 次失败（低于阈值 10），仍正常返回凭据错误，未触发锁死
        for (int i = 0; i < UserServiceImpl.LOGIN_FAIL_MAX_ATTEMPTS - 1; i++) {
            R res = userService.login(wrongDto);
            assertNotEquals(0, res.getCode());
            assertEquals("账号或密码错误", res.getMsg());
        }

        // 第 10 次输入正确密码：应正常登录成功，且自动清空该 IP+用户名 的失败计数
        LoginDto okDto = new LoginDto();
        okDto.setUsername("  alice  ");
        okDto.setPassword("CorrectPass123");
        R okRes = userService.login(okDto);
        assertEquals(0, okRes.getCode());
        assertEquals(0, userService.getLoginFailTrackedCount(), "登录成功后应自动清除失败记录");

        // 再次连续失败达到 10 次阈值后，第 11 次即使输入正确密码也必须被限流拦截，且不打到数据库查询
        for (int i = 0; i < UserServiceImpl.LOGIN_FAIL_MAX_ATTEMPTS; i++) {
            R fail = userService.login(wrongDto);
            assertNotEquals(0, fail.getCode());
            assertEquals("账号或密码错误", fail.getMsg());
        }
        clearInvocations(userService);
        R lockedRes = userService.login(okDto);
        assertNotEquals(0, lockedRes.getCode());
        assertTrue(lockedRes.getMsg().contains("登录失败次数过多"), lockedRes.getMsg());
        verify(userService, never()).getOne(any(QueryWrapper.class));

        // 同一 IP 下其他正常账号不受牵连，仍可正常登录
        User charlie = sampleUser(3L, "charlie", "CharliePwd9", 1, 1);
        doReturn(charlie).when(userService).getOne(any(QueryWrapper.class));
        LoginDto charlieDto = new LoginDto();
        charlieDto.setUsername("charlie");
        charlieDto.setPassword("CharliePwd9");
        assertEquals(0, userService.login(charlieDto).getCode());
    }

    @Test
    void login_rateLimitEvictsExpiredWindowAndCapsTrackedEntriesAgainstMemoryLeak() {
        long t0 = 10_000_000L;
        int cap = 12;

        // 1. 同一键连续记录达到阈值后被锁定，窗口过期后自动解锁并回收
        for (int i = 0; i < UserServiceImpl.LOGIN_FAIL_MAX_ATTEMPTS; i++) {
            userService.recordLoginFailure("198.51.100.1", "Admin", t0 + i * 100L, cap);
        }
        // 用户名大小写与空白归一化，不能通过大小写绕过锁定
        assertTrue(userService.getLoginLockRemainingMs("198.51.100.1", "  admin ", t0 + 2_000L) > 0L);

        long afterWindow = t0 + UserServiceImpl.LOGIN_FAIL_WINDOW_MS + 5_000L;
        assertEquals(0L, userService.getLoginLockRemainingMs("198.51.100.1", "admin", afterWindow));
        assertEquals(0, userService.getLoginFailTrackedCount(), "过期条目检查后应立即移除空队列");

        // 2. 模拟高频随机 IP + 超长用户名爆破：严格受限于容量上限 cap，绝不无界增长
        String hugeUsername = "u".repeat(5000);
        String key = UserServiceImpl.buildLoginRateKey("10.0.0.1", hugeUsername);
        assertTrue(key.length() <= 140, "限流键必须截断超长用户名防止单键占满内存");

        for (int i = 0; i < 50; i++) {
            userService.recordLoginFailure("10.10.0." + i, "user_" + i, afterWindow + i, cap);
            assertTrue(userService.getLoginFailTrackedCount() <= cap, "跟踪条目数不得超过有界容量上限");
        }
        assertEquals(cap, userService.getLoginFailTrackedCount());

        // 3. 推进时间跨过窗口并触发批量清理后，旧过期条目全部清空，仅剩最新一条
        long t2 = afterWindow + UserServiceImpl.LOGIN_FAIL_WINDOW_MS + 10_000L;
        userService.recordLoginFailure("172.16.0.9", "fresh_user", t2, cap);
        for (int i = 0; i < 64; i++) {
            userService.getLoginLockRemainingMs("172.16.0.9", "fresh_user", t2 + 1);
        }
        assertEquals(1, userService.getLoginFailTrackedCount(), "批量清理后所有过期条目必须彻底释放");
    }

    private ChangePasswordDto buildChangePwdDto(String newUsername, String currentPwd, String newPwd, String confirmPwd) {
        ChangePasswordDto dto = new ChangePasswordDto();
        dto.setNewUsername(newUsername);
        dto.setCurrentPassword(currentPwd);
        dto.setNewPassword(newPwd);
        dto.setConfirmPassword(confirmPwd);
        return dto;
    }

    @Test
    void updatePassword_enforcesValidationBoundariesAndRejectsSameOldAndNewPassword() {
        User activeUser = sampleUser(10L, "alice", "OldSecret1", 1, 1);
        bindAuthenticatedRequest(activeUser, "198.51.100.20");
        lenient().doReturn(activeUser).when(userService).getById(10);

        // 空参、空白用户名、过短/过长/含空格用户名
        assertNotEquals(0, userService.updatePassword(null).getCode());
        assertNotEquals(0, userService.updatePassword(buildChangePwdDto("  \u200B ", "OldSecret1", "NewSecret2", "NewSecret2")).getCode());
        assertNotEquals(0, userService.updatePassword(buildChangePwdDto("ab", "OldSecret1", "NewSecret2", "NewSecret2")).getCode());
        assertNotEquals(0, userService.updatePassword(buildChangePwdDto("a".repeat(33), "OldSecret1", "NewSecret2", "NewSecret2")).getCode());
        assertNotEquals(0, userService.updatePassword(buildChangePwdDto("bad name", "OldSecret1", "NewSecret2", "NewSecret2")).getCode());

        // 空白当前密码、纯空白/零宽新密码、过短(<6)/过长(>64)新密码、确认密码不一致
        assertNotEquals(0, userService.updatePassword(buildChangePwdDto("alice", "   ", "NewSecret2", "NewSecret2")).getCode());
        assertNotEquals(0, userService.updatePassword(buildChangePwdDto("alice", "OldSecret1", "      ", "      ")).getCode());
        assertNotEquals(0, userService.updatePassword(buildChangePwdDto("alice", "OldSecret1", "\u3000\u3000\u3000\u3000\u3000\u3000", "\u3000\u3000\u3000\u3000\u3000\u3000")).getCode());
        assertNotEquals(0, userService.updatePassword(buildChangePwdDto("alice", "OldSecret1", "12345", "12345")).getCode());
        assertNotEquals(0, userService.updatePassword(buildChangePwdDto("alice", "OldSecret1", "x".repeat(65), "x".repeat(65))).getCode());

        R mismatch = userService.updatePassword(buildChangePwdDto("alice", "OldSecret1", "NewSecret2", "NewSecret3"));
        assertNotEquals(0, mismatch.getCode());
        assertEquals("新密码和确认密码不匹配", mismatch.getMsg());

        // 当前密码错误
        R wrongOld = userService.updatePassword(buildChangePwdDto("alice", "WrongOld1", "NewSecret2", "NewSecret2"));
        assertNotEquals(0, wrongOld.getCode());
        assertEquals("当前密码错误", wrongOld.getMsg());

        // 新旧密码相同必须拒绝
        R samePwd = userService.updatePassword(buildChangePwdDto("alice_new", "OldSecret1", "OldSecret1", "OldSecret1"));
        assertNotEquals(0, samePwd.getCode());
        assertEquals("新密码不能与当前密码相同", samePwd.getMsg());

        verify(userService, never()).updateById(any(User.class));
        verifyNoInteractions(auditLogStore);
    }

    @Test
    void updatePassword_blocksDisabledOrUnauthenticatedAccountFromBypassingStatus() {
        // 1. 未携带登录令牌 -> 优雅返回未登录，不抛 NPE
        RequestContextHolder.resetRequestAttributes();
        R unauth = userService.updatePassword(buildChangePwdDto("alice", "OldSecret1", "NewSecret2", "NewSecret2"));
        assertNotEquals(0, unauth.getCode());
        assertEquals("用户未登录或token无效", unauth.getMsg());

        // 2. 账号已被停用(status=0)，即使持有旧有效 JWT 且输入正确原密码也必须拦截
        User disabledUser = sampleUser(15L, "banned_user", "OldSecret1", 1, 0);
        bindAuthenticatedRequest(disabledUser, "198.51.100.55");
        doReturn(disabledUser).when(userService).getById(15);

        R blocked = userService.updatePassword(
                buildChangePwdDto("banned_new", "OldSecret1", "NewSecret2", "NewSecret2"));
        assertNotEquals(0, blocked.getCode());
        assertTrue(blocked.getMsg().contains("停用"), blocked.getMsg());
        verify(userService, never()).updateById(any(User.class));
        verifyNoInteractions(auditLogStore);
    }

    @Test
    void updatePassword_successUpdatesCredentialsHandlesDuplicateAndRecordsSanitizedAudit() {
        User user = sampleUser(22L, "admin_user", "admin_user", 0, 1);
        bindAuthenticatedRequest(user, "198.51.100.88, 10.0.0.1");
        doReturn(user).when(userService).getById(22);

        // 1. 新用户名已被占用 -> 拒绝
        doReturn(sampleUser(99L, "taken_name", "x", 1, 1)).when(userService).getOne(any(QueryWrapper.class));
        R dup = userService.updatePassword(
                buildChangePwdDto("taken_name", "admin_user", "Str0ng!NewPass", "Str0ng!NewPass"));
        assertNotEquals(0, dup.getCode());
        assertEquals("用户名已被其他用户使用", dup.getMsg());

        // 2. 并发唯一索引冲突 -> 优雅返回用户名已被占用且不记成功审计
        doReturn(null).when(userService).getOne(any(QueryWrapper.class));
        doThrow(new DuplicateKeyException("uk_user")).when(userService).updateById(any(User.class));
        R raceDup = userService.updatePassword(
                buildChangePwdDto("race_admin", "admin_user", "Str0ng!NewPass", "Str0ng!NewPass"));
        assertNotEquals(0, raceDup.getCode());
        assertEquals("用户名已被其他用户使用", raceDup.getMsg());
        verifyNoInteractions(auditLogStore);

        // 3. 正常修改用户名与 32 位高强度密码 -> 成功更新并写入不含任何明文密码/哈希的审计事件
        String longStrongPwd = "Cat#2026_SuperStrong_Password_32";
        doReturn(true).when(userService).updateById(any(User.class));
        R ok = userService.updatePassword(
                buildChangePwdDto("  cat_master  ", "admin_user", longStrongPwd, longStrongPwd));
        assertEquals(0, ok.getCode());
        verify(userService, atLeastOnce()).updateById(argThat(u ->
                Long.valueOf(22L).equals(u.getId())
                        && "cat_master".equals(u.getUser())
                        && Md5Util.md5(longStrongPwd).equals(u.getPwd())
        ));
        verify(auditLogStore).recordPasswordChange(22L, "admin_user", "cat_master", 0, "198.51.100.88");
    }
}
