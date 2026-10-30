package ec.gob.simertpi.application.identity.smtp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SmtpHostPolicyTest {
    @Test void rejectsUnapprovedHostsIpLiteralsAndPrivateHostnamesBeforeConnecting() {
        var policy = new SmtpHostPolicy("smtp.gmail.com, mail.approved.example");
        assertThatThrownBy(() -> policy.validate("127.0.0.1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.validate("localhost")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.validate("metadata.google.internal")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.validate("attacker.example")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.validate("mail.approved.example:25")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SmtpHostPolicy("203.0.113.25").validate("203.0.113.25"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SmtpHostPolicy("2001:db8::1").validate("2001:db8::1"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
