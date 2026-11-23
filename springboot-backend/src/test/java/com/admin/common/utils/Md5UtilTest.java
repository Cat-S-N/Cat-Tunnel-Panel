package com.admin.common.utils;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Md5Util 单元测试
 */
class Md5UtilTest {

    @Test
    void md5ProducesStandardLowercaseHexAndIsDeterministic() {
        assertEquals("e10adc3949ba59abbe56e057f20f883e", Md5Util.md5("123456"));
        assertEquals("5d41402abc4b2a76b9719d911017c592", Md5Util.md5("hello"));
        assertEquals(Md5Util.md5("hello"), Md5Util.md5("hello"));
        assertNotEquals(Md5Util.md5("hello"), Md5Util.md5("world"));
    }

    @Test
    void md5HandlesNullEmptyAndSpecialCharacters() {
        assertNull(Md5Util.md5(null));
        assertNull(Md5Util.md5(""));

        String special = "!@#$%^&*()_+-= 中文空格\t\n";
        String hash = Md5Util.md5(special);
        assertNotNull(hash);
        assertEquals(32, hash.length());
        assertTrue(hash.matches("^[0-9a-f]{32}$"));
    }

    @Test
    void md5WithSaltHandlesDefaultCustomAndBoundaryInputs() {
        assertNull(Md5Util.md5WithSalt(null));
        assertNull(Md5Util.md5WithSalt(""));
        assertNull(Md5Util.md5WithSalt(null, "salt"));
        assertNull(Md5Util.md5WithSalt("", "salt"));

        String defaultHash = Md5Util.md5WithSalt("secret");
        assertEquals(defaultHash, Md5Util.md5WithSalt("secret", null));
        assertEquals(defaultHash, Md5Util.md5WithSalt("secret", "admin_salt_2024"));
        assertNotEquals(defaultHash, Md5Util.md5WithSalt("secret", "custom_salt"));
    }

    @Test
    void generateSaltProducesRandomBase64WithExpectedByteLength() {
        String s1 = Md5Util.generateSalt();
        String s2 = Md5Util.generateSalt();
        assertNotNull(s1);
        assertNotNull(s2);
        assertNotEquals(s1, s2);
        assertEquals(16, Base64.getDecoder().decode(s1).length);

        String s24 = Md5Util.generateSalt(24);
        assertEquals(24, Base64.getDecoder().decode(s24).length);
    }

    @Test
    void verifyMatchesValidPasswordsAndRejectsInvalidOrNull() {
        String hashedDefault = Md5Util.md5WithSalt("pass@123");
        assertTrue(Md5Util.verify("pass@123", hashedDefault));
        assertFalse(Md5Util.verify("wrong", hashedDefault));
        assertFalse(Md5Util.verify(null, hashedDefault));
        assertFalse(Md5Util.verify("pass@123", null));

        String salt = "custom-salt-99";
        String hashedCustom = Md5Util.md5WithSalt("pass@123", salt);
        assertTrue(Md5Util.verify("pass@123", salt, hashedCustom));
        assertFalse(Md5Util.verify("pass@123", "other-salt", hashedCustom));
        assertFalse(Md5Util.verify(null, salt, hashedCustom));
        assertFalse(Md5Util.verify("pass@123", salt, null));
    }

    @Test
    void md5MultipleHashesRepeatedlyAndHandlesNonPositiveTimes() {
        assertNull(Md5Util.md5Multiple(null, 3));
        assertEquals("", Md5Util.md5Multiple("", 3));
        assertEquals("abc", Md5Util.md5Multiple("abc", 0));
        assertEquals("abc", Md5Util.md5Multiple("abc", -2));

        String once = Md5Util.md5("abc");
        String twice = Md5Util.md5(once);
        assertEquals(once, Md5Util.md5Multiple("abc", 1));
        assertEquals(twice, Md5Util.md5Multiple("abc", 2));
    }

    @Test
    void getFileMd5HandlesValidAndEmptyBytes() {
        assertNull(Md5Util.getFileMd5(null));
        assertNull(Md5Util.getFileMd5(new byte[0]));

        byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
        assertEquals(Md5Util.md5("hello"), Md5Util.getFileMd5(data));
    }
}
