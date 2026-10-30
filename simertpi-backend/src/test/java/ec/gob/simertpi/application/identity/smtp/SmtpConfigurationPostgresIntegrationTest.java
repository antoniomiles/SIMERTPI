package ec.gob.simertpi.application.identity.smtp;

import ec.gob.simertpi.testsupport.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "simertpi.smtp.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "simertpi.smtp.test-email-enabled=false",
        "simertpi.smtp.test-recipient="
})
class SmtpConfigurationPostgresIntegrationTest extends AbstractPostgresIntegrationTest {
    @Autowired SmtpConfigurationService service;
    @Autowired JdbcTemplate jdbc;

    @Test void refusesToCarryCredentialAcrossProviderServerOrUserChangeButKeepsSameAccountCredential() {
        var original = new SmtpCandidate("GMAIL", "SIMERTPI QA", "sender@example.test", "smtp.gmail.com",
                587, "STARTTLS", "sender@example.test", "same-account-secret".toCharArray());
        var saved = service.saveDraft(original, "qa-smtp-user", "SMTP identity fixture");
        var sameAccount = new SmtpCandidate("GMAIL", "SIMERTPI QA", "other-from@example.test", "SMTP.GMAIL.COM",
                587, "STARTTLS", "sender@example.test", new char[0]);
        assertThat(service.saveDraft(sameAccount, "qa-smtp-user", "retain matching credential").credentialConfigured()).isTrue();

        var changedAccount = new SmtpCandidate("GMAIL", "SIMERTPI QA", "sender@example.test", "smtp.gmail.com",
                587, "STARTTLS", "different@example.test", new char[0]);
        assertThatThrownBy(() -> service.saveDraft(changedAccount, "qa-smtp-user", "must replace credential"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("credencial nueva");
        assertThat(jdbc.queryForObject("SELECT state FROM identity.admin_smtp_configuration WHERE id=?", String.class, saved.id()))
                .isEqualTo("SUPERSEDED");
    }

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
