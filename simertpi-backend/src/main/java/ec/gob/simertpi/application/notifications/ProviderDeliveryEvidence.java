package ec.gob.simertpi.application.notifications;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;

/** Provider acceptance is not evidence of Android receipt or presentation. */
public final class ProviderDeliveryEvidence {
    private ProviderDeliveryEvidence() {}
    private static final Set<String> SAFE_ERRORS = Set.of(
        "PROVIDER_NOT_CONFIGURED", "PROVIDER_UNKNOWN", "SANDBOX_DISABLED",
        "FCM_RESULT_INVALID", "FCM_TOKEN_INVALID", "FCM_TEMPORARY_FAILURE", "FCM_PERMANENT_FAILURE");
    public static String safeError(String code, String fallback) {
        return SAFE_ERRORS.contains(code == null ? "" : code) ? code : fallback;
    }
    /** Opaque IDs are never logged; oversized/control-bearing IDs retain a stable fingerprint. */
    public static String storageReference(String id) {
        if (id == null || id.isBlank()) return null;
        if (id.length() <= 150 && id.chars().noneMatch(Character::isISOControl)) return id;
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(id.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public static String providerOutcome(String status) {
        return switch (status) {
            case "DELIVERED" -> "PROVIDER_ACCEPTED";
            case "PERMANENT_FAILURE", "INVALID_DESTINATION", "TEMPORARY_FAILURE", "DEAD" -> "PROVIDER_REJECTED";
            default -> "UNCONFIRMED";
        };
    }
}
