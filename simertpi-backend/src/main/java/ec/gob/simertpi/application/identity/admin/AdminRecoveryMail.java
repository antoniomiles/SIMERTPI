package ec.gob.simertpi.application.identity.admin;

public interface AdminRecoveryMail {
    void send(String recipient, String subject, String body);
}
