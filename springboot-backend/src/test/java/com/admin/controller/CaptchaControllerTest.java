package com.admin.controller;

import cloud.tianai.captcha.application.ImageCaptchaApplication;
import cloud.tianai.captcha.application.vo.CaptchaResponse;
import cloud.tianai.captcha.application.vo.ImageCaptchaVO;
import cloud.tianai.captcha.common.response.ApiResponse;
import cloud.tianai.captcha.common.response.ApiResponseStatusConstant;
import cloud.tianai.captcha.validator.common.model.dto.ImageCaptchaTrack;
import com.admin.common.dto.CaptchaVerifyDto;
import com.admin.common.lang.R;
import com.admin.entity.ViteConfig;
import com.admin.service.ViteConfigService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * CaptchaController 验证码开关检测、生成与校验单元测试
 */
@ExtendWith(MockitoExtension.class)
class CaptchaControllerTest {

    private static final Set<String> VALID_OPTIONS = new HashSet<>(
            Arrays.asList("SLIDER", "WORD_IMAGE_CLICK", "ROTATE", "CONCAT")
    );

    @Mock
    private ViteConfigService viteConfigService;

    @Mock
    private ImageCaptchaApplication application;

    private CaptchaController captchaController;

    @BeforeEach
    void setUp() {
        captchaController = new CaptchaController();
        ReflectionTestUtils.setField(captchaController, "viteConfigService", viteConfigService);
        ReflectionTestUtils.setField(captchaController, "application", application);
    }

    private ViteConfig config(String name, String value) {
        ViteConfig c = new ViteConfig();
        c.setName(name);
        c.setValue(value);
        return c;
    }

    @Test
    void check_returnsZeroWhenConfigMissingOrFalseOrOther() {
        when(viteConfigService.getOne(any(QueryWrapper.class))).thenReturn(null);
        R missing = captchaController.check();
        assertEquals(0, missing.getCode());
        assertEquals(0, missing.getData());

        when(viteConfigService.getOne(any(QueryWrapper.class))).thenReturn(config("captcha_enabled", "false"));
        R disabled = captchaController.check();
        assertEquals(0, disabled.getCode());
        assertEquals(0, disabled.getData());

        when(viteConfigService.getOne(any(QueryWrapper.class))).thenReturn(config("captcha_enabled", ""));
        R empty = captchaController.check();
        assertEquals(0, empty.getCode());
        assertEquals(0, empty.getData());
    }

    @Test
    void check_returnsOneWhenConfigIsTrue() {
        when(viteConfigService.getOne(any(QueryWrapper.class))).thenReturn(config("captcha_enabled", "true"));

        R enabled = captchaController.check();

        assertEquals(0, enabled.getCode());
        assertEquals(1, enabled.getData());
    }

    @Test
    @SuppressWarnings("unchecked")
    void genCaptcha_usesConfiguredTypeWhenFixed() {
        when(viteConfigService.getOne(any(QueryWrapper.class))).thenReturn(config("captcha_type", "ROTATE"));
        CaptchaResponse<ImageCaptchaVO> mockResp = mock(CaptchaResponse.class);
        when(application.generateCaptcha("ROTATE")).thenReturn(mockResp);

        CaptchaResponse<ImageCaptchaVO> result = captchaController.genCaptcha();

        assertSame(mockResp, result);
        verify(application).generateCaptcha("ROTATE");
    }

    @Test
    @SuppressWarnings("unchecked")
    void genCaptcha_usesRandomOptionWhenMissingOrRandom() {
        CaptchaResponse<ImageCaptchaVO> mockResp = mock(CaptchaResponse.class);
        when(application.generateCaptcha(anyString())).thenReturn(mockResp);

        // 1. 配置不存在
        when(viteConfigService.getOne(any(QueryWrapper.class))).thenReturn(null);
        assertSame(mockResp, captchaController.genCaptcha());

        // 2. 配置为 RANDOM
        when(viteConfigService.getOne(any(QueryWrapper.class))).thenReturn(config("captcha_type", "RANDOM"));
        assertSame(mockResp, captchaController.genCaptcha());

        ArgumentCaptor<String> typeCaptor = ArgumentCaptor.forClass(String.class);
        verify(application, times(2)).generateCaptcha(typeCaptor.capture());
        for (String usedType : typeCaptor.getAllValues()) {
            assertTrue(VALID_OPTIONS.contains(usedType), "Unexpected captcha type: " + usedType);
        }
    }

    @Test
    void getRandomOption_alwaysReturnsSupportedType() {
        for (int i = 0; i < 20; i++) {
            assertTrue(VALID_OPTIONS.contains(CaptchaController.getRandomOption()));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void verify_success_returnsValidTokenWithId() {
        CaptchaVerifyDto dto = new CaptchaVerifyDto();
        dto.setId("captcha-id-123");
        ImageCaptchaTrack track = new ImageCaptchaTrack();
        dto.setData(track);

        doReturn(ApiResponse.ofSuccess()).when(application).matching("captcha-id-123", track);

        ApiResponse<?> res = captchaController.verify(dto);

        assertTrue(res.isSuccess());
        Map<String, Object> data = (Map<String, Object>) res.getData();
        assertNotNull(data);
        assertEquals("captcha-id-123", data.get("validToken"));
    }

    @Test
    void verify_failure_returnsOriginalFailureResponse() {
        CaptchaVerifyDto dto = new CaptchaVerifyDto();
        dto.setId("captcha-id-bad");
        ImageCaptchaTrack track = new ImageCaptchaTrack();
        dto.setData(track);

        ApiResponse<?> failResp = ApiResponse.ofMessage(ApiResponseStatusConstant.NOT_VALID_PARAM);
        doReturn(failResp).when(application).matching("captcha-id-bad", track);

        ApiResponse<?> res = captchaController.verify(dto);

        assertFalse(res.isSuccess());
        assertSame(failResp, res);
    }
}
