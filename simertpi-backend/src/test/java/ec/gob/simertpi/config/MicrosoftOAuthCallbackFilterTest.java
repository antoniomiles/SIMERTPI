package ec.gob.simertpi.config;

import ec.gob.simertpi.application.identity.admin.AdminAuthenticationService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class MicrosoftOAuthCallbackFilterTest {
    @Test void onlyExactCallbackGetMayReturnCrossSiteWithoutAdminCookie() throws Exception {
        AdminAuthenticationService auth = mock(AdminAuthenticationService.class);
        AdminSessionFilter filter = new AdminSessionFilter(auth, "https://admin.example.invalid");
        var callback = request("GET", "/api/v1/admin/system/email/oauth/callback");
        callback.addHeader("Sec-Fetch-Site", "cross-site");
        var callbackResponse = new MockHttpServletResponse();
        var callbackChain = new MockFilterChain();
        filter.doFilter(callback, callbackResponse, callbackChain);
        assertThat(callbackResponse.getStatus()).isEqualTo(200);
        assertThat(callbackChain.getRequest()).isSameAs(callback);
        verifyNoInteractions(auth);

        var neighboring = request("GET", "/api/v1/admin/system/email/oauth/status");
        neighboring.addHeader("Sec-Fetch-Site", "cross-site");
        var deniedResponse = new MockHttpServletResponse();
        filter.doFilter(neighboring, deniedResponse, new MockFilterChain());
        assertThat(deniedResponse.getStatus()).isEqualTo(403);
        verifyNoInteractions(auth);

        var methodChanged = request("POST", "/api/v1/admin/system/email/oauth/callback");
        methodChanged.addHeader("Sec-Fetch-Site", "cross-site");
        var methodResponse = new MockHttpServletResponse();
        filter.doFilter(methodChanged, methodResponse, new MockFilterChain());
        assertThat(methodResponse.getStatus()).isEqualTo(403);
        verifyNoInteractions(auth);
    }

    private static MockHttpServletRequest request(String method, String path) {
        var request = new MockHttpServletRequest(method, path);
        request.setSecure(true);
        request.setServerName("backend.example.invalid");
        request.setServerPort(443);
        return request;
    }
}
