package ec.gob.simertpi.application.identity.smtp;

/** Sanitized error codes only; provider response bodies and credentials are never retained. */
public final class MicrosoftOAuthClientException extends RuntimeException {
    private final String code;

    public MicrosoftOAuthClientException(String code) {
        super(safe(code));
        this.code = safe(code);
    }

    public String code() { return code; }

    private static String safe(String value) {
        return switch (value == null ? "" : value) {
            case "INVALID_GRANT", "INVALID_CLIENT", "INVALID_SCOPE", "INVALID_REQUEST", "TEMPORARY_FAILURE" -> value;
            default -> "INVALID_RESPONSE";
        };
    }

    @Override public String toString() { return "MicrosoftOAuthClientException[code=" + code + "]"; }
}
