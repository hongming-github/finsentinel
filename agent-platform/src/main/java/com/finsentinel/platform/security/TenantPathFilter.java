package com.finsentinel.platform.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.http.server.PathContainer;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.io.IOException;

/**
 * Runs after authorization: a {@code /api/tenants/{tenantId}/...} request goes on only when {@code tenantId} is the
 * caller's own tenant. Anything else is answered 404, the same as a case that does not exist, so a caller cannot even
 * learn which tenants have data. A token without a tenant gets 404 everywhere under {@code /api/tenants}.
 *
 * <p>A filter rather than a check in each controller: a new endpoint under the path is covered without anyone
 * remembering to add the check.
 */
class TenantPathFilter extends OncePerRequestFilter {

    private static final PathPattern TENANT_PATH = PathPatternParser.defaultInstance.parse("/api/tenants/{tenantId}/**");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        PathPattern.PathMatchInfo match = TENANT_PATH.matchAndExtract(
                PathContainer.parsePath(request.getRequestURI().substring(request.getContextPath().length())));
        if (match != null) {
            String pathTenant = match.getUriVariables().get("tenantId");
            boolean own = Caller.current().map(Caller::tenant).filter(pathTenant::equals).isPresent();
            if (!own) {
                response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.getWriter().write("{\"error\":\"not found\"}");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
