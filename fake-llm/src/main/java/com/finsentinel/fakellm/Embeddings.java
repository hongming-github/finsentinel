package com.finsentinel.fakellm;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Answers the OpenAI embeddings endpoint with a deterministic bag-of-words vector: texts sharing words point the same
 * way, so keyword-like retrieval still works and the same text always gets the same vector. No semantics.
 */
final class Embeddings {

    /** text-embedding-3-small's size, which the platform's vector column expects, unless the request names one. */
    static final int DEFAULT_DIMENSIONS = 1536;

    private final JsonMapper json;

    Embeddings(JsonMapper json) {
        this.json = json;
    }

    ObjectNode respond(JsonNode request) {
        int dimensions = request.path("dimensions").asInt(DEFAULT_DIMENSIONS);
        List<String> inputs = new ArrayList<>();
        JsonNode input = request.path("input");
        if (input.isArray()) {
            input.forEach(i -> inputs.add(i.asString()));
        } else {
            inputs.add(input.asString());
        }
        ObjectNode response = json.createObjectNode().put("object", "list").put("model", ChatResponder.MODEL);
        ArrayNode data = response.putArray("data");
        int tokens = 0;
        for (int i = 0; i < inputs.size(); i++) {
            ArrayNode embedding = data.addObject().put("object", "embedding").put("index", i).putArray("embedding");
            for (float x : vector(inputs.get(i), dimensions)) {
                embedding.add(x);
            }
            tokens += ChatResponder.estimateTokens(inputs.get(i));
        }
        response.putObject("usage").put("prompt_tokens", tokens).put("total_tokens", tokens);
        return response;
    }

    static float[] vector(String text, int dimensions) {
        float[] v = new float[dimensions];
        for (String word : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+", -1)) {
            if (word.length() > 2) {
                v[Math.floorMod(word.hashCode(), dimensions)] += 1f;
            }
        }
        double norm = 0;
        for (float x : v) {
            norm += x * x;
        }
        if (norm == 0) {
            v[0] = 1f;
            return v;
        }
        float n = (float) Math.sqrt(norm);
        for (int i = 0; i < v.length; i++) {
            v[i] /= n;
        }
        return v;
    }
}
