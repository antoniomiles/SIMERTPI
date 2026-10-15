package ec.gob.simertpi.config;

import ec.gob.simertpi.application.identity.auth.MunicipalAuthService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

/** Resolves only municipal-audience bearer sessions; citizen bearer behavior remains in MobileBearerFilter. */
public class MunicipalBearerFilter extends OncePerRequestFilter {
    private final MunicipalAuthService municipal;

    public MunicipalBearerFilter(MunicipalAuthService municipal) {
        this.municipal = municipal;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            chain.doFilter(request, response);
            return;
        }

        String token = header.substring(7);
        boolean municipalOnly = requiresMunicipalAudience(request);
        boolean municipalCapable = municipalOnly || supportsMunicipalToken(request);
        if (!municipalCapable) {
            chain.doFilter(request, response);
            return;
        }
        boolean municipalToken = municipalOnly || municipal.isMunicipalToken(token);
        if (!municipalToken) {
            if (municipalOnly) reject(response);
            else chain.doFilter(request, response);
            return;
        }
        if (municipalToken && citizenOnlyEndpoint(request)) {
            reject(response);
            return;
        }

        final org.springframework.security.core.userdetails.UserDetails user;
        try {
            user = municipal.authenticate(token);
        } catch (AuthenticationException exception) {
            SecurityContextHolder.clearContext();
            reject(response);
            return;
        }

        var authentication = UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities());
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        chain.doFilter(request, response);
    }

    private static boolean requiresMunicipalAudience(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path.startsWith("/api/v1/municipal/")) return true;
        if (under(path, "/api/v1/inspections") || under(path, "/api/v1/violations")
                || under(path, "/api/v1/evidence")) return true;
        return "POST".equals(request.getMethod())
                && path.matches("/api/v1/parking-sessions/[^/]+/verbal-warnings");
    }

    private static boolean supportsMunicipalToken(HttpServletRequest request) {
        if (!"GET".equals(request.getMethod())) return false;
        String path = request.getRequestURI();
        return path.equals("/api/v1/parking/rules") || path.equals("/api/v1/parking/rules/options")
                || under(path, "/api/v1/vehicles/plate") || under(path, "/api/v1/vehicles/user")
                || under(path, "/api/v1/permits/vehicle")
                || path.equals("/api/v1/permits/active") || path.equals("/api/v1/permits/expired")
                || path.matches("/api/v1/permits/[^/]+");
    }

    private static boolean citizenOnlyEndpoint(HttpServletRequest request) {
        String path = request.getRequestURI();
        String method = request.getMethod();
        return path.equals("/api/v1/users")
                || path.equals("/api/v1/users/mine")
                || path.equals("/api/v1/parking-spaces/availability")
                || path.startsWith("/api/v1/payments/")
                || path.equals("/api/v1/payments")
                || under(path, "/api/v1/notifications")
                || under(path, "/api/v1/citizen")
                || path.equals("/api/v1/permits/mine")
                || ("POST".equals(method) && (path.equals("/api/v1/parking/sessions") || path.equals("/api/v1")))
                || ("POST".equals(method) && path.matches("/api/v1/parking/sessions/[^/]+/abandon"))
                || path.matches("/api/v1/parking-sessions/[^/]+/extensions(?:/.*)?")
                || ("POST".equals(method) && path.equals("/api/v1/vehicles"))
                || ("PUT".equals(method) && path.matches("/api/v1/vehicles/[^/]+/deactivation"));
    }

    private static boolean under(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }

    private static void reject(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write("{\"status\":401,\"message\":\"Authentication required\"}");
    }
}
