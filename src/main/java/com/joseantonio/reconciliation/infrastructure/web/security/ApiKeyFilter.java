package com.joseantonio.reconciliation.infrastructure.web.security;

import com.joseantonio.reconciliation.infrastructure.config.ReconciliationProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Servlet filter in front of the whole application: adds baseline security headers and enforces the API key
 * (see {@link ApiKeyVerifier}). A missing or wrong key yields {@code 401} as {@code application/problem+json}; the response
 * never says whether a key was missing or merely wrong.
 */
@Component
public class ApiKeyFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyFilter.class);
    private static final String UNAUTHORIZED_BODY = "{\"type\":\"about:blank\",\"title\":\"Unauthorized\",\"status\":401,"
            + "\"detail\":\"A valid X-API-Key header is required\",\"code\":\"UNAUTHORIZED\"}";

    private final ApiKeyVerifier verifier;

    public ApiKeyFilter(ReconciliationProperties properties) {
        this.verifier = ApiKeyVerifier.of(properties.security().apiKey(), properties.security().apiKeyRequired());
        if (!verifier.enabled()) {
            log.warn("API key authentication is DISABLED (reconciliation.security.api-key is blank). "
                    + "This is acceptable for local development only.");
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "no-referrer");
        // Decoded and normalised by the container: never use getRequestURI() for security decisions.
        String path = request.getServletPath() + (request.getPathInfo() == null ? "" : request.getPathInfo());
        if (path.startsWith("/api/")) {
            response.setHeader("Cache-Control", "no-store");
        }
        if (!verifier.authorize(path, request.getHeader(ApiKeyVerifier.HEADER))) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setHeader("WWW-Authenticate", "ApiKey realm=\"reconciliation\"");
            response.setContentType("application/problem+json");
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write(UNAUTHORIZED_BODY);
            return;
        }
        chain.doFilter(request, response);
    }
}
