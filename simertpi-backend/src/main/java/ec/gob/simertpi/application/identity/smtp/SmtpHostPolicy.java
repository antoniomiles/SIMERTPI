package ec.gob.simertpi.application.identity.smtp;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.IDN;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/** SMTP egress allowlist plus DNS checks to prevent the admin form becoming an SSRF proxy. */
@Component
public final class SmtpHostPolicy {
    private final Set<String> allowedHosts;

    public SmtpHostPolicy(@Value("${simertpi.smtp.allowed-hosts:smtp.gmail.com}") String configured) {
        this.allowedHosts = Arrays.stream(configured.split(","))
                .map(String::trim).filter(value -> !value.isEmpty())
                .map(value -> IDN.toASCII(value).toLowerCase(java.util.Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    public void validate(String input) {
        if (input == null || input.length() > 253 || input.endsWith(".")) reject();
        String host;
        try { host = IDN.toASCII(input.trim()).toLowerCase(java.util.Locale.ROOT); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("El servidor SMTP no es válido"); }
        if (host.matches("[0-9]{1,3}(?:\\.[0-9]{1,3}){3}") || host.contains(":")) reject();
        if (!host.matches("(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)(?:\\.(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?))*")
                || host.equals("localhost") || host.endsWith(".localhost") || host.endsWith(".local")
                || host.endsWith(".internal") || !allowedHosts.contains(host)) reject();
        validatePublicAddresses(host);
    }

    /** Validates the fixed Microsoft OAuth SMTP endpoint without permitting it as an arbitrary CUSTOM host. */
    public void validateMicrosoftOAuthHost(String input) {
        if (input == null || !"smtp-mail.outlook.com".equalsIgnoreCase(input.trim())) reject();
        validatePublicAddresses("smtp-mail.outlook.com");
    }

    private static void validatePublicAddresses(String host) {
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                        || address.isSiteLocalAddress() || address.isMulticastAddress()) reject();
                byte[] bytes = address.getAddress();
                if (bytes.length == 4 && isNonPublicIpv4(bytes)) reject();
                if (bytes.length == 16 && ((bytes[0] & 0xfe) == 0xfc || isIpv4Mapped(bytes) && isNonPublicIpv4(java.util.Arrays.copyOfRange(bytes, 12, 16)))) reject();
            }
        } catch (java.net.UnknownHostException unavailable) {
            throw new IllegalArgumentException("No se pudo resolver el servidor SMTP permitido");
        }
    }

    private static boolean isIpv4Mapped(byte[] address) {
        for (int i = 0; i < 10; i++) if (address[i] != 0) return false;
        return (address[10] & 0xff) == 0xff && (address[11] & 0xff) == 0xff;
    }

    private static boolean isNonPublicIpv4(byte[] address) {
        int a=address[0]&0xff, b=address[1]&0xff, c=address[2]&0xff;
        return a==0 || a==10 || a==127 || a>=224 || a==169&&b==254 || a==172&&b>=16&&b<=31
                || a==192&&(b==0||b==168) || a==198&&(b==18||b==19||b==51) || a==203&&b==0&&c==113
                || a==100&&b>=64&&b<=127;
    }

    private static void reject() { throw new IllegalArgumentException("El servidor SMTP no está autorizado"); }
}
