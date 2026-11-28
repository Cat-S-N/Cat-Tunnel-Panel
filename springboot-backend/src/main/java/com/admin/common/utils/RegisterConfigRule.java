package com.admin.common.utils;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 用户自助注册配置与邀请码解析校验规则
 * 统一供 ViteConfigServiceImpl 与 UserServiceImpl 复用，确保公开布尔信号、后台配置规范化与注册校验完全一致。
 */
public final class RegisterConfigRule {

    public static final String KEY_REGISTER_ENABLED = "register_enabled";
    public static final String KEY_REGISTER_INVITE_CODE = "register_invite_code";
    public static final String KEY_REGISTER_INVITE_REQUIRED = "register_invite_required";
    public static final String KEY_DEFAULT_FLOW = "register_default_flow";
    public static final String KEY_DEFAULT_NUM = "register_default_num";
    public static final String KEY_DEFAULT_EXP_DAYS = "register_default_exp_days";
    public static final String KEY_DEFAULT_FLOW_RESET_TIME = "register_default_flow_reset_time";

    public static final long DEFAULT_FLOW_GB = 10L;
    public static final long DEFAULT_NUM = 5L;
    public static final long DEFAULT_EXP_DAYS = 30L;
    public static final long DEFAULT_FLOW_RESET_DAY = 1L;

    public static final long MAX_DEFAULT_FLOW_GB = 1_000_000L;
    public static final long MAX_DEFAULT_NUM = 10_000L;
    public static final long MIN_DEFAULT_EXP_DAYS = 1L;
    public static final long MAX_DEFAULT_EXP_DAYS = 36_500L;
    public static final long MAX_FLOW_RESET_DAY = 31L;
    public static final int MAX_INVITE_CONFIG_LEN = 512;
    public static final int MAX_SINGLE_INVITE_CODE_LEN = 128;

    private RegisterConfigRule() {
    }

    /**
     * 去除首尾 ASCII 控制空白、Unicode 空白字符（含全角空格、NBSP）以及常见零宽字符
     */
    public static String trimUnicodeBlank(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        int start = 0;
        int end = raw.length();
        while (start < end && isBlankOrZeroWidth(raw.charAt(start))) {
            start++;
        }
        while (end > start && isBlankOrZeroWidth(raw.charAt(end - 1))) {
            end--;
        }
        return (start == 0 && end == raw.length()) ? raw : raw.substring(start, end);
    }

    private static boolean isBlankOrZeroWidth(char ch) {
        return ch <= 0x20
                || Character.isWhitespace(ch)
                || Character.isSpaceChar(ch)
                || ch == '\u200B'
                || ch == '\u200C'
                || ch == '\u200D'
                || ch == '\uFEFF';
    }

    /**
     * 解析逗号分隔的邀请码配置：每项清理首尾空白与零宽字符、严格过滤空项并保序去重，
     * 防止 "CODE1,,CODE2" 或全角空白造成空串绕过。
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
            String trimmed = trimUnicodeBlank(part);
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
     * 规范化邀请码配置字符串：去除首尾空白、重复项与多余空项，无有效邀请码时统一存为空字符串
     */
    public static String normalizeInviteCodes(String raw) {
        Set<String> codes = parseInviteCodes(raw);
        if (codes.isEmpty()) {
            return "";
        }
        return String.join(",", codes);
    }

    /**
     * 判断配置键是否允许置空（置空表示关闭邀请码或回退默认注册配额）
     */
    public static boolean allowsEmptyConfigValue(String key) {
        return KEY_REGISTER_INVITE_CODE.equals(key)
                || KEY_DEFAULT_FLOW.equals(key)
                || KEY_DEFAULT_NUM.equals(key)
                || KEY_DEFAULT_EXP_DAYS.equals(key)
                || KEY_DEFAULT_FLOW_RESET_TIME.equals(key);
    }

    /**
     * 规范化注册相关配置项存储值（非注册配置键原样返回）
     */
    public static String normalizeConfigValue(String key, String rawValue) {
        if (key == null) {
            return rawValue == null ? "" : rawValue;
        }
        if (KEY_REGISTER_INVITE_CODE.equals(key)) {
            return normalizeInviteCodes(rawValue);
        }
        if (KEY_DEFAULT_FLOW.equals(key)
                || KEY_DEFAULT_NUM.equals(key)
                || KEY_DEFAULT_EXP_DAYS.equals(key)
                || KEY_DEFAULT_FLOW_RESET_TIME.equals(key)) {
            String val = trimUnicodeBlank(rawValue);
            if (val.isEmpty()) {
                return "";
            }
            Long parsed = parseDigitsOnlyLong(val);
            return parsed != null ? String.valueOf(parsed) : val;
        }
        if (KEY_REGISTER_ENABLED.equals(key)) {
            return trimUnicodeBlank(rawValue);
        }
        return rawValue == null ? "" : rawValue;
    }

    /**
     * 安全解析运行期数值型配置：空白、负数、越界或非法格式均回退到默认值，防止手工脏数据导致异常或溢出
     */
    public static long resolveBoundedLong(String rawValue, long fallback, long minInclusive, long maxInclusive) {
        String val = trimUnicodeBlank(rawValue);
        if (val.isEmpty()) {
            return fallback;
        }
        Long parsed = parseDigitsOnlyLong(val);
        if (parsed == null || parsed < minInclusive || parsed > maxInclusive) {
            return fallback;
        }
        return parsed;
    }

    /**
     * 校验网站配置项中的注册相关参数合法性（允许留空使用默认值，非法返回错误原因，合法返回 null）
     */
    public static String checkConfigEntry(String key, String rawValue) {
        if (key == null) {
            return null;
        }
        String val = trimUnicodeBlank(rawValue);
        if (KEY_REGISTER_INVITE_CODE.equals(key)) {
            if (rawValue != null && (rawValue.indexOf('\r') >= 0 || rawValue.indexOf('\n') >= 0)) {
                return "注册邀请码不能包含换行符，多个邀请码请使用英文半角逗号(,)分隔";
            }
            if (val.length() > MAX_INVITE_CONFIG_LEN) {
                return "注册邀请码配置总长度不能超过 " + MAX_INVITE_CONFIG_LEN + " 个字符";
            }
            if (val.indexOf('，') >= 0) {
                return "注册邀请码请使用英文半角逗号(,)分隔多个邀请码";
            }
            for (String code : parseInviteCodes(val)) {
                if (code.length() > MAX_SINGLE_INVITE_CODE_LEN) {
                    return "单个注册邀请码长度不能超过 " + MAX_SINGLE_INVITE_CODE_LEN + " 个字符";
                }
                for (int i = 0; i < code.length(); i++) {
                    char c = code.charAt(i);
                    if (c < 0x20 || c == 0x7F) {
                        return "注册邀请码包含非法控制字符";
                    }
                }
            }
            return null;
        }
        if (val.isEmpty()) {
            return null;
        }
        if (KEY_REGISTER_ENABLED.equals(key)) {
            if (!"true".equals(val) && !"false".equals(val)) {
                return "开放用户注册开关仅支持 true 或 false";
            }
            return null;
        }
        if (KEY_DEFAULT_FLOW.equals(key)) {
            Long n = parseDigitsOnlyLong(val);
            if (n == null || n < 0L || n > MAX_DEFAULT_FLOW_GB) {
                return "新用户默认流量需为 0 到 " + MAX_DEFAULT_FLOW_GB + " 之间的整数";
            }
        } else if (KEY_DEFAULT_NUM.equals(key)) {
            Long n = parseDigitsOnlyLong(val);
            if (n == null || n < 0L || n > MAX_DEFAULT_NUM) {
                return "新用户默认转发数上限需为 0 到 " + MAX_DEFAULT_NUM + " 之间的整数";
            }
        } else if (KEY_DEFAULT_EXP_DAYS.equals(key)) {
            Long n = parseDigitsOnlyLong(val);
            if (n == null || n < MIN_DEFAULT_EXP_DAYS || n > MAX_DEFAULT_EXP_DAYS) {
                return "新用户默认有效期需为 " + MIN_DEFAULT_EXP_DAYS + " 到 " + MAX_DEFAULT_EXP_DAYS + " 之间的整数天数";
            }
        } else if (KEY_DEFAULT_FLOW_RESET_TIME.equals(key)) {
            Long n = parseDigitsOnlyLong(val);
            if (n == null || n < 0L || n > MAX_FLOW_RESET_DAY) {
                return "新用户默认流量重置日需为 0 到 " + MAX_FLOW_RESET_DAY + " 之间的整数";
            }
        }
        return null;
    }

    private static Long parseDigitsOnlyLong(String s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch < '0' || ch > '9') {
                return null;
            }
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
