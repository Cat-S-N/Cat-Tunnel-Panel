package com.admin.common.utils;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 用户自助注册配置与邀请码解析校验规则
 * 统一供 ViteConfigServiceImpl 与 UserServiceImpl 复用，确保公开布尔信号与注册校验完全一致。
 */
public final class RegisterConfigRule {

    public static final String KEY_REGISTER_ENABLED = "register_enabled";
    public static final String KEY_REGISTER_INVITE_CODE = "register_invite_code";
    public static final String KEY_REGISTER_INVITE_REQUIRED = "register_invite_required";
    public static final String KEY_DEFAULT_FLOW = "register_default_flow";
    public static final String KEY_DEFAULT_NUM = "register_default_num";
    public static final String KEY_DEFAULT_EXP_DAYS = "register_default_exp_days";
    public static final String KEY_DEFAULT_FLOW_RESET_TIME = "register_default_flow_reset_time";

    public static final long MAX_DEFAULT_FLOW_GB = 1_000_000L;
    public static final long MAX_DEFAULT_NUM = 10_000L;
    public static final long MIN_DEFAULT_EXP_DAYS = 1L;
    public static final long MAX_DEFAULT_EXP_DAYS = 36_500L;
    public static final long MAX_FLOW_RESET_DAY = 31L;
    public static final int MAX_INVITE_CONFIG_LEN = 512;

    private RegisterConfigRule() {
    }

    /**
     * 解析逗号分隔的邀请码配置：每项 trim 并严格过滤空项，防止 "CODE1,,CODE2" 或 " , " 造成空串绕过。
     */
    public static Set<String> parseInviteCodes(String raw) {
        if (raw == null || raw.isEmpty()) {
            return Collections.emptySet();
        }
        String[] parts = raw.split(",");
        Set<String> valid = new LinkedHashSet<>();
        for (String part : parts) {
            if (part == null) {
                continue;
            }
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                valid.add(trimmed);
            }
        }
        return valid.isEmpty() ? Collections.emptySet() : Collections.unmodifiableSet(valid);
    }

    /**
     * 判断当前配置是否要求注册时填写邀请码
     */
    public static boolean isInviteRequired(String raw) {
        return !parseInviteCodes(raw).isEmpty();
    }

    /**
     * 规范化邀请码配置字符串：去除首尾空白与多余空项，无有效邀请码时统一存为空字符串
     */
    public static String normalizeInviteCodes(String raw) {
        Set<String> codes = parseInviteCodes(raw);
        if (codes.isEmpty()) {
            return "";
        }
        return String.join(",", codes);
    }

    /**
     * 校验网站配置项中的注册相关参数合法性（允许留空使用默认值，非法返回错误原因，合法返回 null）
     */
    public static String checkConfigEntry(String key, String rawValue) {
        if (key == null) {
            return null;
        }
        String val = rawValue == null ? "" : rawValue.trim();
        if (KEY_REGISTER_INVITE_CODE.equals(key)) {
            if (val.length() > MAX_INVITE_CONFIG_LEN) {
                return "注册邀请码配置总长度不能超过 " + MAX_INVITE_CONFIG_LEN + " 个字符";
            }
            if (val.indexOf('，') >= 0) {
                return "注册邀请码请使用英文半角逗号(,)分隔多个邀请码";
            }
            return null;
        }
        if (val.isEmpty()) {
            return null;
        }
        if (KEY_DEFAULT_FLOW.equals(key)) {
            Long n = parseLongStrict(val);
            if (n == null || n < 0L || n > MAX_DEFAULT_FLOW_GB) {
                return "新用户默认流量需为 0 到 " + MAX_DEFAULT_FLOW_GB + " 之间的整数";
            }
        } else if (KEY_DEFAULT_NUM.equals(key)) {
            Long n = parseLongStrict(val);
            if (n == null || n < 0L || n > MAX_DEFAULT_NUM) {
                return "新用户默认转发数上限需为 0 到 " + MAX_DEFAULT_NUM + " 之间的整数";
            }
        } else if (KEY_DEFAULT_EXP_DAYS.equals(key)) {
            Long n = parseLongStrict(val);
            if (n == null || n < MIN_DEFAULT_EXP_DAYS || n > MAX_DEFAULT_EXP_DAYS) {
                return "新用户默认有效期需为 " + MIN_DEFAULT_EXP_DAYS + " 到 " + MAX_DEFAULT_EXP_DAYS + " 之间的整数天数";
            }
        } else if (KEY_DEFAULT_FLOW_RESET_TIME.equals(key)) {
            Long n = parseLongStrict(val);
            if (n == null || n < 0L || n > MAX_FLOW_RESET_DAY) {
                return "新用户默认流量重置日需为 0 到 " + MAX_FLOW_RESET_DAY + " 之间的整数";
            }
        }
        return null;
    }

    private static Long parseLongStrict(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
