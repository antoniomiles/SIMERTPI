package ec.gob.simertpi.application.identity.smtp;

import ec.gob.simertpi.testsupport.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "simertpi.smtp.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "simertpi.smtp.test-email-enabled=false",
        "simertpi.smtp.test-recipient="
})
class SmtpConfigurationPostgresIntegrationTest extends AbstractPostgresIntegrationTest {
    @Autowired SmtpConfigurationService service;
    @Autowired JdbcTemplate jdbc;

    @Test void draftCredentialIsEncryptedAndReturnedConfigurationNeverContainsIt() {
        char[] password = "test-app-password-never-plaintext".toCharArray();
        var candidate = new SmtpCandidate("GMAIL", "SIMERTPI QA", "sender@example.test", "smtp.gmail.com",
                587, "STARTTLS", "sender@example.test", password);
        var saved = service.saveDraft(candidate, "qa-smtp-user", "Validación de configuración SMTP");

        assertThat(saved.credentialConfigured()).isTrue();
        assertThat(saved.state()).isEqualTo("DRAFT");
        byte[] persistedCiphertext = jdbc.queryForObject("SELECT credential_ciphertext FROM identity.admin_smtp_configuration WHERE id=?", byte[].class, saved.id());
        assertThat(persistedCiphertext).isNotNull().isNotEqualTo("test-app-password-never-plaintext".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(service.getConfiguration().draft()).isEqualTo(saved);
        assertThat(service.getConfiguration().toString()).doesNotContain("test-app-password-never-plaintext");
        assertThat(saved.toString()).doesNotContain("test-app-password-never-plaintext");
    }
}
