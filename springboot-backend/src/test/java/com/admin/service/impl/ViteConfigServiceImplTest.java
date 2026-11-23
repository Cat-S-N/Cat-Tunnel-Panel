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
}
