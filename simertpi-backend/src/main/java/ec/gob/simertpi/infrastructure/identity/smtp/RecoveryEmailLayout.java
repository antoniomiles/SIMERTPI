package ec.gob.simertpi.infrastructure.identity.smtp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.util.HtmlUtils;

final class RecoveryEmailLayout {
    private static final Pattern CONTENT = Pattern.compile(
            "(<h1[^>]*>).*?(</h1>).*?(?=<p>Atentamente,<br>)", Pattern.DOTALL);
    private static final Pattern ACTION = Pattern.compile(
            "<p[^>]*><a href=\"\\{\\{activationUrl}}\"[^>]*>ACTIVAR MI CUENTA</a></p>");
    private static final String TEMPLATE = load();

    private RecoveryEmailLayout() { }

    static String username(String username) {
        return render("Recuperación de usuario", """
                <p>Hemos recibido una solicitud para recordar tu usuario de acceso al portal administrativo SIMERTPI.</p>
                <p style="margin-bottom:6px">Tu usuario es:</p>
                <p style="margin-top:0"><strong>%s</strong></p>
                <p>Puedes utilizar este usuario para iniciar sesión normalmente.</p>
                <p style="font-size:14px;color:#54645a">Si no solicitaste esta información, puedes ignorar este mensaje.</p>
                """.formatted(escape(username)));
    }

    static String password(String resetUrl) {
        var action = ACTION.matcher(TEMPLATE);
        if (!action.find()) throw new IllegalStateException("Institutional email action is missing");
        String button = action.group().replace("ACTIVAR MI CUENTA", "Restablecer contraseña")
                .replace("{{activationUrl}}", escape(resetUrl));
        return render("Restablecer contraseña", """
                <p>Hemos recibido una solicitud para restablecer la contraseña de acceso a tu cuenta administrativa SIMERTPI.</p>
                <p>Para continuar, selecciona el botón:</p>
                %s
                <p style="font-size:14px;color:#54645a">El enlace tiene una vigencia de 30 minutos y solo puede utilizarse una vez.</p>
                <p style="font-size:14px;color:#54645a">Si no solicitaste el cambio, puedes ignorar este mensaje. Tu contraseña actual permanecerá sin cambios.</p>
                """.formatted(button));
    }

    private static String render(String title, String body) {
        var content = CONTENT.matcher(TEMPLATE);
        if (!content.find()) throw new IllegalStateException("Institutional email content is missing");
        return TEMPLATE.substring(0, content.start()) + content.group(1) + title + content.group(2)
                + "\n" + body + TEMPLATE.substring(content.end());
    }

    private static String escape(String value) {
        return HtmlUtils.htmlEscape(value, StandardCharsets.UTF_8.name());
    }

    private static String load() {
        try (var stream = new ClassPathResource("templates/mail/admin-activation.html").getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException unavailable) {
            throw new IllegalStateException("Institutional email template is unavailable", unavailable);
        }
    }
}
