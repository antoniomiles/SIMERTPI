package ec.gob.simertpi.application.identity.smtp;

public final class SmtpOperationException extends RuntimeException {
    private final int status;

    public SmtpOperationException(String message) { this(message, 409); }
    public SmtpOperationException(String message, int status) {
        super(message);
        if (status < 400 || status > 599) throw new IllegalArgumentException("Invalid HTTP status");
        this.status = status;
    }
    public int status() { return status; }
}
