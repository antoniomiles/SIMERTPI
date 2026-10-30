package ec.gob.simertpi.infrastructure.identity.smtp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.proc.JWSKeySelector;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.DefaultResourceRetriever;
import com.nimbusds.jose.util.ResourceRetriever;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.BadJWTException;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import com.nimbusds.jwt.proc.JWTClaimsSetVerifier;
import com.nimbusds.jose.jwk.source.RemoteJWKSet;
import ec.gob.simertpi.application.identity.smtp.MicrosoftOAuthClient;
import ec.gob.simertpi.application.identity.smtp.MicrosoftOAuthClientException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Fixed-endpoint Microsoft identity adapter. No request-controlled URL is accepted. */
@Component
public final class MicrosoftOAuthHttpClient implements MicrosoftOAuthClient {
    static final String TOKEN_ENDPOINT = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token";
    static final String JWKS_ENDPOINT = "https://login.microsoftonline.com/consumers/discovery/v2.0/keys";
    static final String CONSUMER_TENANT_ID = "9188040d-6c67-4c5b-b112-36a304b66dad";
    private static final int MAX_BODY_BYTES = 64 * 1024;
    private static final Set<String> ALLOWED_SCOPES = Set.of("openid", "profile", "email", "offline_access",
            "https://outlook.office.com/SMTP.Send");

    private final String clientId;
    private final String clientSecret;
    private final String expectedRedirectUri;
    private final HttpClient http;
    private final ObjectMapper json;
    private final DefaultJWTProcessor<SecurityContext> jwtProcessor;
    private final URI tokenEndpoint;

    @Autowired
    public MicrosoftOAuthHttpClient(ObjectMapper json,
                                    @Value("${simertpi.smtp.microsoft.client-id:}") String clientId,
                                    @Value("${simertpi.smtp.microsoft.client-secret:}") String clientSecret,
                                    @Value("${simertpi.smtp.microsoft.redirect-uri:}") String expectedRedirectUri) {
        this(json, clientId, clientSecret, expectedRedirectUri, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build(),
                jwksProcessor(), URI.create(TOKEN_ENDPOINT));
    }

    MicrosoftOAuthHttpClient(ObjectMapper json, String clientId, String clientSecret, String expectedRedirectUri,
                             HttpClient http, DefaultJWTProcessor<SecurityContext> jwtProcessor, URI tokenEndpoint) {
        this.json = Objects.requireNonNull(json);
        this.clientId = clientId == null ? "" : clientId.trim();
        this.clientSecret = clientSecret == null ? "" : clientSecret;
        this.expectedRedirectUri = expectedRedirectUri == null ? "" : expectedRedirectUri.trim();
        this.http = Objects.requireNonNull(http);
        this.jwtProcessor = Objects.requireNonNull(jwtProcessor);
        this.tokenEndpoint = Objects.requireNonNull(tokenEndpoint);
    }

    @Override public TokenResponse exchange(String code, String verifier, String redirectUri) {
        if (code == null || code.isBlank() || code.length() > 4096
                || verifier == null || !verifier.matches("[A-Za-z0-9._~-]{43,128}")
                || redirectUri == null || !isHttpsUri(redirectUri) || expectedRedirectUri.isBlank()
                || !expectedRedirectUri.equals(redirectUri)) {
            throw new IllegalArgumentException("La respuesta OAuth de Microsoft no es válida");
        }
        return requestToken(true, "authorization_code", "code", code,
                "code_verifier", verifier, "redirect_uri", redirectUri);
    }

    @Override public TokenResponse refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank() || refreshToken.length() > 16384) {
            throw new IllegalArgumentException("La renovación OAuth de Microsoft no es válida");
        }
        return requestToken(false, "refresh_token", "refresh_token", refreshToken);
    }

    @Override public AuthorizedIdentity validateIdentity(String idToken, String expectedNonce) {
        if (idToken == null || idToken.length() > 32_768 || expectedNonce == null || expectedNonce.length() > 256) {
            throw new IllegalArgumentException("La identidad de Microsoft no pudo validarse");
        }
        requireConfigured();
        try {
            JWTClaimsSet claims = jwtProcessor.process(idToken, null);
            Date expiration = claims.getExpirationTime();
            Date notBefore = claims.getNotBeforeTime();
            Date now = new Date();
            if (expiration == null || !expiration.after(now)
                    || notBefore != null && notBefore.after(new Date(now.getTime() + 30_000))) {
                throw new IllegalArgumentException("La identidad de Microsoft no pudo validarse");
            }
            if (!claims.getAudience().contains(clientId)
                    || !expectedNonce.equals(claims.getStringClaim("nonce"))) {
                throw new IllegalArgumentException("La identidad de Microsoft no pudo validarse");
            }
            String tenant = claims.getStringClaim("tid");
            String issuer = claims.getIssuer();
            if (!CONSUMER_TENANT_ID.equalsIgnoreCase(tenant)
                    || !("https://login.microsoftonline.com/" + CONSUMER_TENANT_ID + "/v2.0").equals(issuer)) {
                throw new IllegalArgumentException("Solo se admiten cuentas personales Microsoft autorizadas");
            }
            String email = firstNonBlank(claims.getStringClaim("email"), claims.getStringClaim("preferred_username"));
            String subject = claims.getSubject();
            if (email == null || subject == null || subject.isBlank()) {
                throw new IllegalArgumentException("La identidad de Microsoft no pudo validarse");
            }
            return new AuthorizedIdentity(email.trim().toLowerCase(Locale.ROOT), subject);
        } catch (IllegalArgumentException invalid) {
            throw invalid;
        } catch (Exception invalid) {
            // Do not propagate Nimbus or JWT parser messages: they may contain token fragments.
            throw new IllegalArgumentException("La identidad de Microsoft no pudo validarse");
        }
    }

    private TokenResponse requestToken(boolean requireIdToken, String grantType, String... keyValues) {
        requireConfigured();
        StringBuilder body = new StringBuilder("grant_type=").append(encode(grantType))
                .append("&client_id=").append(encode(clientId))
                .append("&client_secret=").append(encode(clientSecret));
        for (int i = 0; i < keyValues.length; i += 2) {
            body.append('&').append(encode(keyValues[i])).append('=').append(encode(keyValues[i + 1]));
        }
        body.append("&scope=").append(encode(String.join(" ", ALLOWED_SCOPES)));
        HttpRequest request = HttpRequest.newBuilder(tokenEndpoint)
                .timeout(Duration.ofSeconds(8))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build();
        try {
            HttpResponse<java.io.InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            byte[] responseBytes;
            try (var input = response.body()) { responseBytes = input.readNBytes(MAX_BODY_BYTES + 1); }
            if (responseBytes.length > MAX_BODY_BYTES) {
                java.util.Arrays.fill(responseBytes, (byte) 0);
                throw new MicrosoftOAuthClientException("INVALID_RESPONSE");
            }
            JsonNode root;
            try { root = json.readTree(responseBytes); }
            finally { java.util.Arrays.fill(responseBytes, (byte) 0); }
            if (status < 200 || status >= 300) {
                throw new MicrosoftOAuthClientException(safeOAuthError(root.path("error").asText(null), status));
            }
            String access = nonBlank(root.path("access_token").asText(null));
            String refresh = nonBlank(root.path("refresh_token").asText(null));
            String idToken = nonBlank(root.path("id_token").asText(null));
            long expiresIn = root.path("expires_in").asLong(0);
            if (access == null || requireIdToken && idToken == null || expiresIn < 60 || expiresIn > 86_400) {
                throw new MicrosoftOAuthClientException("INVALID_RESPONSE");
            }
            return new TokenResponse(access, refresh, expiresIn, idToken);
        } catch (MicrosoftOAuthClientException safe) {
            throw safe;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new MicrosoftOAuthClientException("TEMPORARY_FAILURE");
        } catch (IOException | RuntimeException failure) {
            if (failure instanceof IllegalArgumentException argument && "Microsoft OAuth no está configurado".equals(argument.getMessage())) throw argument;
            throw new MicrosoftOAuthClientException("TEMPORARY_FAILURE");
        }
    }

    private static String safeOAuthError(String error, int status) {
        if ("invalid_grant".equals(error)) return "INVALID_GRANT";
        if ("invalid_client".equals(error)) return "INVALID_CLIENT";
        if ("invalid_scope".equals(error)) return "INVALID_SCOPE";
        if ("invalid_request".equals(error)) return "INVALID_REQUEST";
        if (status == 429 || status >= 500) return "TEMPORARY_FAILURE";
        return "INVALID_RESPONSE";
    }

    private void requireConfigured() {
        if (clientId.isBlank() || clientSecret.isBlank()) throw new IllegalArgumentException("Microsoft OAuth no está configurado");
    }

    private static DefaultJWTProcessor<SecurityContext> jwksProcessor() {
        try {
            ResourceRetriever retriever = new DefaultResourceRetriever(3_000, 3_000, 128 * 1024);
            RemoteJWKSet<SecurityContext> keys = new RemoteJWKSet<>(URI.create(JWKS_ENDPOINT).toURL(), retriever);
            JWSKeySelector<SecurityContext> selector = new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, keys);
            DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
            processor.setJWSKeySelector(selector);
            processor.setJWTClaimsSetVerifier((claims, context) -> validateMicrosoftClaims(claims));
            return processor;
        } catch (Exception failure) {
            throw new IllegalStateException("No se pudo preparar la validación de identidad Microsoft");
        }
    }

    private static void validateMicrosoftClaims(JWTClaimsSet claims) throws BadJWTException {
        String tenant;
        try { tenant = claims.getStringClaim("tid"); }
        catch (java.text.ParseException invalid) { throw new BadJWTException("Invalid tenant claim"); }
        String issuer = claims.getIssuer();
        if (!CONSUMER_TENANT_ID.equalsIgnoreCase(tenant)
                || !("https://login.microsoftonline.com/" + CONSUMER_TENANT_ID + "/v2.0").equals(issuer)) {
            throw new BadJWTException("Untrusted Microsoft issuer");
        }
    }

    private static boolean isHttpsUri(String value) {
        try {
            URI uri = URI.create(value);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                    && uri.getUserInfo() == null && uri.getFragment() == null;
        } catch (RuntimeException invalid) { return false; }
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static String nonBlank(String value) { return value == null || value.isBlank() ? null : value; }
    private static String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return null;
    }

    public static final class MicrosoftOAuthException extends RuntimeException {
        private final int statusCode;
        MicrosoftOAuthException(int statusCode) {
            super("Microsoft OAuth rechazó la solicitud (HTTP " + statusCode + ")");
            this.statusCode = statusCode;
        }
        public int statusCode() { return statusCode; }
        @Override public String toString() { return "MicrosoftOAuthException[statusCode=" + statusCode + "]"; }
    }
}
