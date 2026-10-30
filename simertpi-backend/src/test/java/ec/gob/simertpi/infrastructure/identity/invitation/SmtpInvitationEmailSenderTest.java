package ec.gob.simertpi.infrastructure.identity.invitation;

import ec.gob.simertpi.application.identity.invitation.InvitationEmailSender;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.beans.factory.ObjectProvider;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SmtpInvitationEmailSenderTest {
    private static final OffsetDateTime EXPIRY = OffsetDateTime.parse("2026-10-30T10:00:00Z");

    @Test
    void sendsHtmlAndTextInvitationAndOnlyReportsProviderAcceptance() throws Exception {
        JavaMailSender provider = mock(JavaMailSender.class);
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        when(provider.createMimeMessage()).thenReturn(message);
        SmtpInvitationEmailSender sender = sender(provider);

        InvitationEmailSender.DeliveryResult result = sender.send(
                "person@example.test", "Ana <script>alert(1)</script>", "ana", List.of("Secretaría"),
                "https://dev.example.test/activate-account#token=" + "A".repeat(43), EXPIRY);

        assertEquals(InvitationEmailSender.Status.ACCEPTED_BY_PROVIDER, result.status());
        assertEquals("SMTP", sender.providerCode());
        assertTrue(sender.configured());
        assertEquals("SIMERTPI | Activación de tu cuenta", message.getSubject());
        assertEquals("person@example.test", message.getAllRecipients()[0].toString());
        String content = contentOf(message);
        String html = content;
        assertTrue(html.contains("Ana &lt;script&gt;alert(1)&lt;/script&gt;"));
        assertTrue(html.contains("Secretaría"));
        assertTrue(html.contains("href=\"https://dev.example.test/activate-account#token=" + "A".repeat(43) + "\""));
        assertFalse(html.contains("?token="));
        assertTrue(html.contains("hora de Ecuador"));
        verify(provider).send(message);
    }

    @Test
    void reportsFailureWithoutLoggingOrDisclosingMailContents() throws Exception {
        JavaMailSender provider = mock(JavaMailSender.class);
        when(provider.createMimeMessage()).thenThrow(new IllegalStateException("private provider details"));

        InvitationEmailSender.DeliveryResult result = sender(provider).send(
                "private@example.test", "Private", "private-user", List.of("IT_ADMIN"),
                "https://dev.example.test/activate#token=private-token", EXPIRY);

        assertEquals(InvitationEmailSender.Status.FAILED, result.status());
        verify(provider, never()).send(any(MimeMessage.class));
    }

    @Test
    void rejectsActivationUrlsThatCouldLeakTokenThroughQueryString() {
        JavaMailSender provider = mock(JavaMailSender.class);
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        when(provider.createMimeMessage()).thenReturn(message);

        assertEquals(InvitationEmailSender.Status.FAILED, sender(provider).send(
                "person@example.test", "Ana", "ana", List.of("Secretaría"),
                "https://dev.example.test/activate?token=must-not-be-used", EXPIRY).status());
        verify(provider, never()).send(any(MimeMessage.class));
    }

    @Test
    void refusesToSendAnActivationUrlFromAnotherEnvironment() {
        JavaMailSender provider = mock(JavaMailSender.class);
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        when(provider.createMimeMessage()).thenReturn(message);

        assertEquals(InvitationEmailSender.Status.FAILED, sender(provider).send(
                "person@example.test", "Ana", "ana", List.of("Secretaría"),
                "https://production.example.test/activate-account#token=" + "A".repeat(43), EXPIRY).status());
        verify(provider, never()).send(any(MimeMessage.class));
    }

    @Test
    void defaultProviderIsExplicitlyUnconfigured() {
        InvitationEmailSender sender = (destination, displayName, username, roleNames, url, expiresAt) ->
                InvitationEmailSender.DeliveryResult.unconfigured();
        assertEquals(InvitationEmailSender.Status.UNCONFIGURED,
                sender.send("person@example.test", "A", "a", List.of(), "", EXPIRY).status());
    }

    @Test
    void dynamicModeFailsClosedInsteadOfFallingBackToLegacyStaticMailSender() {
        var dynamic = mock(ec.gob.simertpi.application.identity.smtp.SmtpConfigurationService.class);
        when(dynamic.configured()).thenReturn(false);
        when(dynamic.activeCredential()).thenReturn(null);
        JavaMailSender legacy = mock(JavaMailSender.class);
        var sender = new SmtpInvitationEmailSender(
                new InvitationSmtpSettings("legacy@example.test", "https://dev.example.test"), legacy, dynamic,
                new ec.gob.simertpi.application.identity.smtp.SmtpHostPolicy("smtp.gmail.com"));

        var result = sender.send("user@example.test", "User", "user", List.of("IT_ADMIN"),
                "https://dev.example.test/activate#token=" + "A".repeat(43), EXPIRY);

        assertEquals(InvitationEmailSender.Status.UNCONFIGURED, result.status());
        verify(legacy, never()).send(any(MimeMessage.class));
        verify(legacy, never()).createMimeMessage();
    }

    @Test
    void activeInvitationHostIsRevalidatedAndRejectedIpLiteralIsNeverContacted() {
        var dynamic = mock(ec.gob.simertpi.application.identity.smtp.SmtpConfigurationService.class);
        when(dynamic.configured()).thenReturn(true);
        char[] secret = "test-secret".toCharArray();
        when(dynamic.activeCredential()).thenReturn(new ec.gob.simertpi.application.identity.smtp.SmtpConfigurationService.ActiveCredential(
                new ec.gob.simertpi.application.identity.smtp.SmtpTransport.SmtpSettings(
                        "203.0.113.25", 587, "STARTTLS", "sender@example.test", "sender@example.test", "SIMERTPI"),
                secret, 1));
        JavaMailSender legacy = mock(JavaMailSender.class);
        var sender = new SmtpInvitationEmailSender(new InvitationSmtpSettings("", "https://dev.example.test"),
                legacy, dynamic, new ec.gob.simertpi.application.identity.smtp.SmtpHostPolicy("203.0.113.25"));

        var result = sender.send("user@example.test", "User", "user", List.of("IT_ADMIN"),
                "https://dev.example.test/activate#token=" + "A".repeat(43), EXPIRY);

        assertEquals(InvitationEmailSender.Status.FAILED, result.status());
        assertArrayEquals(new char[secret.length], secret);
        verify(legacy, never()).send(any(MimeMessage.class));
        verify(legacy, never()).createMimeMessage();
    }

    @Test
    void smtpProviderWithoutHostFromOrPublicBaseUrlStaysUnconfiguredAndNeverAttemptsSend() {
        JavaMailSender provider = mock(JavaMailSender.class);
        @SuppressWarnings("unchecked") ObjectProvider<JavaMailSender> providerLookup = mock(ObjectProvider.class);
        when(providerLookup.getIfAvailable()).thenReturn(provider);
        MockEnvironment environment = new MockEnvironment()
                .withProperty("simertpi.invitation-email.provider", "SMTP")
                .withProperty("spring.mail.host", "");
        InvitationEmailSender sender = new InvitationEmailConfiguration().invitationEmailSender(
                new InvitationSmtpSettings("", ""), environment, providerLookup);

        assertEquals("UNCONFIGURED", sender.providerCode());
        assertFalse(sender.configured());
        assertEquals(InvitationEmailSender.Status.UNCONFIGURED, sender.send(
                "person@example.test", "Ana", "ana", List.of("Secretaría"),
                "https://dev.example.test/activate#token=" + "A".repeat(43), EXPIRY).status());
        verifyNoInteractions(provider);
    }

    private static SmtpInvitationEmailSender sender(JavaMailSender provider) {
        return new SmtpInvitationEmailSender(new InvitationSmtpSettings("no-reply@example.test", "https://dev.example.test"), provider);
    }

    private static String contentOf(Part part) throws Exception {
        Object content = part.getContent();
        if (content instanceof String value) return value;
        if (content instanceof Multipart multipart) {
            StringBuilder value = new StringBuilder();
            for (int i = 0; i < multipart.getCount(); i++) value.append(contentOf(multipart.getBodyPart(i)));
            return value.toString();
        }
        return "";
    }
}
