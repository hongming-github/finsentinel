package com.finsentinel.platform;

import com.finsentinel.platform.knowledge.Embedder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.annotation.Primary;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Deterministic stand-in for an embedding model: hashed bag of words, L2-normalised, 1536 dimensions. Texts that
 * share words land close together, which is enough to test the retrieval plumbing without an API.
 */
@TestComponent
@Primary
@ConditionalOnProperty(name = "finsentinel.test.fake-ai", havingValue = "true", matchIfMissing = true)
public class FakeEmbedder implements Embedder {

    public static final int DIMENSIONS = 1536;
    private int calls;
    private int texts;

    @Override
    public synchronized List<float[]> embed(List<String> input) {
        calls++;
        texts += input.size();
        List<float[]> out = new ArrayList<>(input.size());
        for (String text : input) {
            out.add(vector(text));
        }
        return out;
    }

    public static float[] vector(String text) {
        float[] v = new float[DIMENSIONS];
        for (String word : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (word.length() > 2) {
                v[Math.floorMod(word.hashCode(), DIMENSIONS)] += 1f;
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

    public synchronized int textsEmbedded() {
        return texts;
    }
}
