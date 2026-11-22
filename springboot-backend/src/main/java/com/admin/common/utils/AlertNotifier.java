package com.admin.common.utils;

import com.admin.common.lang.R;
import com.alibaba.fastjson2.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 实时运维告警通知器（Telegram Bot + Webhook）：
 * 1. 监控节点上下线状态转换（首次扫描仅建立基线，避免重启误报）
 * 2. 监控用户线路 status 1 -> 0（到期 / 超额停用）转换
 * 3. 提供「网站配置 -> 测试告警」即时校验能力
 */
@Slf4j
@Component
public class AlertNotifier {

    @Autowired(required = false)
    private JdbcTemplate jdbc;

    private final ConcurrentHashMap<Long, Integer> lastNodeStatus = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Long> nodeOfflineSince = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Integer> lastLineStatus = new ConcurrentHashMap<>();
    private volatile boolean initialized = false;

    private final ExecutorService executor = new ThreadPoolExecutor(
            1, 1, 60L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(128),
            r -> {
                Thread t = new Thread(r, "alert-notifier");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.DiscardOldestPolicy()
    );

    /** 每 30 秒检查一次节点在线状态与线路状态变化 */
    @Scheduled(fixedDelay = 30_000, initialDelay = 20_000)
    public void checkStatusTransitions() {
        if (jdbc == null) {
            return;
        }
        try {
            Map<String, String> cfg = loadAlertConfig();
            boolean alertOn = "true".equalsIgnoreCase(cfg.get("alert_enabled")) && hasAnyTarget(cfg);
            String appName = cfg.getOrDefault("app_name", "Cat Tunnel Panel");
            long now = System.currentTimeMillis();

            // 1) 节点在线/离线状态检查
            List<Map<String, Object>> nodes = jdbc.queryForList(
                    "SELECT id, name, server_ip, ip, status FROM node WHERE status IS NOT NULL");
            for (Map<String, Object> row : nodes) {
                Long id = toLong(row.get("id"));
                if (id == null) continue;
                int cur = toInt(row.get("status"), 0);
                Integer prev = lastNodeStatus.put(id, cur);
                if (!initialized) {
                    if (cur == 0) {
                        nodeOfflineSince.putIfAbsent(id, now);
                    }
                    continue;
                }
                if (prev == null) {
                    // 运行期新接入节点仅记录初态，或若上线则记录时间
                    if (cur == 0) {
                        nodeOfflineSince.put(id, now);
                    }
                    continue;
                }
                String name = str(row.get("name"), "节点#" + id);
                String addr = firstNonEmpty(str(row.get("server_ip"), ""), str(row.get("ip"), ""));
                if (prev == 1 && cur == 0) {
                    nodeOfflineSince.put(id, now);
                    if (alertOn) {
                        notifyAsync(cfg,
                                "[" + appName + "] 🔴 节点离线告警",
                                String.format("节点：%s\n地址：%s\n时间：%s\n状态：已从在线变为离线，请检查机器网络或 gost 服务。",
                                        name, addr.isEmpty() ? "-" : addr, formatTime(now)));
                    }
                } else if (prev == 0 && cur == 1) {
                    Long downAt = nodeOfflineSince.remove(id);
                    String dur = downAt != null ? formatDuration(now - downAt) : "未知";
                    if (alertOn) {
                        notifyAsync(cfg,
                                "[" + appName + "] 🟢 节点恢复上线",
                                String.format("节点：%s\n地址：%s\n时间：%s\n离线时长：%s",
                                        name, addr.isEmpty() ? "-" : addr, formatTime(now), dur));
                    }
                }
            }

            // 2) 线路 status 1 -> 0（超额/到期停用）检查
            List<Map<String, Object>> lines = jdbc.queryForList(
                    "SELECT l.id, l.user_id, l.node_id, l.flow, l.exp_time, l.status, "
                            + "u.user AS username, n.name AS node_name "
                            + "FROM inbound_line l "
                            + "LEFT JOIN user u ON u.id = l.user_id "
                            + "LEFT JOIN node n ON n.id = l.node_id");
            for (Map<String, Object> row : lines) {
                Long id = toLong(row.get("id"));
                if (id == null) continue;
                int cur = toInt(row.get("status"), 1);
                Integer prev = lastLineStatus.put(id, cur);
                if (!initialized || prev == null) {
                    continue;
                }
                if (prev == 1 && cur == 0 && alertOn) {
                    String username = str(row.get("username"), "用户#" + row.get("user_id"));
                    String nodeName = str(row.get("node_name"), "节点#" + row.get("node_id"));
                    Long expTime = toLong(row.get("exp_time"));
                    Long flow = toLong(row.get("flow"));
                    String reason = (expTime != null && expTime > 0 && expTime <= now)
                            ? "线路已到期停用"
                            : (flow != null && flow > 0 ? "线路流量跑满或已被停用" : "线路已停用");
                    notifyAsync(cfg,
                            "[" + appName + "] ⚠️ 线路停用提醒",
                            String.format("用户：%s\n节点：%s\n原因：%s\n时间：%s",
                                    username, nodeName, reason, formatTime(now)));
                }
            }

            initialized = true;
        } catch (Exception e) {
            log.debug("告警状态巡检跳过: {}", e.getMessage());
        }
    }

    /** 发送测试告警（支持优先使用前端传入尚未保存的配置参数） */
    public R sendTestAlert(Map<String, String> overrides) {
        Map<String, String> cfg = jdbc != null ? loadAlertConfig() : new HashMap<>();
        if (overrides != null) {
            for (String key : new String[]{"tg_bot_token", "tg_chat_id", "webhook_url"}) {
                if (overrides.containsKey(key) && overrides.get(key) != null) {
                    cfg.put(key, overrides.get(key).trim());
                }
            }
        }
        if (!hasAnyTarget(cfg)) {
            return R.err("请至少填写 Telegram Bot Token + Chat ID 或 Webhook 地址");
        }
        String appName = cfg.getOrDefault("app_name", "Cat Tunnel Panel");
        String title = "[" + appName + "] 🔔 测试告警通知";
        String content = "这是一条来自 " + appName + " 的测试告警消息。\n发送时间：" + formatTime(System.currentTimeMillis());

        List<String> results = dispatch(cfg, title, content);
        List<String> errors = new ArrayList<>();
        for (String r : results) {
            if (r.startsWith("ERR:")) {
                errors.add(r.substring(4));
            }
        }
        if (!errors.isEmpty()) {
            return R.err(String.join("；", errors));
        }
        return R.ok(String.join("；", results));
    }

    public void notifyAsync(String title, String content) {
        Map<String, String> cfg = loadAlertConfig();
        if (!"true".equalsIgnoreCase(cfg.get("alert_enabled")) || !hasAnyTarget(cfg)) {
            return;
        }
        notifyAsync(cfg, title, content);
    }

    private void notifyAsync(Map<String, String> cfg, String title, String content) {
        executor.execute(() -> {
            List<String> res = dispatch(cfg, title, content);
            for (String s : res) {
                if (s.startsWith("ERR:")) {
                    log.warn("发送告警失败: {}", s.substring(4));
                }
            }
        });
    }

    static boolean hasAnyTarget(Map<String, String> cfg) {
        if (cfg == null) return false;
        boolean hasTg = isNotBlank(cfg.get("tg_bot_token")) && isNotBlank(cfg.get("tg_chat_id"));
        boolean hasHook = isNotBlank(cfg.get("webhook_url"));
        return hasTg || hasHook;
    }

    static String buildWebhookBody(String url, String title, String content) {
        String fullText = title + "\n" + content;
        Map<String, Object> body = new LinkedHashMap<>();
        String lower = url.toLowerCase();
        if (lower.contains("open.feishu.cn") || lower.contains("open.larksuite.com")) {
            Map<String, String> textObj = new LinkedHashMap<>();
            textObj.put("text", fullText);
            body.put("msg_type", "text");
            body.put("content", textObj);
        } else if (lower.contains("oapi.dingtalk.com") || lower.contains("qyapi.weixin.qq.com")) {
            Map<String, String> textObj = new LinkedHashMap<>();
            textObj.put("content", fullText);
            body.put("msgtype", "text");
            body.put("text", textObj);
        } else {
            body.put("title", title);
            body.put("content", content);
            body.put("text", fullText);
            body.put("message", fullText);
            body.put("timestamp", System.currentTimeMillis());
        }
        return JSON.toJSONString(body);
    }

    private List<String> dispatch(Map<String, String> cfg, String title, String content) {
        List<String> outcomes = new ArrayList<>();
        String tgToken = trim(cfg.get("tg_bot_token"));
        String tgChat = trim(cfg.get("tg_chat_id"));
        String webhook = trim(cfg.get("webhook_url"));

        if (!tgToken.isEmpty() && !tgChat.isEmpty()) {
            try {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("chat_id", tgChat);
                payload.put("text", title + "\n" + content);
                String endpoint = "https://api.telegram.org/bot" + tgToken + "/sendMessage";
                postJson(endpoint, JSON.toJSONString(payload));
                outcomes.add("Telegram 告警已发送");
            } catch (Exception e) {
                outcomes.add("ERR:Telegram 发送失败(" + e.getMessage() + ")");
            }
        }

        if (!webhook.isEmpty()) {
            if (!webhook.startsWith("http://") && !webhook.startsWith("https://")) {
                outcomes.add("ERR:Webhook 地址必须以 http:// 或 https:// 开头");
            } else {
                try {
                    postJson(webhook, buildWebhookBody(webhook, title, content));
                    outcomes.add("Webhook 告警已发送");
                } catch (Exception e) {
                    outcomes.add("ERR:Webhook 发送失败(" + e.getMessage() + ")");
                }
            }
        }
        return outcomes;
    }

    private void postJson(String targetUrl, String jsonBody) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) java.net.URI.create(targetUrl).toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(10000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        conn.setRequestProperty("User-Agent", "Cat-Tunnel-Panel-Alert/1.0");
        byte[] bytes = jsonBody.getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(bytes);
        }
        int status = conn.getResponseCode();
        InputStream stream = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
        String resp = "";
        if (stream != null) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int n;
            while ((n = stream.read(buf)) != -1 && bos.size() < 4096) {
                bos.write(buf, 0, n);
            }
            resp = bos.toString(StandardCharsets.UTF_8);
        }
        conn.disconnect();
        if (status < 200 || status >= 300) {
            throw new IllegalStateException("HTTP " + status + (resp.isEmpty() ? "" : ": " + resp));
        }
    }

    private Map<String, String> loadAlertConfig() {
        Map<String, String> map = new HashMap<>();
        if (jdbc == null) {
            return map;
        }
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT name, value FROM vite_config WHERE name IN ('alert_enabled','tg_bot_token','tg_chat_id','webhook_url','app_name')");
            for (Map<String, Object> r : rows) {
                Object k = r.get("name");
                Object v = r.get("value");
                if (k != null && v != null) {
                    map.put(String.valueOf(k), String.valueOf(v));
                }
            }
        } catch (Exception e) {
            log.debug("读取告警配置失败: {}", e.getMessage());
        }
        return map;
    }

    static String formatDuration(long ms) {
        if (ms < 0) ms = 0;
        long sec = ms / 1000;
        if (sec < 60) return sec + " 秒";
        long min = sec / 60;
        if (min < 60) return min + " 分钟";
        long hr = min / 60;
        long remMin = min % 60;
        return hr + " 小时" + (remMin > 0 ? " " + remMin + " 分钟" : "");
    }

    private static String formatTime(long ts) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(ts));
    }

    private static boolean isNotBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static String str(Object o, String fallback) {
        if (o == null) return fallback;
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? fallback : s;
    }

    private static String firstNonEmpty(String a, String b) {
        return (a != null && !a.isEmpty()) ? a : (b == null ? "" : b);
    }

    private static Long toLong(Object o) {
        if (o == null) return null;
        if (o instanceof Number) return ((Number) o).longValue();
        try {
            return Long.parseLong(String.valueOf(o).trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static int toInt(Object o, int fallback) {
        if (o == null) return fallback;
        if (o instanceof Number) return ((Number) o).intValue();
        try {
            return Integer.parseInt(String.valueOf(o).trim());
        } catch (Exception e) {
            return fallback;
        }
    }
}
