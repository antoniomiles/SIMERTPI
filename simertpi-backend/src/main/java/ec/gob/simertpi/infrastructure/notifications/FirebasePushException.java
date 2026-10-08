package ec.gob.simertpi.infrastructure.notifications;

public final class FirebasePushException extends Exception {
    public enum Kind { INVALID_TOKEN, RETRYABLE, PERMANENT }
    private final Kind kind;
    public FirebasePushException(Kind kind) {
        super("FCM_SEND_FAILED");
        this.kind = kind;
    }
    public Kind kind() { return kind; }
}
