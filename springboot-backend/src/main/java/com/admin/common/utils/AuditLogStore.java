package com.admin.common.utils;

import com.admin.common.lang.R;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * 操作审计日志持久化存储：
 * - 由 LogAspect 捕获 @LogAnnotation 控制器变更操作后异步写入 audit_log
 * - 仅记录白名单内的变更操作，跳过 list/get/online-map/心跳等高频只读请求
 * - 保留最近 90 天记录，支持关键词筛选、分页及超管清空
 */
@Slf4j
@Component
public class AuditLogStore {

    private static final long RETENTION_MS = 90L * 24 * 3600 * 1000;
    private static final long RETRY_INTERVAL_MS = 30_000L;
    static final int MAX_PAGE_SIZE = 100;
    static final int MAX_PAGE_NUMBER = 100_000;
    static final int MAX_KEYWORD_LEN = 128;

    private static final Pattern EXTRA_SECRET_PATTERN = Pattern.compile(
            "(?i)\"(tg_bot_token|tg_token|webhook_url|sub_token|subToken|token|secret|pwd|passwd|password|oldPassword|newPassword|currentPassword|confirmPassword|inviteCode|invite_code|register_invite_code)\"\\s*:\\s*\"(?:[^\"\\\\]++|\\\\.)*+\"");

    private static final Pattern LOGIN_USER_PATTERN = Pattern.compile(
            "(?i)\"(?:username|user)\"\\s*:\\s*\"([^\"\\\\]{1,64})\"");

    static final Map<String, String> ACTION_MAP;
    static {
        Map<String, String> m = new HashMap<>();
        m.put("NodeController.create", "创建节点");
        m.put("NodeController.update", "更新节点");
        m.put("NodeController.delete", "删除节点");
        m.put("NodeController.enableIpv6", "开启节点IPv6");
        m.put("NodeController.disableIpv6", "关闭节点IPv6");

        m.put("InboundController.create", "创建协议入站");
        m.put("InboundController.oneClick", "一键建协议");
        m.put("InboundController.oneClickRelay", "一键搭中转");
        m.put("InboundController.delete", "删除协议入站");
        m.put("InboundController.deleteByNode", "清空节点协议");
        m.put("InboundController.pushConfig", "下发节点配置");
        m.put("InboundController.assign", "分配单协议");
        m.put("InboundController.assignAll", "分配节点线路");
        m.put("InboundController.assignSelf", "用户自选线路");
        m.put("InboundController.unassign", "收回单个协议");
        m.put("InboundController.resetSubToken", "重置订阅链接");
        m.put("InboundController.resetSocksPassword", "重置SOCKS5密码");
        m.put("InboundController.lineStatus", "启停用户线路");
        m.put("InboundController.lineUpdate", "更新线路配额");
        m.put("InboundController.lineDelete", "删除用户线路");
        m.put("InboundController.rename", "重命名协议");
        m.put("InboundController.userNamesSave", "保存节点别名");

        m.put("LandingController.create", "创建落地");
        m.put("LandingController.update", "更新落地");
        m.put("LandingController.delete", "删除落地");

        m.put("UserController.create", "创建用户");
        m.put("UserController.update", "更新用户");
        m.put("UserController.delete", "删除用户");
        m.put("UserController.reset", "重置用户流量");
        m.put("UserController.quantumultX", "调整QX订阅权限");
        m.put("UserController.setManagedNodes", "分配节点管理权限");
        m.put("UserController.updatePassword", "修改密码");
        m.put("UserController.login", "用户登录");
        m.put("UserController.register", "用户自助注册");
        m.put("UserController.clearAuditLogs", "清空操作审计日志");

        m.put("ViteConfigController.updateConfigs", "批量更新网站配置");
        m.put("ViteConfigController.updateConfig", "更新网站配置");
        m.put("ViteConfigController.testAlert", "发送测试告警");

        m.put("MonitorController.saveSettings", "更新监控告警设置");
        m.put("MonitorController.testTelegram", "测试Telegram告警");
        m.put("MonitorController.saveMeta", "更新节点监控配置");
        m.put("MonitorController.saveTarget", "保存探测目标");
        m.put("MonitorController.deleteTarget", "删除探测目标");

        m.put("ForwardController.create", "创建转发");
        m.put("ForwardController.assignToUser", "分配转发");
        m.put("ForwardController.setClientLink", "设置客户端链接");
        m.put("ForwardController.update", "更新转发");
        m.put("ForwardController.updateForwardOrder", "更新转发排序");
        m.put("ForwardController.delete", "删除转发");
        m.put("ForwardController.forceDelete", "强制删除转发");
        m.put("ForwardController.pause", "暂停转发");
        m.put("ForwardController.resume", "恢复转发");

        m.put("TunnelController.create", "创建隧道");
        m.put("TunnelController.update", "更新隧道");
        m.put("TunnelController.delete", "删除隧道");
        m.put("TunnelController.assignUserTunnel", "分配用户隧道");
        m.put("TunnelController.removeUserTunnel", "移除用户隧道");
        m.put("TunnelController.updateUserTunnel", "更新用户隧道");

        m.put("SpeedLimitController.create", "创建限速");
        m.put("SpeedLimitController.update", "更新限速");
        m.put("SpeedLimitController.delete", "删除限速");

        ACTION_MAP = Collections.unmodifiableMap(m);
    }

    private static final String DDL = "CREATE TABLE IF NOT EXISTS `audit_log` ("
            + "`id` BIGINT NOT NULL AUTO_INCREMENT,"
            + "`user_id` BIGINT NULL COMMENT '操作人ID',"
            + "`username` VARCHAR(64) NOT NULL DEFAULT '' COMMENT '操作人用户名',"
            + "`role_label` VARCHAR(32) NOT NULL DEFAULT '' COMMENT '角色标识',"
            + "`action` VARCHAR(128) NOT NULL COMMENT '操作摘要',"
            + "`method` VARCHAR(160) NOT NULL COMMENT '控制器方法',"
            + "`ip` VARCHAR(64) NOT NULL DEFAULT '' COMMENT '操作IP',"
            + "`params` VARCHAR(1000) NOT NULL DEFAULT '' COMMENT '脱敏请求参数',"
            + "`result_code` INT NOT NULL DEFAULT 0 COMMENT '返回状态码',"
            + "`created_time` BIGINT NOT NULL COMMENT '操作时间戳(ms)',"
            + "PRIMARY KEY (`id`),"
            + "KEY `idx_audit_time` (`created_time`),"
            + "KEY `idx_audit_user` (`user_id`)"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='操作审计日志'";

    @Autowired(required = false)
    private JdbcTemplate jdbc;

    private volatile boolean available = false;
    private volatile long lastTry = 0L;
    private final AtomicInteger insertCounter = new AtomicInteger(0);

    private final ExecutorService executor = new ThreadPoolExecutor(
            1, 1, 60L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(512),
            r -> {
                Thread t = new Thread(r, "audit-log-writer");
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
            log.warn("初始化 audit_log 表失败: {}", e.getMessage());
            return false;
        }
    }

    /** 将完整的控制器方法名提取为 SimpleClass.methodName 并匹配操作说明 */
    public static String resolveAction(String controllerMethod) {
        if (controllerMethod == null) {
            return null;
        }
        int lastDot = controllerMethod.lastIndexOf('.');
        if (lastDot <= 0) {
            return ACTION_MAP.get(controllerMethod);
        }
        int prevDot = controllerMethod.lastIndexOf('.', lastDot - 1);
        String key = prevDot >= 0 ? controllerMethod.substring(prevDot + 1) : controllerMethod;
        return ACTION_MAP.get(key);
    }

    /** 屏蔽额外敏感配置项（例如 Telegram Token / Webhook URL / 邀请码 / 密码字段 / 单键配置更新对象） */
    public static String maskSensitiveParams(String params) {
        if (params == null || params.isEmpty()) {
            return "";
        }
        String redacted = com.admin.common.aop.LogRedact.redact(params);
        if (redacted == null) {
            return "";
        }
        String masked = EXTRA_SECRET_PATTERN.matcher(redacted).replaceAll("\"$1\":\"***\"");
        return masked.length() <= 950 ? masked : masked.substring(0, 950) + "...";
    }

    /** 登录请求尚未携带 JWT，从脱敏请求参数中提取登录账号名便于审计 */
    public static String extractLoginUsername(String requestParams) {
        if (requestParams == null || requestParams.isEmpty()) {
            return null;
        }
        java.util.regex.Matcher m = LOGIN_USER_PATTERN.matcher(requestParams);
        return m.find() ? m.group(1).trim() : null;
    }

    public void recordIfAuditable(String authorization, String ip, String controllerMethod, String requestParams, Object returnValue) {
        if (jdbc == null) {
            return;
        }
        String action = resolveAction(controllerMethod);
        if (action == null) {
            return;
        }

        Long uid = null;
        String username = "匿名";
        String roleLabel = "-";
        if (authorization != null && !authorization.trim().isEmpty() && !"null".equals(authorization)) {
            try {
                uid = JwtUtil.getUserIdFromToken(authorization);
                Integer roleId = JwtUtil.getRoleIdFromToken(authorization);
                String[] parts = authorization.split("\\.");
                if (parts.length == 3) {
                    String decoded = new String(java.util.Base64.getUrlDecoder().decode(parts[1]), java.nio.charset.StandardCharsets.UTF_8);
                    Map<?, ?> payload = com.alibaba.fastjson2.JSON.parseObject(decoded, Map.class);
                    Object nameObj = payload != null ? payload.get("name") : null;
                    if (nameObj != null) {
                        username = String.valueOf(nameObj);
                    }
                }
                if (roleId != null && roleId == 0) {
                    roleLabel = "超级管理员";
                } else if (roleId != null) {
                    roleLabel = "普通/节点管理员";
                }
            } catch (Exception ignored) {
            }
        }

        int resultCode = 0;
        if (returnValue instanceof R) {
            resultCode = ((R) returnValue).getCode();
        }

        String shortMethod = extractShortMethod(controllerMethod);
        if (resultCode == 0 && ("UserController.updatePassword".equals(shortMethod)
                || "UserController.register".equals(shortMethod))) {
            // 修改密码与自助注册成功事件已由 UserServiceImpl 独立记录（零密码/零邀请码接触），跳过 AOP 重复落库
            return;
        }
        if ("匿名".equals(username) && ("UserController.login".equals(shortMethod)
                || "UserController.register".equals(shortMethod))) {
            String loginUser = extractLoginUsername(requestParams);
            if (loginUser != null && !loginUser.isEmpty()) {
                username = loginUser;
            }
        }
        String cleanIp = SubAccessStore.firstHeaderIp(ip);
        if (cleanIp.isEmpty()) cleanIp = SubAccessStore.safeTruncate(ip, 64);
        String safeParams = maskSensitiveParams(requestParams);
        long now = System.currentTimeMillis();

        final Long finalUid = uid;
        final String finalUser = SubAccessStore.safeTruncate(username, 64);
        final String finalRole = SubAccessStore.safeTruncate(roleLabel, 32);
        final String finalIp = SubAccessStore.safeTruncate(cleanIp, 64);
        final int finalCode = resultCode;

        executor.execute(() -> {
            if (!ensureTable()) {
                return;
            }
            try {
                // 对持有非空 managed_nodes 的普通账号标记为「节点管理员」，避免与普通车友混淆
                String actualRole = finalRole;
                if ("普通/节点管理员".equals(finalRole) && finalUid != null) {
                    List<String> mns = jdbc.queryForList(
                            "SELECT `managed_nodes` FROM `user` WHERE `id` = ? LIMIT 1", String.class, finalUid);
                    if (!mns.isEmpty() && mns.get(0) != null && !mns.get(0).trim().isEmpty()) {
                        actualRole = "节点管理员";
                    } else {
                        actualRole = "用户";
                    }
                }
                jdbc.update("INSERT INTO `audit_log` (`user_id`, `username`, `role_label`, `action`, `method`, `ip`, `params`, `result_code`, `created_time`) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        finalUid, finalUser, actualRole, action, shortMethod, finalIp, safeParams, finalCode, now);
                if (insertCounter.incrementAndGet() % 300 == 0) {
                    jdbc.update("DELETE FROM `audit_log` WHERE `created_time` < ?", now - RETENTION_MS);
                }
            } catch (Exception e) {
                if (e.getMessage() != null && e.getMessage().contains("doesn't exist")) {
                    available = false;
                }
                log.debug("写入操作审计日志失败: {}", e.getMessage());
            }
        });
    }

    /**
     * 记录用户自助注册成功的操作审计日志（仅记录新用户ID、用户名、来源IP与是否启用邀请码，严禁接收或记录明文密码/邀请码）
     */
    public void recordUserRegister(Long userId, String username, String ip, boolean inviteUsed) {
        if (jdbc == null) {
            return;
        }
        String cleanUser = SubAccessStore.safeTruncate(username == null || username.trim().isEmpty() ? "匿名" : username.trim(), 64);
        String cleanIp = SubAccessStore.firstHeaderIp(ip);
        if (cleanIp.isEmpty()) {
            cleanIp = SubAccessStore.safeTruncate(ip == null || ip.trim().isEmpty() ? "unknown" : ip, 64);
        }
        String escapedUser = cleanUser.replace("\\", "\\\\").replace("\"", "\\\"");
        String safeParams = "{\"username\":\"" + escapedUser + "\",\"inviteUsed\":" + inviteUsed + "}";
        long now = System.currentTimeMillis();
        final String finalIp = SubAccessStore.safeTruncate(cleanIp, 64);

        executor.execute(() -> writeUserRegisterRow(userId, cleanUser, finalIp, safeParams, now));
    }

    void writeUserRegisterRow(Long userId, String cleanUser, String finalIp, String safeParams, long now) {
        if (!ensureTable()) {
            return;
        }
        try {
            jdbc.update(
                    "INSERT INTO `audit_log` (`user_id`, `username`, `role_label`, `action`, `method`, `ip`, `params`, `result_code`, `created_time`) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    userId, cleanUser, "用户", "用户自助注册", "UserController.register", finalIp, maskSensitiveParams(safeParams), 0, now);
            if (insertCounter.incrementAndGet() % 300 == 0) {
                jdbc.update("DELETE FROM `audit_log` WHERE `created_time` < ?", now - RETENTION_MS);
            }
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("doesn't exist")) {
                available = false;
            }
            log.debug("写入注册审计日志失败: {}", e.getMessage());
        }
    }

    /**
     * 记录用户修改账号密码成功的操作审计日志（仅记录用户ID、新旧用户名、角色与来源IP，严禁接收或记录任何明文密码或密码哈希）
     */
    public void recordPasswordChange(Long userId, String oldUsername, String newUsername, Integer roleId, String ip) {
        if (jdbc == null) {
            return;
        }
        String cleanOld = SubAccessStore.safeTruncate(
                oldUsername == null || oldUsername.trim().isEmpty() ? "匿名" : oldUsername.trim(), 64);
        String cleanNew = SubAccessStore.safeTruncate(
                newUsername == null || newUsername.trim().isEmpty() ? cleanOld : newUsername.trim(), 64);
        String cleanIp = SubAccessStore.firstHeaderIp(ip);
        if (cleanIp.isEmpty()) {
            cleanIp = SubAccessStore.safeTruncate(ip == null || ip.trim().isEmpty() ? "unknown" : ip, 64);
        }
        boolean usernameChanged = !java.util.Objects.equals(cleanOld, cleanNew);
        String escapedOld = cleanOld.replace("\\", "\\\\").replace("\"", "\\\"");
        String escapedNew = cleanNew.replace("\\", "\\\\").replace("\"", "\\\"");
        String safeParams = "{\"oldUsername\":\"" + escapedOld
                + "\",\"newUsername\":\"" + escapedNew
                + "\",\"usernameChanged\":" + usernameChanged + "}";
        String baseRole = Integer.valueOf(0).equals(roleId) ? "超级管理员" : "普通/节点管理员";
        long now = System.currentTimeMillis();
        final String finalIp = SubAccessStore.safeTruncate(cleanIp, 64);

        executor.execute(() -> writePasswordChangeRow(userId, cleanNew, baseRole, finalIp, safeParams, now));
    }

    void writePasswordChangeRow(Long userId, String cleanUser, String baseRole, String finalIp, String safeParams, long now) {
        if (!ensureTable()) {
            return;
        }
        try {
            String actualRole = baseRole;
            if ("普通/节点管理员".equals(baseRole) && userId != null) {
                List<String> mns = jdbc.queryForList(
                        "SELECT `managed_nodes` FROM `user` WHERE `id` = ? LIMIT 1", String.class, userId);
                if (!mns.isEmpty() && mns.get(0) != null && !mns.get(0).trim().isEmpty()) {
                    actualRole = "节点管理员";
                } else {
                    actualRole = "用户";
                }
            }
            jdbc.update(
                    "INSERT INTO `audit_log` (`user_id`, `username`, `role_label`, `action`, `method`, `ip`, `params`, `result_code`, `created_time`) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    userId, cleanUser, actualRole, "修改密码", "UserController.updatePassword", finalIp, maskSensitiveParams(safeParams), 0, now);
            if (insertCounter.incrementAndGet() % 300 == 0) {
                jdbc.update("DELETE FROM `audit_log` WHERE `created_time` < ?", now - RETENTION_MS);
            }
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("doesn't exist")) {
                available = false;
            }
            log.debug("写入修改密码审计日志失败: {}", e.getMessage());
        }
    }

    public Map<String, Object> query(int page, int size, String keyword, Long userId) {
        return query(page, size, keyword, userId, null, null);
    }

    public Map<String, Object> query(int page, int size, String keyword, Long userId, String status) {
        return query(page, size, keyword, userId, status, null);
    }

    public Map<String, Object> query(int page, int size, String keyword, Long userId, String status, String category) {
        int safePage = Math.min(MAX_PAGE_NUMBER, Math.max(1, page));
        int safeSize = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
        int offset = (safePage - 1) * safeSize;
        String normalizedStatus = normalizeStatus(status);
        String normalizedCategory = normalizeCategory(category);

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("records", Collections.emptyList());
        res.put("total", 0L);
        res.put("failedCount", 0L);
        res.put("page", safePage);
        res.put("size", safeSize);
        res.put("status", normalizedStatus);
        res.put("category", normalizedCategory);

        if (!ensureTable()) {
            return res;
        }

        StringBuilder baseWhere = new StringBuilder(" WHERE 1=1");
        List<Object> baseArgs = new ArrayList<>();
        if (userId != null) {
            baseWhere.append(" AND `user_id` = ?");
            baseArgs.add(userId);
        }
        appendCategoryFilter(baseWhere, normalizedCategory);
        if (keyword != null) {
            String trimmed = keyword.trim();
            if (!trimmed.isEmpty()) {
                if (trimmed.length() > MAX_KEYWORD_LEN) {
                    trimmed = trimmed.substring(0, MAX_KEYWORD_LEN);
                }
                String like = "%" + escapeLike(trimmed) + "%";
                baseWhere.append(" AND (`username` LIKE ? ESCAPE '!' OR `action` LIKE ? ESCAPE '!' OR `ip` LIKE ? ESCAPE '!' OR `params` LIKE ? ESCAPE '!' OR `method` LIKE ? ESCAPE '!' OR `role_label` LIKE ? ESCAPE '!')");
                baseArgs.add(like);
                baseArgs.add(like);
                baseArgs.add(like);
                baseArgs.add(like);
                baseArgs.add(like);
                baseArgs.add(like);
            }
        }

        StringBuilder filteredWhere = new StringBuilder(baseWhere);
        if ("success".equals(normalizedStatus)) {
            filteredWhere.append(" AND `result_code` = 0");
        } else if ("failed".equals(normalizedStatus)) {
            filteredWhere.append(" AND `result_code` <> 0");
        }

        try {
            Long failedCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM `audit_log`" + baseWhere + " AND `result_code` <> 0",
                    Long.class, baseArgs.toArray());
            Long total = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM `audit_log`" + filteredWhere, Long.class, baseArgs.toArray());
            List<Object> listArgs = new ArrayList<>(baseArgs);
            listArgs.add(safeSize);
            listArgs.add(offset);
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT `id`, `user_id` AS `userId`, `username`, `role_label` AS `roleLabel`, "
                            + "`action`, `method`, `ip`, `params`, `result_code` AS `resultCode`, `created_time` AS `createdTime` "
                            + "FROM `audit_log`" + filteredWhere + " ORDER BY `created_time` DESC, `id` DESC LIMIT ? OFFSET ?",
                    listArgs.toArray());
            res.put("records", rows);
            res.put("total", total == null ? 0L : total);
            res.put("failedCount", failedCount == null ? 0L : failedCount);
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("doesn't exist")) {
                available = false;
            }
            log.warn("查询操作审计日志失败: {}", e.getMessage());
        }
        return res;
    }

    static String normalizeStatus(String status) {
        if (status == null) return "all";
        String s = status.trim().toLowerCase(java.util.Locale.ROOT);
        if ("success".equals(s) || "failed".equals(s)) {
            return s;
        }
        return "all";
    }

    static String normalizeCategory(String category) {
        if (category == null) return "all";
        String c = category.trim().toLowerCase(java.util.Locale.ROOT);
        switch (c) {
            case "login":
            case "register":
            case "password":
            case "config":
            case "user":
            case "node":
            case "forward":
                return c;
            default:
                return "all";
        }
    }

    private static void appendCategoryFilter(StringBuilder where, String normalizedCategory) {
        switch (normalizedCategory) {
            case "login":
                where.append(" AND (`method` = 'UserController.login' OR `action` = '用户登录')");
                break;
            case "register":
                where.append(" AND (`method` = 'UserController.register' OR `action` = '用户自助注册')");
                break;
            case "password":
                where.append(" AND (`method` = 'UserController.updatePassword' OR `action` = '修改密码')");
                break;
            case "config":
                where.append(" AND (`method` LIKE 'ViteConfigController.%' OR `method` LIKE 'MonitorController.%' OR `method` = 'UserController.clearAuditLogs')");
                break;
            case "user":
                where.append(" AND (`method` LIKE 'UserController.%' AND `method` NOT IN ('UserController.login', 'UserController.register', 'UserController.updatePassword', 'UserController.clearAuditLogs'))");
                break;
            case "node":
                where.append(" AND (`method` LIKE 'NodeController.%' OR `method` LIKE 'InboundController.%' OR `method` LIKE 'LandingController.%')");
                break;
            case "forward":
                where.append(" AND (`method` LIKE 'ForwardController.%' OR `method` LIKE 'TunnelController.%' OR `method` LIKE 'SpeedLimitController.%')");
                break;
            default:
                break;
        }
    }

    static String escapeLike(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length() + 8);
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (ch == '!' || ch == '%' || ch == '_') {
                sb.append('!');
            }
            sb.append(ch);
        }
        return sb.toString();
    }

    public int clearAll() {
        if (!ensureTable()) {
            return 0;
        }
        try {
            return jdbc.update("DELETE FROM `audit_log`");
        } catch (Exception e) {
            log.warn("清空审计日志失败: {}", e.getMessage());
            return 0;
        }
    }

    private static String extractShortMethod(String controllerMethod) {
        if (controllerMethod == null) return "";
        int lastDot = controllerMethod.lastIndexOf('.');
        if (lastDot <= 0) return SubAccessStore.safeTruncate(controllerMethod, 160);
        int prevDot = controllerMethod.lastIndexOf('.', lastDot - 1);
        String key = prevDot >= 0 ? controllerMethod.substring(prevDot + 1) : controllerMethod;
        return SubAccessStore.safeTruncate(key, 160);
    }
}
