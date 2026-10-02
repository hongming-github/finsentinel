package com.finsentinel.platform.knowledge;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Embeds through the provider, remembering every vector on disk keyed by model and text. Re-ingesting unchanged
 * passages (a fresh database, a test run, a re-chunk that keeps most passages) costs nothing.
 */
@Component
class CachingEmbedder implements Embedder {

    private final EmbeddingModel model;
    private final Path cacheDir;
    private final int batchSize;

    CachingEmbedder(EmbeddingModel model, KnowledgeProperties props,
                    @Value("${spring.ai.openai.embedding.model}") String modelName) {
        this.model = model;
        this.cacheDir = props.dir().resolve(".cache").resolve("embeddings").resolve(modelName);
        this.batchSize = props.batchSize();
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        float[][] result = new float[texts.size()][];
        List<Integer> missing = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            result[i] = read(key(texts.get(i)));
            if (result[i] == null) {
                missing.add(i);
            }
        }
        for (int from = 0; from < missing.size(); from += batchSize) {
            List<Integer> batch = missing.subList(from, Math.min(from + batchSize, missing.size()));
            List<float[]> vectors = model.embed(batch.stream().map(texts::get).toList());
            for (int j = 0; j < batch.size(); j++) {
                result[batch.get(j)] = vectors.get(j);
                write(key(texts.get(batch.get(j))), vectors.get(j));
            }
        }
        return List.of(result);
    }

    private float[] read(String key) {
        Path file = cacheDir.resolve(key);
        if (!Files.exists(file)) {
            return null;
        }
        try {
            ByteBuffer buffer = ByteBuffer.wrap(Files.readAllBytes(file)).order(ByteOrder.LITTLE_ENDIAN);
            float[] vector = new float[buffer.remaining() / Float.BYTES];
            buffer.asFloatBuffer().get(vector);
            return vector;
        } catch (IOException e) {
            return null; // a corrupt cache entry is re-embedded, not fatal
        }
    }

    private void write(String key, float[] vector) {
        try {
            Files.createDirectories(cacheDir);
            ByteBuffer buffer = ByteBuffer.allocate(vector.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
            buffer.asFloatBuffer().put(vector);
            Path tmp = Files.createTempFile(cacheDir, key, ".tmp");
            Files.write(tmp, buffer.array());
            Files.move(tmp, cacheDir.resolve(key), java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write embedding cache in " + cacheDir, e);
        }
    }

    private static String key(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
