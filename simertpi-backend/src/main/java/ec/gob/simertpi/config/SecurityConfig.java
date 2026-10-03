package ec.gob.simertpi.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    private static final String[] PUBLIC_CATALOG_GETS = {
            "/api/v1/zones", "/api/v1/zones/**",
            "/api/v1/streets", "/api/v1/streets/**",
            "/api/v1/parking-spaces", "/api/v1/parking-spaces/**",
            "/api/v1/tariffs", "/api/v1/tariffs/**"
    };

    private static final String[] VEHICLE_READERS = {
            "CITIZEN", "INSPECTOR", "SUPERVISOR", "SIMERTPI_ADMIN", "IT_ADMIN", "AUDITOR"
    };
    private static final String[] PERMIT_OPERATORS = {
            "INSPECTOR", "SUPERVISOR", "SIMERTPI_ADMIN"
    };
    private static final String[] PERMIT_READERS = {
            "CITIZEN", "INSPECTOR", "SUPERVISOR", "SIMERTPI_ADMIN"
    };

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health").permitAll()
                .requestMatchers("/actuator/info").hasAuthority("SIMERTPI_ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/v1/users").permitAll()
                .requestMatchers(HttpMethod.GET, PUBLIC_CATALOG_GETS).permitAll()
                .requestMatchers("/api/v1/payments/webhooks/**").denyAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/payments")
                    .hasAuthority("CITIZEN")
                .requestMatchers("/api/v1/payments/**").authenticated()
                .requestMatchers(HttpMethod.GET, "/api/v1/users/username/**")
                    .hasAuthority("SIMERTPI_ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/v1/parking/sessions", "/api/v1")
                    .hasAuthority("CITIZEN")
                .requestMatchers(HttpMethod.GET, "/api/v1/parking/rules")
                    .hasAnyAuthority("CITIZEN", "INSPECTOR", "SUPERVISOR", "SIMERTPI_ADMIN")
                .requestMatchers("/api/v1/inspections/**", "/api/v1/violations/**")
                    .hasAuthority("INSPECTOR")
                .requestMatchers(HttpMethod.POST, "/api/v1/evidence")
                    .hasAuthority("INSPECTOR")
                .requestMatchers(HttpMethod.GET, "/api/v1/evidence/**")
                    .hasAnyAuthority("INSPECTOR", "SUPERVISOR", "SIMERTPI_ADMIN")
                .requestMatchers("/api/v1/evidence/**").denyAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/permits/mine")
                    .hasAuthority("CITIZEN")
                .requestMatchers(HttpMethod.GET, "/api/v1/permits/vehicle/**",
                        "/api/v1/permits/active", "/api/v1/permits/expired")
                    .hasAnyAuthority(PERMIT_OPERATORS)
                .requestMatchers(HttpMethod.GET, "/api/v1/permits/{id}")
                    .hasAnyAuthority(PERMIT_READERS)
                .requestMatchers(HttpMethod.POST, "/api/v1/permits")
                    .hasAuthority("SIMERTPI_ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/api/v1/permits/{id}")
                    .hasAuthority("SIMERTPI_ADMIN")
                .requestMatchers("/api/v1/permits/**").denyAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/notifications/mine")
                    .hasAuthority("CITIZEN")
                .requestMatchers(HttpMethod.PATCH, "/api/v1/notifications/*/read")
                    .hasAuthority("CITIZEN")
                .requestMatchers("/api/v1/notifications/**").denyAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/admin/reconciliation/payments",
                        "/api/v1/admin/reconciliation/evidence", "/api/v1/admin/reconciliation/outbox")
                    .hasAuthority("SIMERTPI_ADMIN")
                .requestMatchers("/api/v1/admin/reconciliation/**").denyAll()
                .requestMatchers("/api/v1/audit/**").hasAuthority("SIMERTPI_ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/v1/vehicles")
                    .hasAuthority("CITIZEN")
                .requestMatchers(HttpMethod.GET, "/api/v1/vehicles/plate/**")
                    .hasAnyAuthority("INSPECTOR", "SUPERVISOR", "SIMERTPI_ADMIN", "IT_ADMIN", "AUDITOR")
                .requestMatchers(HttpMethod.GET, "/api/v1/vehicles/user/**")
                    .hasAnyAuthority(VEHICLE_READERS)
                .requestMatchers(HttpMethod.POST, "/api/v1/zones", "/api/v1/streets",
                        "/api/v1/parking-spaces", "/api/v1/tariffs")
                    .hasAuthority("SIMERTPI_ADMIN")
                .requestMatchers("/api/v1/parking-sessions/**").authenticated()
                .requestMatchers("/api/v1/**").authenticated()
                .anyRequest().denyAll()
            )
            .httpBasic(Customizer.withDefaults());

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
