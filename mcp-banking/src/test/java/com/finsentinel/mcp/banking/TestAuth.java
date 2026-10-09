package com.finsentinel.mcp.banking;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.util.JSONObjectUtils;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * A stand-in for the Keycloak realm (OpenID discovery and key set, in process) and tokens signed by it, so tests run
 * the production JWT decoder. Mirrors the platform's TestIssuer; MCP servers belong to other teams and share no code.
 */
final class TestAuth {

    private static final KeyPair KEYS = generate();
    private static final HttpServer SERVER = start();

    private TestAuth() {
    }

    static String issuer() {
        return "http://localhost:" + SERVER.getAddress().getPort() + "/realms/finsentinel";
    }

    /** A token for {@code audience} with these realm roles, like the platform's service account gets. */
    static String token(String audience, String... roles) {
        JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer(issuer()).audience(audience)
                .subject("service-account-platform-service").claim("roles", List.of(roles))
                .issueTime(new Date()).expirationTime(Date.from(Instant.now().plusSeconds(300))).build();
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key").build(), claims);
            jwt.sign(new RSASSASigner(KEYS.getPrivate()));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static HttpServer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/realms/finsentinel/.well-known/openid-configuration", exchange -> respond(exchange,
                    JSONObjectUtils.toJSONString(Map.of("issuer", issuer(), "subject_types_supported", List.of("public"),
                            "jwks_uri", issuer() + "/protocol/openid-connect/certs"))));
            server.createContext("/realms/finsentinel/protocol/openid-connect/certs", exchange -> respond(exchange,
                    new JWKSet(new RSAKey.Builder((RSAPublicKey) KEYS.getPublic()).keyID("test-key").build()).toString()));
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static KeyPair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
