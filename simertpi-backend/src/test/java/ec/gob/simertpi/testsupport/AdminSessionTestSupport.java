package ec.gob.simertpi.testsupport;

import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.Map;

/** Test helper that exercises the same cookie and CSRF login contract as the admin SPA. */
public final class AdminSessionTestSupport {
    private static final String CSRF_COOKIE = "__Host-SIMERTPI_ADMIN_XSRF";
    private static final String SESSION_COOKIE = "__Host-SIMERTPI_ADMIN_SESSION";

    private AdminSessionTestSupport() {}

    public record Session(String sessionCookie, String csrfToken) {
        public HttpHeaders headers(boolean csrfRequired) {
            var headers = new HttpHeaders();
            headers.add(HttpHeaders.COOKIE, sessionCookie + "; " + CSRF_COOKIE + "=" + csrfToken);
            if (csrfRequired) headers.add("X-XSRF-TOKEN", csrfToken);
            return headers;
        }
    }

    public static Session login(TestRestTemplate http, int port, String username, String password) {
        String base = "http://localhost:" + port + "/api/v1/admin/auth";
        var csrfResponse = http.getForEntity(base + "/csrf", Void.class);
        String initialCsrf = cookie(csrfResponse, CSRF_COOKIE);

        var loginHeaders = new HttpHeaders();
        loginHeaders.setContentType(MediaType.APPLICATION_JSON);
        loginHeaders.add(HttpHeaders.COOKIE, CSRF_COOKIE + "=" + initialCsrf);
        loginHeaders.add("X-XSRF-TOKEN", initialCsrf);
        var loginResponse = http.exchange(base + "/login", HttpMethod.POST,
                new HttpEntity<>(Map.of("username", username, "password", password), loginHeaders), String.class);
        if (!loginResponse.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("Administrative test login failed with HTTP " + loginResponse.getStatusCode().value());
        }
        return new Session(cookie(loginResponse, SESSION_COOKIE), cookie(loginResponse, CSRF_COOKIE));
    }

    public static Session login(MockMvc mvc, String username, String password) throws Exception {
        var csrfResponse = mvc.perform(get("/api/v1/admin/auth/csrf").secure(true))
                .andExpect(status().isNoContent()).andReturn();
        var csrfCookie = mvcCookie(csrfResponse, CSRF_COOKIE);
        String body = "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}";
        var loginResponse = mvc.perform(post("/api/v1/admin/auth/login").secure(true).cookie(csrfCookie)
                        .header("X-XSRF-TOKEN", csrfCookie.getValue()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        return new Session(mvcCookie(loginResponse, SESSION_COOKIE).getValue(), mvcCookie(loginResponse, CSRF_COOKIE).getValue());
    }

    public static jakarta.servlet.http.Cookie sessionCookie(Session session) {
        return new jakarta.servlet.http.Cookie(SESSION_COOKIE, session.sessionCookie());
    }

    public static jakarta.servlet.http.Cookie csrfCookie(Session session) {
        return new jakarta.servlet.http.Cookie(CSRF_COOKIE, session.csrfToken());
    }

    private static jakarta.servlet.http.Cookie mvcCookie(MvcResult result, String name) {
        return result.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
                .filter(value -> value.startsWith(name + "="))
                .map(value -> new jakarta.servlet.http.Cookie(name, value.substring(name.length() + 1).split(";", 2)[0]))
                .findFirst().orElseThrow(() -> new IllegalStateException("Expected test cookie " + name + " was not returned"));
    }

    private static String cookie(ResponseEntity<?> response, String name) {
        return response.getHeaders().get(HttpHeaders.SET_COOKIE).stream()
                .filter(value -> value.startsWith(name + "="))
                .map(value -> value.substring(name.length() + 1).split(";", 2)[0])
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Expected test cookie " + name + " was not returned"));
    }
}
