package com.admin.common.utils;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RegisterConfigRule 极端/非法配置校验、规范化与安全降级单元测试
 */
class RegisterConfigRuleTest {

    @Test
    void parseAndNormalizeInviteCodes_deduplicatesStripsUnicodeBlankAndIgnoresEmptySegments() {
        assertTrue(RegisterConfigRule.parseInviteCodes(null).isEmpty());
        assertTrue(RegisterConfigRule.parseInviteCodes("").isEmpty());
        assertTrue(RegisterConfigRule.parseInviteCodes(" , \u3000 , \u200B\uFEFF , ").isEmpty());
        assertFalse(RegisterConfigRule.isInviteRequired(" ,, \u00A0 , "));
        assertEquals("", RegisterConfigRule.normalizeInviteCodes(" , \u3000 , "));

        String messy = " \u3000CODE_A\u200B , , CODE_B , CODE_A , \uFEFFCODE_C\u00A0 , ";
        Set<String> parsed = RegisterConfigRule.parseInviteCodes(messy);
        assertEquals(Arrays.asList("CODE_A", "CODE_B", "CODE_C"), new ArrayList<>(parsed));
        assertTrue(RegisterConfigRule.isInviteRequired(messy));
        assertEquals("CODE_A,CODE_B,CODE_C", RegisterConfigRule.normalizeInviteCodes(messy));
    }

    @Test
    void checkConfigEntry_inviteCodeRejectsNewlinesChineseCommaOverlongTotalAndSingleCode() {
        assertNull(RegisterConfigRule.checkConfigEntry(RegisterConfigRule.KEY_REGISTER_INVITE_CODE, ""));
        assertNull(RegisterConfigRule.checkConfigEntry(RegisterConfigRule.KEY_REGISTER_INVITE_CODE, "VIP2026,CAT888"));

        assertNotNull(RegisterConfigRule.checkConfigEntry(
                RegisterConfigRule.KEY_REGISTER_INVITE_CODE, "CODE1，CODE2"));
        assertNotNull(RegisterConfigRule.checkConfigEntry(
                RegisterConfigRule.KEY_REGISTER_INVITE_CODE, "CODE1\nCODE2"));
        assertNotNull(RegisterConfigRule.checkConfigEntry(
                RegisterConfigRule.KEY_REGISTER_INVITE_CODE, "CODE\u0007BAD"));

        // 单个邀请码超过 128 位（即使总长 <= 512）也必须拦截，与 RegisterDto @Size(max=128) 对齐
        String code129 = repeatChar('A', 129);
        String errSingle = RegisterConfigRule.checkConfigEntry(
                RegisterConfigRule.KEY_REGISTER_INVITE_CODE, code129);
        assertNotNull(errSingle);
        assertTrue(errSingle.contains("128"));

        // 128 位单码合法
        String code128 = repeatChar('B', 128);
        assertNull(RegisterConfigRule.checkConfigEntry(
                RegisterConfigRule.KEY_REGISTER_INVITE_CODE, code128));

        // 总长度超过 512 拦截
        String overTotal = repeatChar('C', 100) + "," + repeatChar('D', 100) + ","
                + repeatChar('E', 100) + "," + repeatChar('F', 100) + "," + repeatChar('G', 110);
        String errTotal = RegisterConfigRule.checkConfigEntry(
                RegisterConfigRule.KEY_REGISTER_INVITE_CODE, overTotal);
        assertNotNull(errTotal);
        assertTrue(errTotal.contains("512"));
    }

    @Test
    void checkConfigEntry_quotaFieldsRejectNegativeSignedDecimalOverflowAndAcceptValidBounds() {
        // 允许空或纯空白（回退默认值）
        assertNull(RegisterConfigRule.checkConfigEntry(RegisterConfigRule.KEY_DEFAULT_FLOW, null));
        assertNull(RegisterConfigRule.checkConfigEntry(RegisterConfigRule.KEY_DEFAULT_FLOW, "  \u3000 "));

        // 严格边界检查：负数、正号前缀、小数、超大溢出均拦截
        assertNotNull(RegisterConfigRule.checkConfigEntry(RegisterConfigRule.KEY_DEFAULT_FLOW, "-1"));
        assertNotNull(RegisterConfigRule.checkConfigEntry(RegisterConfigRule.KEY_DEFAULT_FLOW, "+10"));
        assertNotNull(RegisterConfigRule.checkConfigEntry(RegisterConfigRule.KEY_DEFAULT_FLOW, "10.5"));
        assertNotNull(RegisterConfigRule.checkConfigEntry(RegisterConfigRule.KEY_DEFAULT_FLOW, "1000001"));
        assertNotNull(RegisterConfigRule.checkConfigEntry(RegisterConfigRule.KEY_DEFAULT_FLOW, "9999999999999999999999"));
        assertNull(RegisterConfigRule.checkConfigEntry(RegisterConfigRule.KEY_DEFAULT_FLOW, "0"));
        assertNull(RegisterConfigRule.checkConfigEntry(RegisterConfigRule.KEY_DEFAULT_FLOW, "1000000"));

        assertNotNull(RegisterConfigRule.checkConfigEntry(RegisterConfigRule.KEY_DEFAULT_NUM, "-5"));
        assertNotNull(RegisterConfigRule.checkConfigEntry(RegisterConfigRule.KEY_DEFAULT_NUM, "10001"));
        assertNull(RegisterConfigRule.checkConfigEntry(RegisterConfigRule.KEY_DEFAULT_NUM, "0"));
        assertNull(RegisterConfigRule.checkConfigEntry(RegisterConfigRule.KEY_DEFAULT_NUM, "10000"));

        assertNotNull(RegisterConfigRule.checkConfigEntry(RegisterConfigRule.KEY_DEFAULT_EXP_DAYS, "0"));
        assertNotNull(RegisterConfigRule.checkConfigEntry(RegisterConfigRule.KEY_DEFAULT_EXP_DAYS, "-30"));
        assertNotNull(RegisterConfigRule.checkConfigEntry(RegisterConfigRule.KEY_DEFAULT_EXP_DAYS, "36501"));
        assertNull(RegisterConfigRule.checkConfigEntry(RegisterConfigRule.KEY_DEFAULT_EXP_DAYS, "1"));
        assertNull(RegisterConfigRule.checkConfigEntry(RegisterConfigRule.KEY_DEFAULT_EXP_DAYS, "36500"));

        assertNotNull(RegisterConfigRule.checkConfigEntry(RegisterConfigRule.KEY_DEFAULT_FLOW_RESET_TIME, "-1"));
        assertNotNull(RegisterConfigRule.checkConfigEntry(RegisterConfigRule.KEY_DEFAULT_FLOW_RESET_TIME, "32"));
        assertNull(RegisterConfigRule.checkConfigEntry(RegisterConfigRule.KEY_DEFAULT_FLOW_RESET_TIME, "0"));
        assertNull(RegisterConfigRule.checkConfigEntry(RegisterConfigRule.KEY_DEFAULT_FLOW_RESET_TIME, "31"));

        // register_enabled 布尔开关校验
        assertNull(RegisterConfigRule.checkConfigEntry(RegisterConfigRule.KEY_REGISTER_ENABLED, "true"));
        assertNull(RegisterConfigRule.checkConfigEntry(RegisterConfigRule.KEY_REGISTER_ENABLED, " false "));
        assertNotNull(RegisterConfigRule.checkConfigEntry(RegisterConfigRule.KEY_REGISTER_ENABLED, "enabled"));
    }

    @Test
    void normalizeConfigValue_andResolveBoundedLong_handleEdgeCasesGracefully() {
        assertEquals("25", RegisterConfigRule.normalizeConfigValue(RegisterConfigRule.KEY_DEFAULT_FLOW, " 0025 "));
        assertEquals("0", RegisterConfigRule.normalizeConfigValue(RegisterConfigRule.KEY_DEFAULT_FLOW, "000"));
        assertEquals("", RegisterConfigRule.normalizeConfigValue(RegisterConfigRule.KEY_DEFAULT_NUM, " \u3000 "));
        assertEquals("true", RegisterConfigRule.normalizeConfigValue(RegisterConfigRule.KEY_REGISTER_ENABLED, " true\u200B "));

        assertEquals(10L, RegisterConfigRule.resolveBoundedLong(null, 10L, 0L, 1000L));
        assertEquals(10L, RegisterConfigRule.resolveBoundedLong("  ", 10L, 0L, 1000L));
        assertEquals(10L, RegisterConfigRule.resolveBoundedLong("-5", 10L, 0L, 1000L));
        assertEquals(10L, RegisterConfigRule.resolveBoundedLong("+20", 10L, 0L, 1000L));
        assertEquals(10L, RegisterConfigRule.resolveBoundedLong("1001", 10L, 0L, 1000L));
        assertEquals(10L, RegisterConfigRule.resolveBoundedLong("99999999999999999999999", 10L, 0L, 1000L));
        assertEquals(30L, RegisterConfigRule.resolveBoundedLong("0", 30L, 1L, 36500L));
        assertEquals(0L, RegisterConfigRule.resolveBoundedLong("\u30000\u200B", 10L, 0L, 1000L));
        assertEquals(365L, RegisterConfigRule.resolveBoundedLong(" 0365 ", 30L, 1L, 36500L));
    }

    private static String repeatChar(char c, int count) {
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            sb.append(c);
        }
        return sb.toString();
    }
}
