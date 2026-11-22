package com.admin.common.utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 订阅拉取访问记录（防订阅泄露）：
 * - 在 /api/v1/open_api/sub、/clash、/quanx-source 被访问时异步落库
 * - 记录归属 user_id、node_id(0=聚合订阅)、sub_type、来访 IP、User-Agent
 * - 提供按用户/节点维度的近期拉取明细及 24 小时独立 IP 数统计
 */
@Slf4j
@Component
public class SubAccessStore {

    private static final long RETENTION_MS = 30L * 24 * 3600 * 1000;
    private static final long DAY_MS = 24L * 3600 * 1000;
    private static final long RETRY_INTERVAL_MS = 30_000L;

    private static final String DDL = "CREATE TABLE IF NOT EXISTS `sub_access_log` ("
            + "`id` BIGINT NOT NULL AUTO_INCREMENT,"
            + "`user_id` BIGINT NOT NULL COMMENT '车友ID',"
            + "`node_id` BIGINT NOT NULL DEFAULT 0 COMMENT '线路节点ID(0=聚合订阅)',"
            + "`sub_type` VARCHAR(16) NOT NULL COMMENT 'sub / clash / quanx',"
            + "`ip` VARCHAR(64) NOT NULL COMMENT '请求来源IP',"
            + "`user_agent` VARCHAR(255) NOT NULL DEFAULT '' COMMENT '客户端UA',"
            + "`created_time` BIGINT NOT NULL COMMENT '访问时间戳(ms)',"
            + "PRIMARY KEY (`id`),"
            + "KEY `idx_sub_log_user_time` (`user_id`, `created_time`),"
            + "KEY `idx_sub_log_time` (`created_time`)"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订阅拉取记录'";

    @Autowired(required = false)
    private JdbcTemplate jdbc;

    private volatile boolean available = false;
    private volatile long lastTry = 0L;
    private final AtomicInteger insertCounter = new AtomicInteger(0);

    private final ExecutorService executor = new ThreadPoolExecutor(
            1, 1, 60L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(512),
            r -> {
                Thread t = new Thread(r, "sub-access-log");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.DiscardOldestPolicy()
    );

    @PostConstruct
    public void init() {
        ensureTable();
    }

    private synchronized boolean ensureTable() {
        if (available) {
            return true;
        }
        if (jdbc == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - lastTry < RETRY_INTERVAL_MS && lastTry > 0) {
            return false;
        }
        lastTry = now;
        try {
            jdbc.execute(DDL);
            available = true;
            return true;
        } catch (Exception e) {
            log.warn("初始化 sub_access_log 表失败: {}", e.getMessage());
            return false;
        }
    }

    /** 异步记录订阅拉取（必须在请求线程先取出 IP 和 UA，不能把 HttpServletRequest 传进异步线程） */
    public void record(String token, String subType, HttpServletRequest request) {
        if (jdbc == null || token == null) {
            return;
        }
        String cleanToken = token.trim();
        if (cleanToken.isEmpty() || cleanToken.length() > 128) {
            return;
        }
        String ip = extractClientIp(request);
        String ua = request != null ? safeTruncate(request.getHeader("User-Agent"), 250) : "";
        String type = safeTruncate(subType == null ? "sub" : subType, 16);
        long now = System.currentTimeMillis();

        executor.execute(() -> writeRecord(cleanToken, type, ip, ua, now));
    }

    private void writeRecord(String token, String type, String ip, String ua, long now) {
        if (!ensureTable()) {
            return;
        }
        try {
            long[] target = resolveTokenOwner(token);
            if (target == null) {
                return;
            }
            jdbc.update("INSERT INTO `sub_access_log` (`user_id`, `node_id`, `sub_type`, `ip`, `user_agent`, `created_time`) VALUES (?, ?, ?, ?, ?, ?)",
                    target[0], target[1], type, ip, ua, now);
            if (insertCounter.incrementAndGet() % 200 == 0) {
                jdbc.update("DELETE FROM `sub_access_log` WHERE `created_time` < ?", now - RETENTION_MS);
            }
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("doesn't exist")) {
                available = false;
            }
            log.debug("记录订阅访问失败: {}", e.getMessage());
        }
    }

    /** 解析 token 归属：[userId, nodeId]，聚合订阅返回 nodeId = 0 */
    private long[] resolveTokenOwner(String token) {
        List<Long> aggUsers = jdbc.queryForList(
                "SELECT `id` FROM `user` WHERE `all_sub_token` = ? LIMIT 1", Long.class, token);
        if (!aggUsers.isEmpty() && aggUsers.get(0) != null) {
            return new long[]{aggUsers.get(0), 0L};
        }
        List<Map<String, Object>> lines = jdbc.queryForList(
                "SELECT `user_id`, `node_id` FROM `inbound_line` WHERE `sub_token` = ? LIMIT 1", token);
        if (!lines.isEmpty()) {
            Object uid = lines.get(0).get("user_id");
            Object nid = lines.get(0).get("node_id");
            if (uid instanceof Number) {
                long nodeId = (nid instanceof Number) ? ((Number) nid).longValue() : 0L;
                return new long[]{((Number) uid).longValue(), nodeId};
            }
        }
        List<Map<String, Object>> ius = jdbc.queryForList(
                "SELECT iu.`user_id`, IFNULL(i.`node_id`, 0) AS `node_id` "
                        + "FROM `inbound_user` iu LEFT JOIN `inbound` i ON i.`id` = iu.`inbound_id` "
                        + "WHERE iu.`sub_token` = ? LIMIT 1", token);
        if (!ius.isEmpty()) {
            Object uid = ius.get(0).get("user_id");
            Object nid = ius.get(0).get("node_id");
            if (uid instanceof Number) {
                long nodeId = (nid instanceof Number) ? ((Number) nid).longValue() : 0L;
                return new long[]{((Number) uid).longValue(), nodeId};
            }
        }
        return null;
    }

    /** 校验某个用户是否在指定节点上有入站分配（用于节点管理员鉴权） */
    public boolean userHasNode(Long userId, Long nodeId) {
        if (jdbc == null || userId == null || nodeId == null) {
            return false;
        }
        try {
            Integer cnt = jdbc.queryForObject(
                    "SELECT COUNT(1) FROM `inbound_user` iu "
                            + "JOIN `inbound` i ON i.`id` = iu.`inbound_id` "
                            + "WHERE iu.`user_id` = ? AND i.`node_id` = ?",
                    Integer.class, userId, nodeId);
            return cnt != null && cnt > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** 查询指定用户（及可选特定节点）的最近 50 条订阅拉取记录 + 24 小时汇总统计 */
    public Map<String, Object> queryByUser(Long userId, Long nodeId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pulls24h", 0L);
        result.put("uniqueIps24h", 0L);
        result.put("logs", Collections.emptyList());

        if (userId == null || !ensureTable()) {
            return result;
        }

        long since24h = System.currentTimeMillis() - DAY_MS;
        try {
            if (nodeId != null && nodeId > 0) {
                Map<String, Object> stats = jdbc.queryForMap(
                        "SELECT COUNT(*) AS pulls, COUNT(DISTINCT `ip`) AS ips "
                                + "FROM `sub_access_log` "
                                + "WHERE `user_id` = ? AND (`node_id` = ? OR `node_id` = 0) AND `created_time` >= ?",
                        userId, nodeId, since24h);
                result.put("pulls24h", toLongVal(stats.get("pulls")));
                result.put("uniqueIps24h", toLongVal(stats.get("ips")));

                List<Map<String, Object>> logs = jdbc.queryForList(
                        "SELECT l.`id`, l.`user_id` AS `userId`, l.`node_id` AS `nodeId`, "
                                + "IF(l.`node_id` = 0, '全部线路(聚合)', IFNULL(n.`name`, CONCAT('节点#', l.`node_id`))) AS `nodeName`, "
                                + "l.`sub_type` AS `subType`, l.`ip`, l.`user_agent` AS `userAgent`, l.`created_time` AS `createdTime` "
                                + "FROM `sub_access_log` l LEFT JOIN `node` n ON n.`id` = l.`node_id` "
                                + "WHERE l.`user_id` = ? AND (l.`node_id` = ? OR l.`node_id` = 0) "
                                + "ORDER BY l.`created_time` DESC, l.`id` DESC LIMIT 50",
                        userId, nodeId);
                result.put("logs", logs);
            } else {
                Map<String, Object> stats = jdbc.queryForMap(
                        "SELECT COUNT(*) AS pulls, COUNT(DISTINCT `ip`) AS ips "
                                + "FROM `sub_access_log` "
                                + "WHERE `user_id` = ? AND `created_time` >= ?",
                        userId, since24h);
                result.put("pulls24h", toLongVal(stats.get("pulls")));
                result.put("uniqueIps24h", toLongVal(stats.get("ips")));

                List<Map<String, Object>> logs = jdbc.queryForList(
                        "SELECT l.`id`, l.`user_id` AS `userId`, l.`node_id` AS `nodeId`, "
                                + "IF(l.`node_id` = 0, '全部线路(聚合)', IFNULL(n.`name`, CONCAT('节点#', l.`node_id`))) AS `nodeName`, "
                                + "l.`sub_type` AS `subType`, l.`ip`, l.`user_agent` AS `userAgent`, l.`created_time` AS `createdTime` "
                                + "FROM `sub_access_log` l LEFT JOIN `node` n ON n.`id` = l.`node_id` "
                                + "WHERE l.`user_id` = ? "
                                + "ORDER BY l.`created_time` DESC, l.`id` DESC LIMIT 50",
                        userId);
                result.put("logs", logs);
            }
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("doesn't exist")) {
                available = false;
            }
            log.warn("查询订阅访问记录失败: {}", e.getMessage());
        }
        return result;
    }

    static String extractClientIp(HttpServletRequest request) {
        if (request == null) {
            return "unknown";
        }
        String ip = firstHeaderIp(request.getHeader("X-Real-IP"));
        if (ip.isEmpty()) {
            ip = firstHeaderIp(request.getHeader("CF-Connecting-IP"));
        }
        if (ip.isEmpty()) {
            ip = firstHeaderIp(IpUtils.getIpAddr(request));
        }
        if (ip.isEmpty() && request.getRemoteAddr() != null) {
            ip = request.getRemoteAddr().trim();
        }
        return ip.isEmpty() ? "unknown" : safeTruncate(ip, 64);
    }

    static String firstHeaderIp(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.isEmpty() || "unknown".equalsIgnoreCase(s)) return "";
        int comma = s.indexOf(',');
        if (comma > 0) {
            s = s.substring(0, comma).trim();
        }
        return "unknown".equalsIgnoreCase(s) ? "" : s;
    }

    static String safeTruncate(String s, int maxLen) {
        if (s == null) return "";
        String trimmed = s.trim();
        return trimmed.length() <= maxLen ? trimmed : trimmed.substring(0, maxLen);
    }

    private static long toLongVal(Object o) {
        return (o instanceof Number) ? ((Number) o).longValue() : 0L;
    }
}
