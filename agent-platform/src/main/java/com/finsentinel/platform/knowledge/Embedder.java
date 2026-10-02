package com.finsentinel.platform.knowledge;

import java.util.List;

/** Text to vectors. The seam that lets tests run without an embedding API. */
public interface Embedder {

    List<float[]> embed(List<String> texts);

    default float[] embed(String text) {
        return embed(List.of(text)).getFirst();
    }
}
