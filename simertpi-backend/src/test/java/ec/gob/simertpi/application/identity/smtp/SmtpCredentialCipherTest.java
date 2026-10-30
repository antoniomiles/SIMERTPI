package ec.gob.simertpi.application.identity.smtp;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SmtpCredentialCipherTest {
    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @Test void encryptsWithRandomNonceAndDecryptsWithoutPersistingPlaintext() {
        SmtpCredentialCipher cipher = new SmtpCredentialCipher(KEY);
        char[] secret = "application-secret-value".toCharArray();
        var first = cipher.encrypt(secret);
        var second = cipher.encrypt(secret);

        assertThat(first.nonce()).hasSize(12);
        assertThat(first.ciphertext()).isNotEqualTo("application-secret-value".getBytes(StandardCharsets.UTF_8));
        assertThat(first.nonce()).isNotEqualTo(second.nonce());
        assertThat(cipher.decrypt(first.nonce(), first.ciphertext())).containsExactly(secret);
        Arrays.fill(secret, '\0');
    }

    @Test void authenticatedEncryptionRejectsTamperingAndMissingOrMalformedKey() {
        var cipher = new SmtpCredentialCipher(KEY);
        var encrypted = cipher.encrypt("secret".toCharArray());
        encrypted.ciphertext()[0] ^= 1;
        assertThatThrownBy(() -> cipher.decrypt(encrypted.nonce(), encrypted.ciphertext()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new SmtpCredentialCipher("").encrypt("secret".toCharArray()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new SmtpCredentialCipher(Base64.getEncoder().encodeToString(new byte[16]))
                .encrypt("secret".toCharArray())).isInstanceOf(IllegalStateException.class);
    }
}
