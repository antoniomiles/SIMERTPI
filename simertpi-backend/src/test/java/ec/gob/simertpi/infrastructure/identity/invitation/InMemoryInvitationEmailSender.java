package ec.gob.simertpi.infrastructure.identity.invitation;

import ec.gob.simertpi.application.identity.invitation.InvitationEmailSender;

import java.time.OffsetDateTime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Test-only mail sink. It never sends messages outside the test process. */
public final class InMemoryInvitationEmailSender implements InvitationEmailSender {
    public record Captured(String destination, String displayName, String username, List<String> roleNames,
                           String activationUrl, OffsetDateTime expiresAt) { }

    private final CopyOnWriteArrayList<Captured> messages = new CopyOnWriteArrayList<>();

    @Override
    public String providerCode() {
        return "TEST_SINK";
    }

    @Override
    public boolean configured() {
        return true;
    }

    @Override
    public DeliveryResult send(String destination, String displayName, String username, List<String> roleNames,
                               String activationUrl, OffsetDateTime expiresAt) {
        messages.add(new Captured(destination, displayName, username, List.copyOf(roleNames), activationUrl, expiresAt));
        // The real-browser harness reads this test-only mailbox to follow the same
        // activation link an email recipient would receive. Never expose the token
        // through the administrative API or application UI.
        if (Boolean.getBoolean("simertpi.admin-browser-harness")) {
            try {
                Path mailbox = Path.of("target", "admin-browser-mailbox.tsv");
                Files.createDirectories(mailbox.getParent());
                Files.writeString(mailbox, username + "\t" + activationUrl + System.lineSeparator(),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (java.io.IOException failure) {
                throw new IllegalStateException("Could not record the isolated browser-test email", failure);
            }
        }
        return DeliveryResult.acceptedByProvider();
    }

    public List<Captured> messages() {
        return List.copyOf(messages);
    }
}
