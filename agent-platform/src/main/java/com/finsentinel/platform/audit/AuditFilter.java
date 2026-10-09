package com.finsentinel.platform.audit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.util.Map;

/**
 * Records every request that reaches the platform, after it is answered: who (from the verified token only), what
 * (the route, e.g. {@code GET /api/tenants/{tenantId}/cases/{caseId}}, or the raw path when no route was reached),
 * which case, and the status. Refusals are recorded too: 401, 403, and the 404 of another bank's path are exactly
 * what an audit is for. It sits before token authentication, so it sees the final status of every request, and reads
 * the identity the chain established; claims of a token that failed verification are never trusted or stored.
 *
 * <p>Not recorded: health and metadata endpoints, and successful MCP requests (the tools record which case they
 * read; protocol messages such as initialize and tools/list carry no customer data).
 */
public class AuditFilter extends OncePerRequestFilter {

    private final AuditLog audit;

    public AuditFilter(AuditLog audit) {
        this.audit = audit;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return path.startsWith("/actuator/health") || path.equals("/actuator/info")
                || path.startsWith("/.well-known/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        boolean failed = true;
        try {
            chain.doFilter(request, response);
            failed = false;
        } finally {
            int status = failed ? 500 : response.getStatus();
            String path = request.getRequestURI().substring(request.getContextPath().length());
            if (!(path.startsWith("/mcp") && status < 400)) {
                audit.record(entry(request, path, status));
            }
        }
    }

    private static AuditEntry entry(HttpServletRequest request, String path, int status) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String action = request.getMethod() + " " + (pattern == null ? path : pattern);
        @SuppressWarnings("unchecked")
        Map<String, String> variables = (Map<String, String>) request.getAttribute(
                HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        String caseId = variables == null ? null : variables.get("caseId");
        Map<String, String> detail = pattern == null ? Map.of() : Map.of("path", path);
        return AuditEntry.forCurrentCaller(action, caseId, String.valueOf(status), detail);
    }
}
