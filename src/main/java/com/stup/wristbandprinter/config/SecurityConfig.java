package com.stup.wristbandprinter.config;

import com.stup.wristbandprinter.security.ApiKeyAuthFilter;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;
@Profile("!worker")
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(CorsProperties.class)
@SecurityScheme(
    name = "ApiKeyAuth",
    type = SecuritySchemeType.APIKEY,
    in = SecuritySchemeIn.HEADER,
    paramName = "X-API-Key"
)
public class SecurityConfig {

    private final ApiKeyAuthFilter apiKeyAuthFilter;

    public SecurityConfig(ApiKeyAuthFilter apiKeyAuthFilter) {
        this.apiKeyAuthFilter = apiKeyAuthFilter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            // CORS allow-list driven by cors.allowed-origins (see corsConfigurationSource).
            .cors(Customizer.withDefaults())
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // CORS preflight carries no API key (browsers never send custom headers on it);
                // it must pass without auth or the real cross-origin request never fires.
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                // Static admin UI shells and the login endpoint are public.
                .requestMatchers(
                    "/jobs.html",
                    "/template-editor.html",
                    "/login.html",
                    "/css/**",
                    "/js/**",
                    "/api/wristbands/login",
                    "/api/wristbands/logout",
                    "/swagger-ui.html",
                    "/swagger-ui/**",
                    "/v3/api-docs/**",
                    "/actuator/health"
                ).permitAll()
                // Print + preview accept the limited print-only key OR the admin key/cookie.
                .requestMatchers(HttpMethod.POST,
                    "/api/wristbands/print",
                    "/api/wristbands/preview/zpl",
                    "/api/wristbands/preview/image"
                ).hasAnyRole("PRINT", "ADMIN")
                // Everything else (jobs, SSE stream, reprint/cancel, printers, templates) is
                // admin-only — the print-only key cannot reach it.
                .anyRequest().hasRole("ADMIN")
            )
            .exceptionHandling(ex -> ex
                // Return 401 (not Spring Security's default 403) for unauthenticated requests
                .authenticationEntryPoint((req, res, e) ->
                    res.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized"))
                .accessDeniedHandler((req, res, e) ->
                    res.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized"))
            )
            .addFilterBefore(apiKeyAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /**
     * CORS for browser callers. Empty allow-list (the default) means no cross-origin request is
     * permitted; same-origin traffic is unaffected. Configure {@code cors.allowed-origins} to the
     * exact STUP origin(s). The API key travels as the {@code X-API-Key} header (not a cookie), so
     * credentials are not enabled.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(CorsProperties corsProperties) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(corsProperties.getAllowedOrigins());
        config.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        config.setAllowedHeaders(List.of("X-API-Key", "Content-Type"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
