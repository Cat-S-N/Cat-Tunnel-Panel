package com.admin.service.impl;

import cloud.tianai.captcha.application.ImageCaptchaApplication;
import cloud.tianai.captcha.spring.plugins.secondary.SecondaryVerificationApplication;
import com.admin.common.dto.RegisterDto;
import com.admin.common.lang.R;
import com.admin.common.utils.AuditLogStore;
import com.admin.common.utils.Md5Util;
import com.admin.entity.User;
import com.admin.service.ViteConfigService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserRegisterTest {

    @Mock
    private ViteConfigService viteConfigService;

    @Mock
    private AuditLogStore auditLogStore;

    private ImageCaptchaApplication application;
    private UserServiceImpl userService;
    private Map<String, String> configStore;

    @BeforeEach
    void setUp() {
        configStore = new HashMap<>();
        application = Mockito.mock(SecondaryVerificationApplication.class);
        userService = Mockito.spy(new UserServiceImpl());
        ReflectionTestUtils.setField(userService, "viteConfigService", viteConfigService);
        ReflectionTestUtils.setField(userService, "application", application);
        ReflectionTestUtils.setField(userService, "auditLogStore", auditLogStore);
        userService.clearRegisterRateLimitForTest();

        lenient().doAnswer(inv -> configStore.get(inv.<String>getArgument(0)))
                .when(userService).getConfigValue(anyString());
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    private RegisterDto validDto(String username, String password) {
        RegisterDto dto = new RegisterDto();
        dto.setUsername(username);
        dto.setPassword(password);
        dto.setConfirmPassword(password);
        return dto;
    }

    @Test
    void register_rejectedWhenDisabledOrUnconfigured() {
        R r1 = userService.register(validDto("alice_01", "secret123"));
        assertNotEquals(0, r1.getCode());
        assertTrue(r1.getMsg().contains("未开放"));

        configStore.put("register_enabled", "false");
        R r2 = userService.register(validDto("alice_01", "secret123"));
        assertNotEquals(0, r2.getCode());
        assertTrue(r2.getMsg().contains("未开放"));
        verifyNoInteractions(auditLogStore);
    }

    @Test
    void register_successInitializesDefaultQuotaForcesUserRoleAndRecordsAuditLog() {
        configStore.put("register_enabled", "true");
        configStore.put("register_default_flow", "50");
        configStore.put("register_default_num", "8");
        configStore.put("register_default_exp_days", "15");
        configStore.put("register_default_flow_reset_time", "10");

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Forwarded-For", "198.51.100.25, 10.0.0.1");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));

        doReturn(null).when(userService).getOne(any(QueryWrapper.class));
        doAnswer(inv -> {
            User saved = inv.getArgument(0);
            saved.setId(99L);
            assertEquals("alice_01", saved.getUser());
            assertEquals(Md5Util.md5("secret123"), saved.getPwd());
            assertEquals(1, saved.getRoleId(), "必须强制普通用户角色，严禁提权");
            assertEquals(1, saved.getStatus());
            assertEquals(50L, saved.getFlow());
            assertEquals(8, saved.getNum());
            assertEquals(10L, saved.getFlowResetTime());
            assertEquals(0L, saved.getInFlow());
            assertEquals(0L, saved.getOutFlow());
            assertTrue(saved.getExpTime() > System.currentTimeMillis());
            return true;
        }).when(userService).save(any(User.class));

        R r = userService.register(validDto("  alice_01  ", "secret123"));
        assertEquals(0, r.getCode());
        verify(userService).save(any(User.class));
        verify(auditLogStore).recordUserRegister(99L, "alice_01", "198.51.100.25", false);
    }

    @Test
    void register_inviteCodeDisabledWhenBlankOrCommasOnly() {
        configStore.put("register_enabled", "true");
        configStore.put("register_invite_code", "  , ,  ");
        doReturn(null).when(userService).getOne(any(QueryWrapper.class));
        doReturn(true).when(userService).save(any(User.class));

        R r = userService.register(validDto("bob_free", "secret123"));
        assertEquals(0, r.getCode());
    }

    @Test
    void register_enforcesMultipleInviteCodesAndBlocksEmptySegmentBypass() {
        configStore.put("register_enabled", "true");
        configStore.put("register_invite_code", " VIP_ALPHA , , VIP-BETA , ");

        // 1. 未传邀请码 -> 拒绝
        R missing = userService.register(validDto("u_no_code", "secret123"));
        assertNotEquals(0, missing.getCode());
        assertTrue(missing.getMsg().contains("邀请码"));

        // 2. 空串 / 空白尝试绕过连续逗号空项 -> 必须拒绝
        RegisterDto blankAttempt = validDto("u_blank_code", "secret123");
        blankAttempt.setInviteCode("   ");
        R blankRes = userService.register(blankAttempt);
        assertNotEquals(0, blankRes.getCode());
        assertTrue(blankRes.getMsg().contains("邀请码"));

        // 3. 错误邀请码 -> 拒绝，且不查询数据库用户名（防枚举）
        RegisterDto wrongAttempt = validDto("u_wrong_code", "secret123");
        wrongAttempt.setInviteCode("VIP_GAMMA");
        R wrongRes = userService.register(wrongAttempt);
        assertNotEquals(0, wrongRes.getCode());
        assertTrue(wrongRes.getMsg().contains("邀请码"));
        verify(userService, never()).getOne(any(QueryWrapper.class));
        verifyNoInteractions(auditLogStore);

        // 4. 匹配第二个邀请码（含首尾空格） -> 通过并记录受邀审计标记
        userService.clearRegisterRateLimitForTest();
        doReturn(null).when(userService).getOne(any(QueryWrapper.class));
        doAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(105L);
            return true;
        }).when(userService).save(any(User.class));

        RegisterDto okSecond = validDto("u_invited_2", "secret123");
        okSecond.setInviteCode("  VIP-BETA  ");
        R okRes = userService.register(okSecond);
        assertEquals(0, okRes.getCode());
        verify(auditLogStore).recordUserRegister(eq(105L), eq("u_invited_2"), anyString(), eq(true));
    }

    @Test
    void register_rejectedWhenUsernameExistsOrConcurrentDuplicateKey() {
        configStore.put("register_enabled", "true");
        User existing = new User();
        existing.setId(2L);
        existing.setUser("alice_01");
        doReturn(existing).when(userService).getOne(any(QueryWrapper.class));

        R r = userService.register(validDto("alice_01", "secret123"));
        assertNotEquals(0, r.getCode());
        verify(userService, never()).save(any(User.class));

        // 并发插入唯一索引冲突优雅返回用户名已存在
        doReturn(null).when(userService).getOne(any(QueryWrapper.class));
        doThrow(new DuplicateKeyException("uk_user")).when(userService).save(any(User.class));
        R dupRes = userService.register(validDto("race_user", "secret123"));
        assertNotEquals(0, dupRes.getCode());
        assertTrue(dupRes.getMsg().contains("已存在"));
        verifyNoInteractions(auditLogStore);
    }

    @Test
    void register_rejectedOnInvalidParamsAndOutOfRangeConfigFallsBackSafely() {
        configStore.put("register_enabled", "true");

        R badUser = userService.register(validDto("ab", "secret123"));
        assertNotEquals(0, badUser.getCode());

        R badPass = userService.register(validDto("alice_01", "123"));
        assertNotEquals(0, badPass.getCode());

        R blankPass = userService.register(validDto("alice_01", "        "));
        assertNotEquals(0, blankPass.getCode());

        RegisterDto mismatch = validDto("alice_01", "secret123");
        mismatch.setConfirmPassword("secret456");
        R badConfirm = userService.register(mismatch);
        assertNotEquals(0, badConfirm.getCode());
        assertTrue(badConfirm.getMsg().contains("不一致"));

        // 超大异常默认配置值安全回退，防整数溢出
        userService.clearRegisterRateLimitForTest();
        configStore.put("register_default_exp_days", "999999999999");
        configStore.put("register_default_num", "999999999");
        configStore.put("register_default_flow_reset_time", "99");
        doReturn(null).when(userService).getOne(any(QueryWrapper.class));
        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        doReturn(true).when(userService).save(userCaptor.capture());

        R ok = userService.register(validDto("safe_quota", "secret123"));
        assertEquals(0, ok.getCode());
        assertEquals(5, userCaptor.getValue().getNum());
        assertEquals(1L, userCaptor.getValue().getFlowResetTime());
        assertTrue(userCaptor.getValue().getExpTime() > System.currentTimeMillis());

        // 负数、零天有效期或脏字符配置同样安全回退默认值，且零宽字符包裹的邀请码可正常匹配
        userService.clearRegisterRateLimitForTest();
        configStore.put("register_invite_code", "  CAT999 , CAT999 ");
        configStore.put("register_default_flow", "-50");
        configStore.put("register_default_num", "-3");
        configStore.put("register_default_exp_days", "0");
        configStore.put("register_default_flow_reset_time", "-1");

        RegisterDto negFallbackDto = validDto("\u200Bneg_safe\u3000", "secret123");
        negFallbackDto.setInviteCode("\u200BCAT999\uFEFF");
        R okNeg = userService.register(negFallbackDto);
        assertEquals(0, okNeg.getCode());
        assertEquals("neg_safe", userCaptor.getValue().getUser());
        assertEquals(10L, userCaptor.getValue().getFlow());
        assertEquals(5, userCaptor.getValue().getNum());
        assertEquals(1L, userCaptor.getValue().getFlowResetTime());
        assertTrue(userCaptor.getValue().getExpTime() > System.currentTimeMillis() + 29L * 86400_000L);
    }

    @Test
    void register_enforcesCaptchaWhenEnabled() {
        configStore.put("register_enabled", "true");
        configStore.put("captcha_enabled", "true");

        RegisterDto noCaptcha = validDto("alice_01", "secret123");
        R r1 = userService.register(noCaptcha);
        assertNotEquals(0, r1.getCode());
        assertTrue(r1.getMsg().contains("验证码"));

        when(((SecondaryVerificationApplication) application).secondaryVerification("bad-id")).thenReturn(false);
        noCaptcha.setCaptchaId("bad-id");
        R r2 = userService.register(noCaptcha);
        assertNotEquals(0, r2.getCode());

        when(((SecondaryVerificationApplication) application).secondaryVerification("ok-id")).thenReturn(true);
        doReturn(null).when(userService).getOne(any(QueryWrapper.class));
        doReturn(true).when(userService).save(any(User.class));
        noCaptcha.setCaptchaId("ok-id");
        R r3 = userService.register(noCaptcha);
        assertEquals(0, r3.getCode());
    }

    @Test
    void register_rateLimitsAndEvictsExpiredAndCapsMaxTrackedIps() {
        configStore.put("register_enabled", "true");
        doReturn(null).when(userService).getOne(any(QueryWrapper.class));
        doReturn(true).when(userService).save(any(User.class));

        for (int i = 0; i < 5; i++) {
            R ok = userService.register(validDto("user_" + i, "secret123"));
            assertEquals(0, ok.getCode());
        }
        R limited = userService.register(validDto("user_6", "secret123"));
        assertNotEquals(0, limited.getCode());
        assertTrue(limited.getMsg().contains("频繁"));

        // 验证过期窗口清理与容量上限保护（防内存泄漏）
        userService.clearRegisterRateLimitForTest();
        long t0 = 1_000_000L;
        int cap = 10;
        for (int i = 0; i < 25; i++) {
            assertFalse(userService.isRegisterRateLimited("10.0.0." + i, t0 + i, cap));
            assertTrue(userService.getRegisterRateTrackedIpCount() <= cap, "跟踪 IP 数量不能超过容量上限");
        }
        assertEquals(cap, userService.getRegisterRateTrackedIpCount());

        // 超过 10 分钟窗口后触发清理，旧过期条目应全部被回收，仅剩新请求 IP
        long afterWindow = t0 + UserServiceImpl.REGISTER_RATE_WINDOW_MS + 5_000L;
        assertFalse(userService.isRegisterRateLimited("192.168.1.1", afterWindow, cap));
        // 再累计达到清理阈值或容量检查后，过期 IP 全部释放
        for (int i = 0; i < 64; i++) {
            userService.isRegisterRateLimited("192.168.1.1", afterWindow + 1, cap);
        }
        assertEquals(1, userService.getRegisterRateTrackedIpCount(), "所有过期 IP 条目应被彻底清理释放");
    }
}
