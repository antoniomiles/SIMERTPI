package ec.gob.simertpi.api.identity.admin;

import ec.gob.simertpi.application.identity.smtp.SmtpConfigurationService;
import ec.gob.simertpi.application.identity.smtp.SmtpOperationException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

class AdminSmtpConfigurationControllerTest {
    private final SmtpConfigurationService service = mock(SmtpConfigurationService.class);
    private final AdminSmtpConfigurationController controller = new AdminSmtpConfigurationController(service);
    private final UsernamePasswordAuthenticationToken actor = UsernamePasswordAuthenticationToken.authenticated("smtp-admin", null, List.of());

    @Test void missingDraftForTestEmailReturnsConflictInsteadOfServerError() {
        when(service.sendTestEmail("smtp-admin")).thenThrow(new SmtpOperationException("Guarda un borrador SMTP antes de enviar una prueba", 409));

        var response = controller.testEmail(actor);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).asString().contains("Guarda un borrador SMTP").doesNotContain("password", "secret", "token");
    }

    @Test void throttledOrDisabledTestEmailReturnsExplicitSafeStatus() {
        doThrow(new SmtpOperationException("Se alcanzó el límite temporal de pruebas SMTP", 429))
                .doThrow(new SmtpOperationException("El envío de prueba no está habilitado en este ambiente", 503))
                .when(service).sendTestEmail("smtp-admin");
        var rateLimit = controller.testEmail(actor);
        assertThat(rateLimit.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(rateLimit.getBody()).asString().doesNotContain("smtp-admin");

        var disabled = controller.testEmail(actor);
        assertThat(disabled.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test void staleDraftConnectionAndActivationReturnNotFoundOrConflictWithoutExceptionDetails() {
        when(service.testConnection(800L, "smtp-admin")).thenThrow(new SmtpOperationException("El borrador SMTP ya no existe o fue reemplazado", 404));
        var missing = controller.testConnection(new AdminSmtpConfigurationController.VersionRequest(800L), actor);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        when(service.activate(800L, "activación autorizada", "smtp-admin"))
                .thenThrow(new SmtpOperationException("Prueba primero la conexión de esta versión antes de activarla", 409));
        var stale = controller.activate(new AdminSmtpConfigurationController.ActivateRequest(800L, "activación autorizada"), actor);
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(stale.getBody()).asString().doesNotContain("Exception", "credential", "password");
    }
}
