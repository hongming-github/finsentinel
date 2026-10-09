package com.finsentinel.platform;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Access tokens shaped like the realm's (deploy/keycloak), signed by {@link TestIssuer}, which the platform trusts
 * under {@link PlatformTest}. Requests carrying them go through the real security chain.
 */
public final class TestTokens {

    public static final String AUDIENCE = "finsentinel-platform";

    private TestTokens() {
    }

    public static String analyst(String tenant) {
        return token(tenant, "ana-" + tenant, "analyst");
    }

    public static String supervisor(String tenant) {
        return token(tenant, "sup-" + tenant, "supervisor");
    }

    public static String assistant(String tenant) {
        return token(tenant, "service-account-analyst-assistant", "assistant");
    }

    public static String token(String tenant, String username, String... roles) {
        return sign(claims(tenant, username, roles).build());
    }

    /** The claims a realm token for this user would carry; tests change one to see it rejected. */
    public static JWTClaimsSet.Builder claims(String tenant, String username, String... roles) {
        Instant now = Instant.now();
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(TestIssuer.uri())
                .audience(AUDIENCE)
                .subject(UUID.nameUUIDFromBytes(username.getBytes(StandardCharsets.UTF_8)).toString())
                .claim("preferred_username", username)
                .claim("roles", List.of(roles))
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(Duration.ofMinutes(5))));
        if (tenant != null) {
            claims.claim("tenant", tenant);
        }
        return claims;
    }

    public static String sign(JWTClaimsSet claims) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(TestIssuer.KEY_ID).build(),
                    claims);
            jwt.sign(new RSASSASigner(TestIssuer.privateKey()));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** MockMvc: send the request with this bearer token. */
    public static RequestPostProcessor bearer(String token) {
        return request -> {
            request.addHeader("Authorization", "Bearer " + token);
            return request;
        };
    }
}
