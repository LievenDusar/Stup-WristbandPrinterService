package com.stup.wristbandprinter.security;

import com.stup.wristbandprinter.config.CorsProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Echoes the Private Network Access (PNA) opt-in header so Chromium browsers permit a request from a
 * public page (the Symfony site) when this service resolves to a private/internal IP. The header is
 * only set on a CORS preflight that explicitly asks for it ({@code Access-Control-Request-Private-Network:
 * true}) AND for an origin already on the CORS allow-list — so we never open private-network access to
 * arbitrary sites.
 *
 * <p>Runs <em>before</em> Spring Security's {@code CorsFilter} (which short-circuits the preflight), so
 * the header is on the response by the time the preflight is answered.
 *
 * <p>PNA is browser-specific and still evolving — modern Chrome may still prompt the user, and other
 * browsers may not honour the header at all. The robust, cross-browser solution remains a server-side
 * proxy; see {@code docs/symfony-proxy-integration.md}.
 */
public class PrivateNetworkAccessFilter extends OncePerRequestFilter {

    private static final String REQUEST_HEADER = "Access-Control-Request-Private-Network";
    private static final String RESPONSE_HEADER = "Access-Control-Allow-Private-Network";

    private final CorsProperties corsProperties;

    public PrivateNetworkAccessFilter(CorsProperties corsProperties) {
        this.corsProperties = corsProperties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())
            && "true".equalsIgnoreCase(request.getHeader(REQUEST_HEADER))) {
            String origin = request.getHeader("Origin");
            if (origin != null && corsProperties.getAllowedOrigins().contains(origin)) {
                response.setHeader(RESPONSE_HEADER, "true");
            }
        }
        chain.doFilter(request, response);
    }
}
