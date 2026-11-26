package com.admin.service.impl;

import cloud.tianai.captcha.application.ImageCaptchaApplication;
import cloud.tianai.captcha.spring.plugins.secondary.SecondaryVerificationApplication;
import com.admin.common.dto.RegisterDto;
import com.admin.common.lang.R;
import com.admin.common.utils.Md5Util;
import com.admin.entity.User;
import com.admin.entity.ViteConfig;
import com.admin.service.ViteConfigService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserRegisterTest {

    @Mock
    private ViteConfigService viteConfigService;

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
        userService.clearRegisterRateLimitForTest();

        lenient().doAnswer(inv -> configStore.get(inv.<String>getArgument(0)))
                .when(userService).getConfigValue(anyString());
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
    }

    @Test
    void register_successInitializesDefaultQuotaAndForcesUserRole() {
        configStore.put("register_enabled", "true");
        configStore.put("register_default_flow", "50");
        configStore.put("register_default_num", "8");
        configStore.put("register_default_exp_days", "15");
        configStore.put("register_default_flow_reset_time", "10");

        doReturn(null).when(userService).getOne(any(QueryWrapper.class));
        doAnswer(inv -> {
            User saved = inv.getArgument(0);
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

        R r = userService.register(validDto("alice_01", "secret123"));
        assertEquals(0, r.getCode());
        verify(userService).save(any(User.class));
    }

    @Test
    void register_rejectedWhenUsernameExists() {
        configStore.put("register_enabled", "true");
        User existing = new User();
        existing.setId(2L);
        existing.setUser("alice_01");
        doReturn(existing).when(userService).getOne(any(QueryWrapper.class));

        R r = userService.register(validDto("alice_01", "secret123"));
        assertNotEquals(0, r.getCode());
        verify(userService, never()).save(any(User.class));
    }

    @Test
    void register_rejectedOnInvalidParams() {
        configStore.put("register_enabled", "true");

        R badUser = userService.register(validDto("ab", "secret123"));
        assertNotEquals(0, badUser.getCode());

        R badPass = userService.register(validDto("alice_01", "123"));
        assertNotEquals(0, badPass.getCode());

        RegisterDto mismatch = validDto("alice_01", "secret123");
        mismatch.setConfirmPassword("secret456");
        R badConfirm = userService.register(mismatch);
        assertNotEquals(0, badConfirm.getCode());
        assertTrue(badConfirm.getMsg().contains("不一致"));
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
    void register_rateLimitsRepeatedRequestsFromSameIp() {
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
    }
}
