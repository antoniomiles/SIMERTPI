package ec.gob.simertpi.application.identity.smtp;

/** Secret-bearing command object. Never serialize or log it. */
public record SmtpCandidate(String provider, String displayName, String fromAddress, String host,
                            int port, String security, String username, char[] password) {
    @Override public String toString() {
        return "SmtpCandidate[provider=" + provider + ", host=" + host + ", port=" + port
                + ", username=" + username + ", password=redacted]";
    }
}
