package ec.gob.simertpi.application.identity.smtp;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.security.SecureRandom;
import java.util.Base64;

@Component
public final class SmtpCredentialCipher {
    private static final int NONCE_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private final String encodedKey;
    private final SecureRandom random = new SecureRandom();

    public SmtpCredentialCipher(@Value("${simertpi.smtp.encryption-key:}") String encodedKey) {
        this.encodedKey = encodedKey == null ? "" : encodedKey.trim();
    }

    Encrypted encrypt(char[] secret) {
        byte[] plain = null;
        byte[] key = null;
        try {
            key = key();
            ByteBuffer encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(secret));
            plain = new byte[encoded.remaining()];
            encoded.get(plain);
            if (encoded.hasArray()) java.util.Arrays.fill(encoded.array(), (byte) 0);
            byte[] nonce = new byte[NONCE_LENGTH];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            return new Encrypted(nonce, cipher.doFinal(plain));
        } catch (Exception failure) {
            throw new IllegalStateException("SMTP credential encryption is unavailable", failure);
        } finally {
            if (plain != null) java.util.Arrays.fill(plain, (byte) 0);
            if (key != null) java.util.Arrays.fill(key, (byte) 0);
        }
    }

    char[] decrypt(byte[] nonce, byte[] ciphertext) {
        byte[] plain = null;
        byte[] key = null;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            key = key();
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, nonce));
            plain = cipher.doFinal(ciphertext);
            CharBuffer decoded = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(plain));
            char[] clear = new char[decoded.remaining()];
            decoded.get(clear);
            if (decoded.hasArray()) java.util.Arrays.fill(decoded.array(), '\0');
            return clear;
        } catch (Exception failure) {
            throw new IllegalStateException("SMTP credential could not be decrypted", failure);
        } finally {
            if (plain != null) java.util.Arrays.fill(plain, (byte) 0);
            if (key != null) java.util.Arrays.fill(key, (byte) 0);
        }
    }

    private byte[] key() {
        try {
            byte[] bytes = Base64.getDecoder().decode(encodedKey);
            if (bytes.length != 32) throw new IllegalStateException("SMTP encryption key must decode to 32 bytes");
            return bytes;
        } catch (IllegalArgumentException failure) {
            throw new IllegalStateException("SMTP encryption key must be a Base64-encoded 256-bit value", failure);
        }
    }

    record Encrypted(byte[] nonce, byte[] ciphertext) { }
}
