package com.finsentinel.fakellm;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;

/**
 * An OpenAI-compatible stand-in for load tests and chaos drills (ADR 0024). Chat Completions and embeddings under
 * {@code /v1}, and the same under {@code /<provider>/v1} so the platform's fallback provider can point here too with
 * its own behaviour: {@code /v1} is "openai", {@code /groq/v1} is "groq".
 *
 * <pre>
 * GET    /admin/behaviour                     every provider's behaviour
 * POST   /admin/behaviour?provider=openai     {"latencyMs":2000,"jitterMs":500,"errorStatus":429,"errorRate":0.5}
 * DELETE /admin/behaviour[?provider=openai]   back to normal
 * GET    /health
 * </pre>
 *
 * Latency and errors apply to chat only: embeddings stay fast so the platform's knowledge sync is not part of a drill.
 */
public final class FakeLlm implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(FakeLlm.class);
    static final int DEFAULT_PORT = 8096;
    static final String DEFAULT_PROVIDER = "openai";

    private final JsonMapper json = JsonMapper.builder().build();
    private final ChatResponder chat = new ChatResponder(json);
    private final Embeddings embeddings = new Embeddings(json);
    private final Map<String, Behaviour> behaviours = new ConcurrentHashMap<>();
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    /** Starts on {@code port}; 0 picks a free one (see {@link #port()}). */
    public FakeLlm(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", port), 0);
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.close();
    }

    public static void main(String[] args) throws IOException {
        int port = DEFAULT_PORT;
        for (String arg : args) {
            if (arg.startsWith("--port=")) {
                port = Integer.parseInt(arg.substring("--port=".length()));
            }
        }
        FakeLlm fake = new FakeLlm(port);
        log.info("fake-llm on http://localhost:{}/v1 (and /<provider>/v1); behaviour at /admin/behaviour", fake.port());
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            route(exchange);
        } catch (JacksonException | IllegalArgumentException e) {
            // A bad request or a bad behaviour: answered, never a dead connection.
            send(exchange, 400, json.createObjectNode().put("error", String.valueOf(e.getMessage())));
        } finally {
            exchange.close();
        }
    }

    private void route(HttpExchange exchange) throws IOException {
        URI uri = exchange.getRequestURI();
        String path = uri.getPath();
        String method = exchange.getRequestMethod();
        if (path.equals("/health")) {
            send(exchange, 200, json.createObjectNode().put("status", "UP"));
        } else if (path.equals("/admin/behaviour")) {
            admin(exchange, method, query(uri).get("provider"));
        } else if (method.equals("POST") && path.endsWith("/v1/chat/completions")) {
            String provider = provider(path, "/v1/chat/completions");
            JsonNode request = json.readTree(body(exchange));
            Behaviour behaviour = behaviours.getOrDefault(provider, Behaviour.NORMAL);
            sleep(behaviour);
            if (ThreadLocalRandom.current().nextDouble() < behaviour.errorRate()) {
                send(exchange, behaviour.errorStatus(), error(behaviour.errorStatus()));
            } else {
                send(exchange, 200, chat.respond(request));
            }
        } else if (method.equals("POST") && path.endsWith("/v1/embeddings")) {
            send(exchange, 200, embeddings.respond(json.readTree(body(exchange))));
        } else {
            send(exchange, 404, json.createObjectNode().put("error", "no route for " + method + " " + path));
        }
    }

    private void admin(HttpExchange exchange, String method, String provider) throws IOException {
        switch (method) {
            case "POST" -> {
                String name = provider == null ? DEFAULT_PROVIDER : provider;
                JsonNode b = json.readTree(body(exchange));
                Behaviour behaviour = new Behaviour(b.path("latencyMs").asLong(0), b.path("jitterMs").asLong(0),
                        b.path("errorStatus").asInt(500), b.path("errorRate").asDouble(0));
                behaviours.put(name, behaviour);
                log.info("Provider {} now {}", name, behaviour);
            }
            case "DELETE" -> {
                if (provider == null) {
                    behaviours.clear();
                } else {
                    behaviours.remove(provider);
                }
                log.info("Provider {} back to normal", provider == null ? "every" : provider);
            }
            case "GET" -> {
                // Nothing to change; the current state is the answer below.
            }
            default -> {
                send(exchange, 405, json.createObjectNode().put("error", "use GET, POST or DELETE"));
                return;
            }
        }
        ObjectNode all = json.createObjectNode();
        new TreeMap<>(behaviours).forEach((name, b) -> all.set(name, json.valueToTree(b)));
        send(exchange, 200, all);
    }

    /** "/v1/chat/completions" is the default provider; "/groq/v1/chat/completions" is "groq". */
    static String provider(String path, String suffix) {
        String prefix = path.substring(0, path.length() - suffix.length());
        return prefix.isEmpty() ? DEFAULT_PROVIDER : prefix.substring(1);
    }

    private ObjectNode error(int status) {
        ObjectNode body = json.createObjectNode();
        body.putObject("error")
                .put("message", "injected by fake-llm (HTTP " + status + ")")
                .put("type", status == 429 ? "rate_limit_exceeded" : "server_error")
                .put("code", status == 429 ? "rate_limit_exceeded" : "server_error");
        return body;
    }

    private static void sleep(Behaviour behaviour) {
        long ms = behaviour.latencyMs()
                + (behaviour.jitterMs() == 0 ? 0 : ThreadLocalRandom.current().nextLong(behaviour.jitterMs() + 1));
        if (ms > 0) {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static String body(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Map<String, String> query(URI uri) {
        Map<String, String> params = new TreeMap<>();
        if (uri.getQuery() != null) {
            for (String pair : uri.getQuery().split("&", -1)) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    params.put(pair.substring(0, eq), pair.substring(eq + 1));
                }
            }
        }
        return params;
    }

    private void send(HttpExchange exchange, int status, JsonNode body) throws IOException {
        byte[] bytes = json.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
