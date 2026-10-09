package com.finsentinel.platform.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.List;
import java.util.Optional;

/**
 * Who is calling, from the access token. The token is the only source of the tenant: a path or tool parameter can
 * name a tenant, but never grants access to it.
 *
 * @param tenant   the bank the caller belongs to (claim {@value #TENANT_CLAIM}); null for service accounts that act
 *                 for no single bank
 * @param subject  stable id of the user or client ({@code sub})
 * @param username readable name for case records ({@code preferred_username}, else {@code sub})
 */
public record Caller(String tenant, String subject, String username, List<String> roles) {

    public static final String TENANT_CLAIM = "tenant";
    public static final String ROLES_CLAIM = "roles";

    public static Caller of(Jwt jwt) {
        String username = jwt.getClaimAsString("preferred_username");
        List<String> roles = jwt.getClaimAsStringList(ROLES_CLAIM);
        return new Caller(jwt.getClaimAsString(TENANT_CLAIM), jwt.getSubject(),
                username == null ? jwt.getSubject() : username, roles == null ? List.of() : List.copyOf(roles));
    }

    /** The authenticated caller of the current request; empty outside a request or without a bearer token. */
    public static Optional<Caller> current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication instanceof JwtAuthenticationToken token ? Optional.of(of(token.getToken())) : Optional.empty();
    }

    /** The caller's tenant, failing closed: a request without one never reaches tenant data. */
    public static String currentTenant() {
        return current().map(Caller::tenant).filter(t -> !t.isBlank())
                .orElseThrow(() -> new TenantRequiredException());
    }

    /** No tenant in the token, where tenant data was asked for. */
    public static final class TenantRequiredException extends RuntimeException {

        TenantRequiredException() {
            super("the access token carries no tenant");
        }
    }
}
