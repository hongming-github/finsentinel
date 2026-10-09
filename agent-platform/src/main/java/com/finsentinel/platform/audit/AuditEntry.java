package com.finsentinel.platform.audit;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Map;
import java.util.TreeMap;

/**
 * One access to record. Identity fields come from a verified token only; all are null for a request that had none.
 *
 * @param action   what was asked, e.g. {@code GET /api/tenants/{tenantId}/cases/{caseId}} or {@code mcp getCase}
 * @param resource the case concerned, when there is one
 * @param outcome  the HTTP status, or OK / NOT_FOUND / ERROR for an MCP tool
 */
public record AuditEntry(String tenant, String subject, String username, String clientId, String action,
                         String resource, String outcome, Map<String, String> detail) {

    public AuditEntry {
        if (action == null || outcome == null) {
            throw new IllegalArgumentException("an audit entry needs an action and an outcome");
        }
        detail = detail == null ? Map.of() : new TreeMap<>(detail);
    }

    /** An entry for the current request's caller, as far as the security chain has verified one. */
    public static AuditEntry forCurrentCaller(String action, String resource, String outcome, Map<String, String> detail) {
        if (SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken token) {
            Jwt jwt = token.getToken();
            return new AuditEntry(jwt.getClaimAsString("tenant"), jwt.getSubject(),
                    jwt.getClaimAsString("preferred_username"), jwt.getClaimAsString("azp"), action, resource, outcome,
                    detail);
        }
        return new AuditEntry(null, null, null, null, action, resource, outcome, detail);
    }

    /** The chain this entry extends: its bank's, or the one for requests without a verified tenant. */
    String chain() {
        return tenant == null ? AuditLog.NO_TENANT_CHAIN : tenant;
    }
}
