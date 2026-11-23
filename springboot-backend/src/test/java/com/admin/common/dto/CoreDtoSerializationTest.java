package com.admin.common.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 核心 DTO（GostDto / FlowDto / LoginDto / GostConfigDto）序列化与边界测试
 */
class CoreDtoSerializationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void gostDtoSerializesAndDeserializesWithDataAndNullFields() throws Exception {
        GostDto dto = new GostDto();
        dto.setCode(0);
        dto.setMsg("ok");
        dto.setData(Map.of("service", "svc-1", "port", 8080));

        String json = objectMapper.writeValueAsString(dto);
        GostDto restored = objectMapper.readValue(json, GostDto.class);

        assertEquals(0, restored.getCode());
        assertEquals("ok", restored.getMsg());
        assertInstanceOf(Map.class, restored.getData());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) restored.getData();
        assertEquals("svc-1", data.get("service"));
        assertEquals(8080, data.get("port"));

        GostDto empty = objectMapper.readValue("{}", GostDto.class);
        assertNull(empty.getCode());
        assertNull(empty.getMsg());
        assertNull(empty.getData());
    }

    @Test
    void flowDtoHandlesLargeTrafficCountersAndMissingFields() throws Exception {
        FlowDto dto = new FlowDto();
        dto.setN("101_1_0");
        dto.setU(10_737_418_240L);
        dto.setD(21_474_836_480L);

        String json = objectMapper.writeValueAsString(dto);
        FlowDto restored = objectMapper.readValue(json, FlowDto.class);

        assertEquals("101_1_0", restored.getN());
        assertEquals(10_737_418_240L, restored.getU());
        assertEquals(21_474_836_480L, restored.getD());

        FlowDto partial = objectMapper.readValue("{\"n\":\"202_0_1\"}", FlowDto.class);
        assertEquals("202_0_1", partial.getN());
        assertNull(partial.getU());
        assertNull(partial.getD());
    }

    @Test
    void loginDtoHandlesOptionalCaptchaId() throws Exception {
        LoginDto dto = new LoginDto();
        dto.setUsername("admin");
        dto.setPassword("secret");
        dto.setCaptchaId("cap-123");

        String json = objectMapper.writeValueAsString(dto);
        LoginDto restored = objectMapper.readValue(json, LoginDto.class);

        assertEquals("admin", restored.getUsername());
        assertEquals("secret", restored.getPassword());
        assertEquals("cap-123", restored.getCaptchaId());

        LoginDto withoutCaptcha = objectMapper.readValue(
                "{\"username\":\"u1\",\"password\":\"p1\"}", LoginDto.class);
        assertEquals("u1", withoutCaptcha.getUsername());
        assertEquals("p1", withoutCaptcha.getPassword());
        assertNull(withoutCaptcha.getCaptchaId());
    }

    @Test
    void gostConfigDtoHandlesNestedConfigItemsAndEmptyLists() throws Exception {
        ConfigItem limiter = new ConfigItem();
        limiter.setName("limiter-1");
        ConfigItem chain = new ConfigItem();
        chain.setName("chain-1");
        ConfigItem service = new ConfigItem();
        service.setName("service-1");

        GostConfigDto config = new GostConfigDto();
        config.setLimiters(List.of(limiter));
        config.setChains(List.of(chain));
        config.setServices(List.of(service));

        String json = objectMapper.writeValueAsString(config);
        GostConfigDto restored = objectMapper.readValue(json, GostConfigDto.class);

        assertEquals(1, restored.getLimiters().size());
        assertEquals("limiter-1", restored.getLimiters().get(0).getName());
        assertEquals(1, restored.getChains().size());
        assertEquals("chain-1", restored.getChains().get(0).getName());
        assertEquals(1, restored.getServices().size());
        assertEquals("service-1", restored.getServices().get(0).getName());

        GostConfigDto emptyLists = objectMapper.readValue(
                "{\"limiters\":[],\"chains\":[],\"services\":[]}", GostConfigDto.class);
        assertEquals(Collections.emptyList(), emptyLists.getLimiters());
        assertEquals(Collections.emptyList(), emptyLists.getChains());
        assertEquals(Collections.emptyList(), emptyLists.getServices());
    }
}
