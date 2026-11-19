package ec.gob.simertpi.application.identity.admin;

public interface AdminRecoveryMail {
    void sendUsername(String recipient, String username);
    void sendPasswordReset(String recipient, String resetUrl);
}
