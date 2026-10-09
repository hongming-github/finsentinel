package com.finsentinel.platform;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Map;

/**
 * A stand-in for the Keycloak realm, in process: OpenID discovery and a JWKS with a key generated at startup. The
 * platform under test is configured with its issuer URI exactly as with Keycloak, so the production decoder runs
 * (discovery, key set, issuer and audience checks). Tests sign tokens with {@link #privateKey()} via {@link TestTokens}.
 */
public final class TestIssuer {

    static final String KEY_ID = "test-key";
    private static final KeyPair KEYS = generate();
    private static final java.util.concurrent.atomic.AtomicInteger TOKENS_ISSUED = new java.util.concurrent.atomic.AtomicInteger();
    private static final HttpServer SERVER = start();

    private TestIssuer() {
    }

    public static String uri() {
        return "http://localhost:" + SERVER.getAddress().getPort() + "/realms/finsentinel";
    }

    /** Client-credentials tokens handed out so far. */
    public static int tokensIssued() {
        return TOKENS_ISSUED.get();
    }

    static RSAPrivateKey privateKey() {
        return (RSAPrivateKey) KEYS.getPrivate();
    }

    private static HttpServer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/realms/finsentinel/.well-known/openid-configuration", exchange -> {
                String issuer = uri();
                respond(exchange, toJson(Map.of("issuer", issuer, "jwks_uri", issuer + "/protocol/openid-connect/certs",
                        "token_endpoint", issuer + "/protocol/openid-connect/token",
                        "subject_types_supported", java.util.List.of("public"))));
            });
            // Client credentials for the platform's service account, as the realm's platform-service client.
            server.createContext("/realms/finsentinel/protocol/openid-connect/token", exchange -> {
                String form = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String auth = exchange.getRequestHeaders().getFirst("Authorization");
                String expected = "Basic " + java.util.Base64.getEncoder().encodeToString(
                        "platform-service:platform-service-dev-secret".getBytes(StandardCharsets.UTF_8));
                if (!form.contains("grant_type=client_credentials") || !expected.equals(auth)) {
                    exchange.sendResponseHeaders(401, -1);
                    exchange.close();
                    return;
                }
                TOKENS_ISSUED.incrementAndGet();
                String token = TestTokens.sign(TestTokens.claims(null, "service-account-platform-service", "systems-reader")
                        .audience(java.util.List.of("mcp-banking", "mcp-compliance")).build());
                respond(exchange, toJson(Map.of("access_token", token, "token_type", "Bearer", "expires_in", 300)));
            });
            server.createContext("/realms/finsentinel/protocol/openid-connect/certs", exchange -> respond(exchange,
                    new JWKSet(new RSAKey.Builder((RSAPublicKey) KEYS.getPublic()).keyID(KEY_ID).build()).toString()));
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

    private static String toJson(Map<String, Object> map) {
        return com.nimbusds.jose.util.JSONObjectUtils.toJSONString(map);
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
