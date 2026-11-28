package com.admin.service.impl;

import com.admin.common.lang.R;
import com.admin.entity.ViteConfig;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * ViteConfigServiceImpl 核心查询、参数校验、新增/更新及批量更新单元测试
 */
@ExtendWith(MockitoExtension.class)
class ViteConfigServiceImplTest {

    private ViteConfigServiceImpl viteConfigService;

    @BeforeEach
    void setUp() {
        viteConfigService = Mockito.spy(new ViteConfigServiceImpl());
    }

    private ViteConfig sampleConfig(Long id, String name, String value) {
        ViteConfig c = new ViteConfig();
        c.setId(id);
        c.setName(name);
        c.setValue(value);
        c.setTime(1000L);
        return c;
    }

    @Test
    @SuppressWarnings("unchecked")
    void getConfigs_returnsKeyValueMap() {
        doReturn(Arrays.asList(
                sampleConfig(1L, "app_name", "FluxPanel"),
                sampleConfig(2L, "captcha_enabled", "true")
        )).when(viteConfigService).list();

        R res = viteConfigService.getConfigs();

        assertEquals(0, res.getCode());
        Map<String, String> data = (Map<String, String>) res.getData();
        assertEquals(2, data.size());
        assertEquals("FluxPanel", data.get("app_name"));
        assertEquals("true", data.get("captcha_enabled"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void getConfigs_emptyList_returnsEmptyMap() {
        doReturn(Collections.emptyList()).when(viteConfigService).list();

        R res = viteConfigService.getConfigs();

        assertEquals(0, res.getCode());
        Map<String, String> data = (Map<String, String>) res.getData();
        assertTrue(data.isEmpty());
    }

    @Test
    void getConfigByName_nullOrBlankName_returnsError() {
        R nullRes = viteConfigService.getConfigByName(null);
        assertNotEquals(0, nullRes.getCode());
        assertEquals("配置名称不能为空", nullRes.getMsg());

        R blankRes = viteConfigService.getConfigByName("   ");
        assertNotEquals(0, blankRes.getCode());
        assertEquals("配置名称不能为空", blankRes.getMsg());

        verify(viteConfigService, never()).getOne(any());
    }

    @Test
    void getConfigByName_notFound_returnsError() {
        doReturn(null).when(viteConfigService).getOne(any(QueryWrapper.class));

        R res = viteConfigService.getConfigByName("unknown_key");

        assertNotEquals(0, res.getCode());
        assertEquals("配置不存在", res.getMsg());
    }

    @Test
    void getConfigByName_found_returnsEntity() {
        ViteConfig existing = sampleConfig(10L, "app_name", "Flux");
        doReturn(existing).when(viteConfigService).getOne(any(QueryWrapper.class));

        R res = viteConfigService.getConfigByName("app_name");

        assertEquals(0, res.getCode());
        assertSame(existing, res.getData());
    }

    @Test
    void updateConfig_nullOrBlankNameOrValue_returnsError() {
        R nullName = viteConfigService.updateConfig(null, "val");
        assertNotEquals(0, nullName.getCode());
        assertEquals("配置名称不能为空", nullName.getMsg());

        R blankName = viteConfigService.updateConfig("  ", "val");
        assertNotEquals(0, blankName.getCode());
        assertEquals("配置名称不能为空", blankName.getMsg());

        R nullVal = viteConfigService.updateConfig("app_name", null);
        assertNotEquals(0, nullVal.getCode());
        assertEquals("配置值不能为空", nullVal.getMsg());

        R blankVal = viteConfigService.updateConfig("app_name", "   ");
        assertNotEquals(0, blankVal.getCode());
        assertEquals("配置值不能为空", blankVal.getMsg());

        verify(viteConfigService, never()).save(any());
        verify(viteConfigService, never()).updateById(any());
    }

    @Test
    void updateConfig_createsNewWhenNotExisting() {
        doReturn(null).when(viteConfigService).getOne(any(QueryWrapper.class));
        doReturn(true).when(viteConfigService).save(any(ViteConfig.class));

        R res = viteConfigService.updateConfig("app_name", "NewPanel");

        assertEquals(0, res.getCode());
        ArgumentCaptor<ViteConfig> captor = ArgumentCaptor.forClass(ViteConfig.class);
        verify(viteConfigService).save(captor.capture());
        verify(viteConfigService, never()).updateById(any());
        assertEquals("app_name", captor.getValue().getName());
        assertEquals("NewPanel", captor.getValue().getValue());
        assertNotNull(captor.getValue().getTime());
    }

    @Test
    void updateConfig_updatesExistingWhenFound() {
        ViteConfig existing = sampleConfig(5L, "app_name", "OldPanel");
        doReturn(existing).when(viteConfigService).getOne(any(QueryWrapper.class));
        doReturn(true).when(viteConfigService).updateById(any(ViteConfig.class));

        R res = viteConfigService.updateConfig("app_name", "UpdatedPanel");

        assertEquals(0, res.getCode());
        verify(viteConfigService).updateById(existing);
        verify(viteConfigService, never()).save(any());
        assertEquals("UpdatedPanel", existing.getValue());
        assertTrue(existing.getTime() > 1000L);
    }

    @Test
    void updateConfig_exception_returnsErrorResponse() {
        doThrow(new RuntimeException("DB write error")).when(viteConfigService).getOne(any(QueryWrapper.class));

        R res = viteConfigService.updateConfig("app_name", "Val");

        assertNotEquals(0, res.getCode());
        assertTrue(res.getMsg().contains("配置更新失败"));
        assertTrue(res.getMsg().contains("DB write error"));
    }

    @Test
    void updateConfigs_nullOrEmptyMap_returnsError() {
        R nullRes = viteConfigService.updateConfigs(null);
        assertNotEquals(0, nullRes.getCode());
        assertEquals("配置数据不能为空", nullRes.getMsg());

        R emptyRes = viteConfigService.updateConfigs(Collections.emptyMap());
        assertNotEquals(0, emptyRes.getCode());
        assertEquals("配置数据不能为空", emptyRes.getMsg());
    }

    @Test
    void updateConfigs_skipsBlankKeysAndUpdatesOrCreatesValidEntries() {
        ViteConfig existing = sampleConfig(1L, "app_name", "Old");
        doReturn(existing).doReturn(null).when(viteConfigService).getOne(any(QueryWrapper.class));
        doReturn(true).when(viteConfigService).updateById(any(ViteConfig.class));
        doReturn(true).when(viteConfigService).save(any(ViteConfig.class));

        Map<String, String> batch = new LinkedHashMap<>();
        batch.put("   ", "ignored");
        batch.put("app_name", "NewName");
        batch.put("captcha_type", "SLIDER");

        R res = viteConfigService.updateConfigs(batch);

        assertEquals(0, res.getCode());
        verify(viteConfigService, times(1)).updateById(existing);
        assertEquals("NewName", existing.getValue());
        ArgumentCaptor<ViteConfig> savedCaptor = ArgumentCaptor.forClass(ViteConfig.class);
        verify(viteConfigService, times(1)).save(savedCaptor.capture());
        assertEquals("captcha_type", savedCaptor.getValue().getName());
        assertEquals("SLIDER", savedCaptor.getValue().getValue());
    }

    @Test
    void updateConfigs_exception_returnsErrorResponse() {
        doThrow(new RuntimeException("batch DB failure")).when(viteConfigService).getOne(any(QueryWrapper.class));

        Map<String, String> batch = Collections.singletonMap("app_name", "Panel");
        R res = viteConfigService.updateConfigs(batch);

        assertNotEquals(0, res.getCode());
        assertTrue(res.getMsg().contains("配置更新失败"));
        assertTrue(res.getMsg().contains("batch DB failure"));
    }

    @Test
    void getConfigByName_registerInviteRequired_returnsBooleanFlagWithoutLeakingCode() {
        // 1. 未配置 -> "false"
        doReturn(null).when(viteConfigService).getOne(any(QueryWrapper.class));
        R r1 = viteConfigService.getConfigByName("register_invite_required");
        assertEquals(0, r1.getCode());
        ViteConfig c1 = (ViteConfig) r1.getData();
        assertEquals("register_invite_required", c1.getName());
        assertEquals("false", c1.getValue());

        // 2. 仅空白与逗号 -> "false"
        doReturn(sampleConfig(2L, "register_invite_code", " ,  , \t ")).when(viteConfigService).getOne(any(QueryWrapper.class));
        R r2 = viteConfigService.getConfigByName("register_invite_required");
        assertEquals(0, r2.getCode());
        assertEquals("false", ((ViteConfig) r2.getData()).getValue());

        // 3. 含有效邀请码 -> "true"，且对象中绝不携带邀请码原文
        doReturn(sampleConfig(3L, "register_invite_code", " CAT_SECRET_01 , CAT_SECRET_02 "))
                .when(viteConfigService).getOne(any(QueryWrapper.class));
        R r3 = viteConfigService.getConfigByName("register_invite_required");
        assertEquals(0, r3.getCode());
        ViteConfig c3 = (ViteConfig) r3.getData();
        assertEquals("true", c3.getValue());
        assertFalse(String.valueOf(c3).contains("CAT_SECRET"));
    }

    @Test
    void updateConfigs_validatesRegisterQuotaRangesAndNormalizesInviteCodes() {
        // 中文逗号拦截
        Map<String, String> cnComma = Collections.singletonMap("register_invite_code", "CODE1，CODE2");
        R badComma = viteConfigService.updateConfigs(cnComma);
        assertNotEquals(0, badComma.getCode());
        assertTrue(badComma.getMsg().contains("英文半角逗号"));

        // 非法重置日拦截
        Map<String, String> badReset = Collections.singletonMap("register_default_flow_reset_time", "32");
        assertNotEquals(0, viteConfigService.updateConfigs(badReset).getCode());

        // 非法有效期拦截
        Map<String, String> badExp = Collections.singletonMap("register_default_exp_days", "0");
        assertNotEquals(0, viteConfigService.updateConfigs(badExp).getCode());

        // 合法配置并规范化邀请码（去空项、去空白、去重复项）与前导零
        doReturn(null).when(viteConfigService).getOne(any(QueryWrapper.class));
        doReturn(true).when(viteConfigService).save(any(ViteConfig.class));

        Map<String, String> validBatch = new LinkedHashMap<>();
        validBatch.put("register_invite_code", " VIP_1 , , VIP_2 , VIP_1 , \u3000VIP_3\u200B ");
        validBatch.put("register_default_flow", " 0100 ");
        validBatch.put("register_default_num", "10");
        validBatch.put("register_default_exp_days", "365");
        validBatch.put("register_default_flow_reset_time", "0");

        R ok = viteConfigService.updateConfigs(validBatch);
        assertEquals(0, ok.getCode());

        ArgumentCaptor<ViteConfig> captor = ArgumentCaptor.forClass(ViteConfig.class);
        verify(viteConfigService, times(5)).save(captor.capture());
        ViteConfig savedInvite = captor.getAllValues().stream()
                .filter(c -> "register_invite_code".equals(c.getName()))
                .findFirst()
                .orElseThrow(AssertionError::new);
        assertEquals("VIP_1,VIP_2,VIP_3", savedInvite.getValue());

        ViteConfig savedFlow = captor.getAllValues().stream()
                .filter(c -> "register_default_flow".equals(c.getName()))
                .findFirst()
                .orElseThrow(AssertionError::new);
        assertEquals("100", savedFlow.getValue());
    }

    @Test
    void updateConfig_allowsClearingOptionalRegisterQuotaKeysAndRejectsInvalidSingleUpdate() {
        ViteConfig existingFlow = sampleConfig(11L, "register_default_flow", "50");
        doReturn(existingFlow).when(viteConfigService).getOne(any(QueryWrapper.class));
        doReturn(true).when(viteConfigService).updateById(any(ViteConfig.class));

        R cleared = viteConfigService.updateConfig("register_default_flow", "   ");
        assertEquals(0, cleared.getCode());
        assertEquals("", existingFlow.getValue());

        R badNegative = viteConfigService.updateConfig("register_default_flow", "-10");
        assertNotEquals(0, badNegative.getCode());

        StringBuilder over128 = new StringBuilder();
        for (int i = 0; i < 129; i++) {
            over128.append('X');
        }
        R badSingleInvite = viteConfigService.updateConfig("register_invite_code", over128.toString());
        assertNotEquals(0, badSingleInvite.getCode());
        assertTrue(badSingleInvite.getMsg().contains("128"));
    }
}
