package com.admin.service.impl;

import com.admin.common.utils.ClashUtil;
import com.admin.common.utils.SingboxUtil;
import com.admin.common.utils.SubNames;
import com.admin.entity.Forward;
import com.admin.entity.Inbound;
import com.admin.entity.InboundUser;
import com.admin.entity.Node;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SOCKS5 协议入站配置、分享链接、Clash 节点与订阅隔离测试。
 */
class SocksProtocolTest {

    @Test
    void buildSocksInboundRequiresEnabledUsers() {
        Inbound in = new Inbound();
        in.setId(1L);
        in.setTag("in-2-40123");
        in.setProtocol("socks");
        in.setListenPort(40123);

        // 无用户或全部停用时不下发，防止 sing-box 启动为无认证开放代理
        assertNull(SingboxUtil.buildInbound(in, Collections.emptyList()));

        InboundUser disabled = new InboundUser();
        disabled.setUuid("11111111-2222-3333-4444-555555555555");
        disabled.setPassword("secret1");
        disabled.setStatus(0);
        assertNull(SingboxUtil.buildInbound(in, Collections.singletonList(disabled)));

        InboundUser active = new InboundUser();
        active.setUuid("a1b2c3d4-e5f6-7890-abcd-ef1234567890");
        active.setPassword("secret2");
        active.setStatus(1);

        JSONObject cfg = SingboxUtil.buildInbound(in, Arrays.asList(disabled, active));
        assertNotNull(cfg);
        assertEquals("socks", cfg.getString("type"));
        assertEquals("in-2-40123", cfg.getString("tag"));
        assertEquals("127.0.0.1", cfg.getString("listen"));
        assertEquals(40123, cfg.getIntValue("listen_port"));

        JSONArray users = cfg.getJSONArray("users");
        assertEquals(1, users.size());
        assertEquals("a1b2c3d4e5f67890", users.getJSONObject(0).getString("username"));
        assertEquals("secret2", users.getJSONObject(0).getString("password"));
    }

    @Test
    void buildSocksLinkAndClashProxyMatchServerCredentials() {
        String uuid = "a1b2c3d4-e5f6-7890-abcd-ef1234567890";
        String password = "mypassword123";
        String expectedUser = "a1b2c3d4e5f67890";

        String v4Link = SingboxUtil.buildSocksLink(uuid, password, "203.0.113.5", 20001, "日本 SOCKS5");
        assertTrue(v4Link.startsWith("socks5://" + expectedUser + ":" + password + "@203.0.113.5:20001#"));

        String v6Link = SingboxUtil.buildSocksLink(uuid, password, "2001:db8::1", 20001, "日本 SOCKS5");
        assertTrue(v6Link.startsWith("socks5://" + expectedUser + ":" + password + "@[2001:db8::1]:20001#"));

        Map<String, Object> proxy = ClashUtil.toProxy(
                "socks", "日本 SOCKS5", "203.0.113.5", 20001,
                uuid, password, null, null, null, null);
        assertNotNull(proxy);
        assertEquals("socks5", proxy.get("type"));
        assertEquals("203.0.113.5", proxy.get("server"));
        assertEquals(20001, proxy.get("port"));
        assertEquals(expectedUser, proxy.get("username"));
        assertEquals(password, proxy.get("password"));
        assertEquals(Boolean.TRUE, proxy.get("udp"));

        assertEquals("SOCKS5", SubNames.protocolDisplayName("socks"));
    }

    @Test
    void socksIsExcludedFromAllSubscriptions() {
        Inbound in = new Inbound();
        in.setId(9L);
        in.setProtocol("socks");

        InboundUser iu = new InboundUser();
        iu.setUuid("a1b2c3d4-e5f6-7890-abcd-ef1234567890");
        iu.setPassword("mypassword123");

        Node node = new Node();
        node.setServerIp("203.0.113.5");
        node.setName("日本机");

        Forward forward = new Forward();
        forward.setInPort(20001);

        List<String> links = new ArrayList<>();
        InboundServiceImpl.addSubLinks(links, in, iu, node, forward,
                SubNames.resolve("socks", null, "日本机", null, false, false, null, null), null);
        assertTrue(links.isEmpty(), "SOCKS5 不应进入通用订阅链接");

        List<Map<String, Object>> proxies = new ArrayList<>();
        InboundServiceImpl.addClashProxies(proxies, ClashUtil.newNameSet(), in, iu, node, forward, "日本机 SOCKS5", null);
        assertTrue(proxies.isEmpty(), "SOCKS5 不应进入 Clash 订阅");
    }
}
