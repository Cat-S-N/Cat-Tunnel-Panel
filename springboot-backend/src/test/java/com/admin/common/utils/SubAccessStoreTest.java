package com.admin.common.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SubAccessStoreTest {

    @Test
    void extractsFirstValidIp() {
        assertEquals("1.2.3.4", SubAccessStore.firstHeaderIp("1.2.3.4, 10.0.0.1"));
        assertEquals("2001:db8::1", SubAccessStore.firstHeaderIp(" 2001:db8::1 "));
        assertEquals("", SubAccessStore.firstHeaderIp("unknown"));
        assertEquals("", SubAccessStore.firstHeaderIp(null));
    }

    @Test
    void safeTruncatesStrings() {
        assertEquals("", SubAccessStore.safeTruncate(null, 10));
        assertEquals("abc", SubAccessStore.safeTruncate("  abc  ", 10));
        assertEquals("Clash", SubAccessStore.safeTruncate("ClashForWindows/1.0", 5));
    }
}
