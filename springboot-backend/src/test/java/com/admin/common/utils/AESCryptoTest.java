package com.admin.common.utils;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AESCrypto 单元测试
 */
class AESCryptoTest {

    @Test
    void constructorAndCreateRejectNullOrEmptySecret() {
        assertThrows(IllegalArgumentException.class, () -> new AESCrypto(null));
        assertThrows(IllegalArgumentException.class, () -> new AESCrypto(""));

        assertNull(AESCrypto.create(null));
        assertNull(AESCrypto.create(""));
        assertNotNull(AESCrypto.create("valid-secret-key"));
    }

    @Test
    void encryptAndDecryptStringRoundTripWithRandomIv() {
        AESCrypto crypto = new AESCrypto("my-super-secret-2026");
        String plaintext = "Hello, 世界! 123456 @#$%";

        String cipher1 = crypto.encrypt(plaintext);
        String cipher2 = crypto.encrypt(plaintext);

        assertNotNull(cipher1);
        assertNotNull(cipher2);
        assertNotEquals(cipher1, cipher2, "每次加密应使用随机 IV 生成不同密文");

        assertEquals(plaintext, crypto.decryptString(cipher1));
        assertEquals(plaintext, crypto.decryptString(cipher2));
    }

    @Test
    void encryptAndDecryptBytesRoundTrip() {
        AESCrypto crypto = new AESCrypto("binary-secret");
        byte[] payload = new byte[]{0x00, 0x01, 0x7f, (byte) 0x80, (byte) 0xff};

        String encrypted = crypto.encrypt(payload);
        byte[] decrypted = crypto.decrypt(encrypted);

        assertArrayEquals(payload, decrypted);
    }

    @Test
    void encryptRejectsNullOrEmptyInputs() {
        AESCrypto crypto = new AESCrypto("secret");

        assertThrows(IllegalArgumentException.class, () -> crypto.encrypt((String) null));
        assertThrows(IllegalArgumentException.class, () -> crypto.encrypt(""));
        assertThrows(IllegalArgumentException.class, () -> crypto.encrypt((byte[]) null));
        assertThrows(IllegalArgumentException.class, () -> crypto.encrypt(new byte[0]));
    }

    @Test
    void decryptRejectsInvalidShortTamperedOrWrongKeyCiphertext() {
        AESCrypto cryptoA = new AESCrypto("secret-A");
        AESCrypto cryptoB = new AESCrypto("secret-B");

        assertThrows(IllegalArgumentException.class, () -> cryptoA.decrypt(null));
        assertThrows(IllegalArgumentException.class, () -> cryptoA.decrypt(""));
        assertThrows(RuntimeException.class, () -> cryptoA.decrypt("not-valid-base64!!!"));

        String tooShort = Base64.getEncoder().encodeToString("short".getBytes(StandardCharsets.UTF_8));
        assertThrows(RuntimeException.class, () -> cryptoA.decrypt(tooShort));

        String encrypted = cryptoA.encrypt("sensitive-payload");
        assertThrows(RuntimeException.class, () -> cryptoB.decryptString(encrypted));

        byte[] raw = Base64.getDecoder().decode(encrypted);
        raw[raw.length - 1] ^= 0x01;
        String tampered = Base64.getEncoder().encodeToString(raw);
        assertThrows(RuntimeException.class, () -> cryptoA.decryptString(tampered));
    }
}
