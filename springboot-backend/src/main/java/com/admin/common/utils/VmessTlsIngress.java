package com.admin.common.utils;

import com.alibaba.fastjson.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * VMess + WebSocket 通过 443 TLS 反代入口转发时的订阅重写工具。
 */
public final class VmessTlsIngress {

    private VmessTlsIngress() {
    }

    private static boolean enabled(JSONObject cfg) {
        if (cfg == null) {
            return false;
        }
        String host = cfg.getString("tlsIngressHost");
        String prefix = cfg.getString("tlsIngressPrefix");
        return "ws".equalsIgnoreCase(cfg.getString("net"))
                && host != null && host.matches("[A-Za-z0-9.-]+")
                && prefix != null && prefix.matches("/[A-Za-z0-9_-]+")
                && cfg.getString("path") != null && cfg.getString("path").startsWith("/");
    }

    public static void apply(Map<String, Object> proxy, JSONObject cfg, int forwardPort) {
        if (proxy == null || !enabled(cfg)) {
            return;
        }
        String host = cfg.getString("tlsIngressHost");
        proxy.put("server", host);
        proxy.put("port", 443);
        proxy.put("tls", true);
        proxy.put("servername", host);
        proxy.put("skip-cert-verify", false);
        Map<String, Object> ws = new LinkedHashMap<>();
        ws.put("path", cfg.getString("tlsIngressPrefix") + "/" + forwardPort + cfg.getString("path"));
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Host", host);
        ws.put("headers", headers);
        proxy.put("ws-opts", ws);
    }

    public static String applyLink(String link, JSONObject cfg, int forwardPort) {
        if (link == null || !link.startsWith("vmess://") || !enabled(cfg)) {
            return link;
        }
        JSONObject vmess = JSONObject.parseObject(
                new String(Base64.getDecoder().decode(link.substring(8)), StandardCharsets.UTF_8));
        String host = cfg.getString("tlsIngressHost");
        vmess.put("add", host);
        vmess.put("port", "443");
        vmess.put("tls", "tls");
        vmess.put("sni", host);
        vmess.put("host", host);
        vmess.put("path", cfg.getString("tlsIngressPrefix") + "/" + forwardPort + cfg.getString("path"));
        return "vmess://" + Base64.getEncoder().encodeToString(vmess.toJSONString().getBytes(StandardCharsets.UTF_8));
    }
}
