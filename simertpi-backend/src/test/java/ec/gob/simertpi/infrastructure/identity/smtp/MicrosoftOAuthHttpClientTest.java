package ec.gob.simertpi.infrastructure.identity.smtp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import com.sun.net.httpserver.HttpServer;
import ec.gob.simertpi.application.identity.smtp.MicrosoftOAuthClient.TokenResponse;
import ec.gob.simertpi.application.identity.smtp.MicrosoftOAuthClientException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MicrosoftOAuthHttpClientTest {
    private HttpServer server;
    private final AtomicReference<String> responseBody = new AtomicReference<>();
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private int responseStatus;

    @BeforeEach void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = responseBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
    }

    @AfterEach void stopServer() { if (server != null) server.stop(0); }

    @Test void exchangesCodeWithPkceAndReturnsRedactedTokens() {
        responseStatus = 200;
        responseBody.set("{\"access_token\":\"access-secret-value\",\"refresh_token\":\"refresh-secret-value\",\"id_token\":\"id-secret-value\",\"expires_in\":3600}");
        var client = client("client-id", "client-secret");

        TokenResponse tokens = client.exchange("auth-code", "A".repeat(43), "https://portal.example.test/callback");

        assertThat(tokens.accessToken()).isEqualTo("access-secret-value");
        assertThat(tokens.refreshToken()).isEqualTo("refresh-secret-value");
        assertThat(requestBody.get()).contains("grant_type=authorization_code", "code=auth-code", "code_verifier="
                + "A".repeat(43), "client_secret=client-secret", "https%3A%2F%2Foutlook.office.com%2FSMTP.Send", "offline_access");
        assertThat(tokens.toString()).doesNotContain("access-secret-value", "refresh-secret-value", "id-secret-value");
    }

    @Test void refreshPreservesOptionalRotatedRefreshTokenSemantics() {
        responseStatus = 200;
        responseBody.set("{\"access_token\":\"fresh-access\",\"expires_in\":3600}");
        TokenResponse tokens = client("client-id", "client-secret").refresh("old-refresh-token");
        assertThat(tokens.accessToken()).isEqualTo("fresh-access");
        assertThat(tokens.refreshToken()).isNull();
        assertThat(requestBody.get()).contains("grant_type=refresh_token", "refresh_token=old-refresh-token");
    }

    @Test void invalidGrantIsDistinguishableWithoutLeakingProviderBody() {
        responseStatus = 400;
        responseBody.set("{\"error\":\"invalid_grant\",\"error_description\":\"refresh-secret-value invalid_grant\"}");
        assertThatThrownBy(() -> client("client-id", "client-secret").refresh("refresh-secret-value"))
                .isInstanceOfSatisfying(MicrosoftOAuthClientException.class, failure -> {
                    assertThat(failure.code()).isEqualTo("INVALID_GRANT");
                    assertThat(failure.getMessage()).doesNotContain("refresh-secret-value");
                    assertThat(failure.toString()).doesNotContain("refresh-secret-value");
                });
    }

    @Test void identityValidationErrorsDoNotEchoTokenOrNonce() {
        var client = client("client-id", "client-secret");
        assertThatThrownBy(() -> client.validateIdentity("sensitive-id-token", "sensitive-nonce"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("La identidad de Microsoft no pudo validarse");
    }

    @Test void missingClientConfigurationFailsClosed() {
        var client = client("", "");
        assertThatThrownBy(() -> client.exchange("code-secret", "A".repeat(43), "https://portal.example.test/callback"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no está configurado");
        assertThat(requestBody.get()).isNull();
    }

    private MicrosoftOAuthHttpClient client(String clientId, String secret) {
        URI tokenEndpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/token");
        return new MicrosoftOAuthHttpClient(new ObjectMapper(), clientId, secret, "https://portal.example.test/callback",
                HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
                new DefaultJWTProcessor<SecurityContext>(), tokenEndpoint);
    }
}
