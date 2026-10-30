package ec.gob.simertpiadmin.bootstrap;

import ec.gob.simertpi.application.audit.AuditService;
import ec.gob.simertpi.application.identity.admin.AdminBootstrapService;
import ec.gob.simertpi.application.identity.invitation.ActivationInvitationDeliveryService;
import ec.gob.simertpi.application.identity.smtp.MicrosoftOAuthSmtpService;
import ec.gob.simertpi.application.identity.smtp.SmtpCandidate;
import java.io.Console;
import java.net.URI;
import java.nio.CharBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/** Interactive, database-target-guarded one-time administration bootstrap CLI. */
public final class AdminBootstrapCli {
    private static final String DEV_SUPERADMIN_USERNAME = "superadmin.simertpi.dev";
    private static final String DEV_SUPERADMIN_APPROVED_EMAIL = "simertpi@outlook.com";
    private static final String DEV_SUPERADMIN_EMAIL_ENV = "SIMERTPI_BOOTSTRAP_SUPERADMIN_EMAIL";
    private static final String DEV_SUPERADMIN_CONFIRMATION = "CONFIRM DEV SUPERADMIN";
    private static final String DEV_PORTAL_HOST = "simertpi-admin-web-dev.onrender.com";
    private static final String MICROSOFT_SENDER = "simertpi@outlook.com";
    private static final String MICROSOFT_AUTHORIZE_CONFIRMATION = "AUTHORIZE DEV SIMERTPI OUTLOOK SMTP";
    private static final String MICROSOFT_ACTIVATE_CONFIRMATION = "ACTIVATE DEV OUTLOOK SMTP";
    public static final String RESOURCE = "dpg-db1hq55g1s2s73a8e340-a";

    private AdminBootstrapCli() { }
    public static void main(String[] args) { System.exit(run(args)); }

    public static int run(String[] args) {
        char[] first = null, second = null;
        try {
            BootstrapMode mode = parseMode(args);
            Map<String, String> flags = parseFlags(args);
            String host = flags.get("--expected-host"), user = flags.get("--expected-db-user");
            String operator = flags.get("--operator"), approval = flags.get("--approval-id");
            if (!user.matches("[A-Za-z_][A-Za-z0-9_]{0,62}")
                    || !operator.matches("[A-Za-z0-9._:@/-]{3,100}")
                    || !approval.matches("[A-Za-z0-9._:/-]{3,100}")) throw new IllegalArgumentException();
            Console console = System.console();
            if (console == null) throw new IllegalStateException();
            String url = required("DB_URL"); verifyJdbcTarget(url, host);
            if (!user.equals(required("DB_USERNAME"))) throw new IllegalStateException();

            Map<String, Object> forced = new HashMap<>();
            forced.put("spring.datasource.url", url);
            forced.put("spring.datasource.username", user);
            forced.put("spring.datasource.password", required("DB_PASSWORD"));
            if (mode.requiresEncryptionKey()) forced.put("simertpi.smtp.encryption-key", required("SIMERTPI_SMTP_ENCRYPTION_KEY"));
            forced.put("spring.flyway.enabled", false);
            forced.put("spring.jpa.hibernate.ddl-auto", "none");
            forced.put("spring.sql.init.mode", "never");
            forced.put("spring.jpa.show-sql", false);
            forced.put("spring.jpa.properties.hibernate.show_sql", false);
            forced.put("spring.jpa.open-in-view", false);
            forced.put("logging.config", "classpath:logback-bootstrap.xml");
            forced.put("logging.level.root", "OFF");
            forced.put("spring.main.banner-mode", "off");
            System.setProperty("logging.level.root", "OFF");
            System.setProperty("logging.config", "classpath:logback-bootstrap.xml");

            try (var context = new SpringApplicationBuilder(AdminBootstrapConfiguration.class)
                    .web(WebApplicationType.NONE).properties("spring.main.banner-mode=off", "logging.level.root=OFF")
                    .initializers(c -> c.getEnvironment().getPropertySources().addFirst(new MapPropertySource("bootstrap-safety", forced))).run()) {
                verifyConnectedTarget(context.getBean(DataSource.class), host, user);
                SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                        "BOOTSTRAP:" + operator, null, List.of(new SimpleGrantedAuthority("BOOTSTRAP_OPERATOR"))));

                if (mode == BootstrapMode.AUTHORIZE_OUTLOOK) return authorizeOutlook(context, console, operator, approval);
                if (mode == BootstrapMode.ACTIVATE_OUTLOOK) return activateOutlook(context, console, operator, approval);

                var identity = new AdminBootstrapService.Identity(console.readLine("Usuario administrativo aprobado: "),
                        console.readLine("Correo aprobado: "), console.readLine("Nombres: "), console.readLine("Apellidos: "));
                if (mode == BootstrapMode.DEV_SUPERADMIN || mode == BootstrapMode.RESEND_SUPERADMIN)
                    return runSuperadmin(context, console, identity, approval, operator, mode);

                first = console.readPassword("Contraseña (mínimo 16 caracteres; máximo 72 bytes UTF-8): ");
                second = console.readPassword("Repita contraseña: ");
                if (first == null || second == null || !Arrays.equals(first, second)) throw new IllegalArgumentException();
                try {
                    context.getBean(AdminBootstrapService.class).createFirstAdmin(identity, CharBuffer.wrap(first), approval);
                } catch (RuntimeException rejected) {
                    context.getBean(AuditService.class).recordOutcome("ADMIN_BOOTSTRAP", "USER", null, "FAILURE", Map.of("approvalId", approval));
                    throw rejected;
                }
                console.printf("Cuenta administrativa creada y verificada; enabled=true, único rol=%s.%n", mode.roleCode);
            }
            return 0;
        } catch (Exception rejected) {
            System.err.println("Bootstrap detenido; destino, autorización o persistencia no verificados. No se muestran detalles sensibles.");
            return 1;
        } finally {
            if (first != null) Arrays.fill(first, '\0');
            if (second != null) Arrays.fill(second, '\0');
            SecurityContextHolder.clearContext();
        }
    }

    private static int authorizeOutlook(org.springframework.context.ConfigurableApplicationContext context,
                                        Console console, String operator, String approval) {
        requireMicrosoftConfiguration(); requireMicrosoftSender();
        String confirmation = console.readLine("Escriba AUTHORIZE DEV SIMERTPI OUTLOOK SMTP para confirmar la primera autorización: ");
        if (!MICROSOFT_AUTHORIZE_CONFIRMATION.equals(confirmation)) throw new IllegalStateException("Explicit confirmation required");
        if (itAdminExists(context.getBean(JdbcTemplate.class))) throw new IllegalStateException("Un IT_ADMIN ya existe; no se permite bootstrap OAuth inicial");
        MicrosoftOAuthSmtpService.StartResult start = context.getBean(MicrosoftOAuthSmtpService.class)
                .startBootstrapAuthorization(operator, approval, confirmation);
        // The URL contains only a single-use state and is printed for this interactive operator action.
        console.printf("Abra esta URL de Microsoft ahora; vence en 10 minutos y es de un solo uso:%n%s%n", start.authorizationUrl());
        return 0;
    }

    private static int activateOutlook(org.springframework.context.ConfigurableApplicationContext context,
                                       Console console, String operator, String approval) {
        requireMicrosoftConfiguration(); requireMicrosoftSender();
        String confirmation = console.readLine("Escriba ACTIVATE DEV OUTLOOK SMTP para confirmar la prueba y activación: ");
        if (!MICROSOFT_ACTIVATE_CONFIRMATION.equals(confirmation)) throw new IllegalStateException("Explicit confirmation required");
        if (itAdminExists(context.getBean(JdbcTemplate.class))) throw new IllegalStateException("La activación bootstrap inicial solo se permite antes de existir un IT_ADMIN");
        MicrosoftOAuthSmtpService service = context.getBean(MicrosoftOAuthSmtpService.class);
        service.testConnection("BOOTSTRAP:" + operator);
        var result = service.activate("BOOTSTRAP:" + operator, "Explicit initial DEV Microsoft SMTP activation: " + approval);
        console.printf("Microsoft OAuth SMTP status=%s. Esta operación no creó una cuenta.%n", result.status());
        return 0;
    }

    private static int runSuperadmin(org.springframework.context.ConfigurableApplicationContext context, Console console,
                                     AdminBootstrapService.Identity identity, String approval, String operator,
                                     BootstrapMode mode) {
        String confirmation = console.readLine("Escriba CONFIRM DEV SUPERADMIN para aprobar la identidad: ");
        String expectedEmail = required(DEV_SUPERADMIN_EMAIL_ENV);
        if (!isApprovedDevSuperadmin(identity.username(), identity.email(), expectedEmail, confirmation))
            throw new IllegalStateException("La identidad o confirmación no coincide con la cuenta Superadministradora autorizada");
        verifyPortalUrl(required("SIMERTPI_PUBLIC_BASE_URL"));
        required("SIMERTPI_SMTP_ENCRYPTION_KEY");
        try {
            AdminBootstrapService bootstrap = context.getBean(AdminBootstrapService.class);
            AdminBootstrapService.PendingSuperadmin invite;
            if (mode == BootstrapMode.DEV_SUPERADMIN) {
                JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
                if (itAdminExists(jdbc)) throw new IllegalStateException("Un IT_ADMIN ya existe; no se permite un segundo bootstrap");
                Boolean identityExists = jdbc.queryForObject("""
                        SELECT EXISTS(SELECT 1 FROM identity.users
                                      WHERE lower(username)=lower(?) OR lower(email)=lower(?))
                        """, Boolean.class, identity.username().trim(), identity.email().trim());
                if (Boolean.TRUE.equals(identityExists)) throw new IllegalStateException("La identidad ya existe; no se promueven cuentas existentes");
                char[] typed = console.readPassword("Contraseña de aplicación Gmail (entrada oculta): ");
                char[] appPassword = normalizeGmailAppPassword(typed);
                try {
                    SmtpCandidate candidate = new SmtpCandidate("GMAIL", "SIMERTPI — GAD Piñas",
                            "antoxavie1992@gmail.com", "smtp.gmail.com", 587, "STARTTLS",
                            "antoxavie1992@gmail.com", appPassword);
                    invite = bootstrap.createFirstSuperadminInvite(identity, approval, candidate).invitation();
                } finally {
                    Arrays.fill(appPassword, '\0');
                }
            } else {
                var smtp = context.getBean(ec.gob.simertpi.application.identity.smtp.SmtpConfigurationService.class);
                var microsoft = context.getBean(MicrosoftOAuthSmtpService.class);
                if (!smtp.configured() && !microsoft.isActive())
                    throw new IllegalStateException("No existe un proveedor de correo activo y verificado para el reenvío");
                invite = bootstrap.reissueFirstSuperadminInvite(identity, approval);
            }
            var delivery = context.getBean(ActivationInvitationDeliveryService.class)
                    .deliver(invite.id(), invite.activationToken(), invite.expiresAt(), "BOOTSTRAP:" + operator);
            console.printf("Invitación de IT_ADMIN pendiente; vence %s; estado del proveedor=%s (aceptación no confirma entrega al buzón).%n",
                    invite.expiresAt(), delivery.status());
            return "ACCEPTED_BY_PROVIDER".equals(delivery.status()) ? 0 : 2;
        } catch (RuntimeException rejected) {
            context.getBean(AuditService.class).recordOutcome("ADMIN_BOOTSTRAP_PENDING", "USER", null, "FAILURE", Map.of("approvalId", approval));
            throw rejected;
        }
    }

    /** Removes only presentation whitespace from Google's grouped app-password display and clears the input buffer. */
    public static char[] normalizeGmailAppPassword(char[] typed) {
        if (typed == null) throw new IllegalArgumentException("La contraseña de aplicación Gmail es obligatoria");
        int length = 0;
        for (char value : typed) if (!Character.isWhitespace(value)) length++;
        char[] normalized = new char[length];
        int index = 0;
        for (char value : typed) {
            if (Character.isWhitespace(value)) continue;
            if (value < 0x21 || value > 0x7e) {
                Arrays.fill(normalized, '\0');
                Arrays.fill(typed, '\0');
                throw new IllegalArgumentException("La contraseña de aplicación Gmail contiene caracteres no válidos");
            }
            normalized[index++] = value;
        }
        Arrays.fill(typed, '\0');
        if (normalized.length == 0) throw new IllegalArgumentException("La contraseña de aplicación Gmail es obligatoria");
        return normalized;
    }

    private static void requireMicrosoftConfiguration() {
        required("SIMERTPI_MICROSOFT_CLIENT_ID");
        required("SIMERTPI_MICROSOFT_CLIENT_SECRET");
        required("SIMERTPI_MICROSOFT_REDIRECT_URI");
        required("SIMERTPI_ADMIN_WEB_ORIGIN");
        required("SIMERTPI_SMTP_ENCRYPTION_KEY");
    }

    private static void requireMicrosoftSender() {
        if (!MICROSOFT_SENDER.equalsIgnoreCase(required("SIMERTPI_MICROSOFT_AUTHORIZED_SENDER").trim()))
            throw new IllegalStateException("Solo se admite el remitente Microsoft aprobado para DEV");
    }

    private static boolean itAdminExists(JdbcTemplate jdbc) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM identity.user_roles ur JOIN identity.roles r ON r.id=ur.role_id WHERE r.code='IT_ADMIN'", Integer.class);
        return count != null && count > 0;
    }

    private static Map<String, String> parseFlags(String[] args) {
        if (args == null || args.length != 5) throw new IllegalArgumentException();
        Map<String, String> flags = new HashMap<>();
        for (int i = 1; i < args.length; i++) {
            String[] flag = args[i].split("=", 2);
            if (flag.length != 2 || flags.put(flag[0], flag[1]) != null) throw new IllegalArgumentException();
        }
        if (!flags.keySet().equals(Set.of("--expected-host", "--expected-db-user", "--operator", "--approval-id")))
            throw new IllegalArgumentException();
        return flags;
    }

    public static boolean isApprovedDevSuperadmin(String username, String email, String expectedEmail, String confirmation) {
        return DEV_SUPERADMIN_USERNAME.equals(username == null ? "" : username.trim())
                && DEV_SUPERADMIN_APPROVED_EMAIL.equalsIgnoreCase(expectedEmail == null ? "" : expectedEmail.trim())
                && DEV_SUPERADMIN_APPROVED_EMAIL.equalsIgnoreCase(email == null ? "" : email.trim())
                && DEV_SUPERADMIN_CONFIRMATION.equals(confirmation);
    }

    private static boolean validEmail(String email) {
        return email != null && email.length() <= 255 && email.trim().matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    }

    public enum BootstrapMode {
        DEV_ADMIN("SIMERTPI_ADMIN", false), DEV_SUPERADMIN("IT_ADMIN", true), RESEND_SUPERADMIN("IT_ADMIN", true),
        AUTHORIZE_OUTLOOK(null, true), ACTIVATE_OUTLOOK(null, true);
        final String roleCode; private final boolean encryptionKeyRequired;
        BootstrapMode(String roleCode, boolean encryptionKeyRequired) { this.roleCode = roleCode; this.encryptionKeyRequired = encryptionKeyRequired; }
        boolean requiresEncryptionKey() { return encryptionKeyRequired; }
    }

    public static BootstrapMode parseMode(String[] args) {
        if (args == null || args.length == 0) throw new IllegalArgumentException();
        return switch (args[0]) {
            case "--bootstrap-dev-admin" -> BootstrapMode.DEV_ADMIN;
            case "--bootstrap-dev-superadmin" -> BootstrapMode.DEV_SUPERADMIN;
            case "--resend-dev-superadmin-invitation" -> BootstrapMode.RESEND_SUPERADMIN;
            case "--authorize-dev-microsoft-smtp" -> BootstrapMode.AUTHORIZE_OUTLOOK;
            case "--activate-dev-microsoft-smtp" -> BootstrapMode.ACTIVATE_OUTLOOK;
            default -> throw new IllegalArgumentException();
        };
    }

    public static void verifyPortalUrl(String value) {
        try {
            URI uri = URI.create(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !DEV_PORTAL_HOST.equalsIgnoreCase(uri.getHost())
                    || uri.getUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443)
                    || !(uri.getRawPath() == null || uri.getRawPath().isEmpty() || uri.getRawPath().equals("/"))
                    || uri.getRawQuery() != null || uri.getRawFragment() != null) throw new IllegalArgumentException();
        } catch (RuntimeException invalid) { throw new IllegalArgumentException("Verified HTTPS portal origin required"); }
    }

    public static void verifyJdbcTarget(String url, String expectedHost) {
        if (expectedHost == null || !expectedHost.matches(java.util.regex.Pattern.quote(RESOURCE) + "(?:\\.[a-z]+-postgres\\.render\\.com|\\.internal)?"))
            throw new IllegalArgumentException("Unapproved DEV host");
        if (!url.startsWith("jdbc:postgresql://")) throw new IllegalArgumentException("Invalid JDBC target");
        URI uri = URI.create(url.substring(5));
        if (!expectedHost.equals(uri.getHost()) || !"/simertpi".equals(uri.getPath()) || uri.getUserInfo() != null
                || uri.getPort() != 5432 || uri.getFragment() != null) throw new IllegalArgumentException("Target mismatch");
        String query = uri.getRawQuery();
        if (query == null || !Arrays.asList(query.split("&")).contains("sslmode=verify-full")
                || !Arrays.stream(query.split("&")).allMatch(q -> q.equals("sslmode=verify-full") || q.startsWith("sslrootcert=")))
            throw new IllegalArgumentException("Verified TLS required");
    }

    public static void verifyConnectedTarget(DataSource source, String host, String user) throws Exception {
        try (var connection = source.getConnection()) {
            verifyJdbcTarget(connection.getMetaData().getURL(), host);
            try (var statement = connection.createStatement(); var result = statement.executeQuery(
                    "SELECT current_database(),current_user,current_setting('server_version_num')::int, (SELECT ssl FROM pg_stat_ssl WHERE pid=pg_backend_pid())")) {
                if (!result.next() || !"simertpi".equals(result.getString(1)) || !user.equals(result.getString(2))
                        || result.getInt(3) / 10000 != 16 || !result.getBoolean(4))
                    throw new IllegalStateException("Connected target mismatch");
            }
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing configuration");
        return value;
    }
}
