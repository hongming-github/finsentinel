package com.finsentinel.platform.llm;

/**
 * A model at a provider, written {@code provider:model} in configuration; a bare model name means OpenAI
 * ({@code gpt-5.4-mini}, {@code groq:openai/gpt-oss-120b}). Its string form is also its price key.
 */
public record ModelRef(String provider, String model) {

    public static final String DEFAULT_PROVIDER = "openai";

    public ModelRef {
        if (provider == null || provider.isBlank() || model == null || model.isBlank()) {
            throw new IllegalArgumentException("a model reference needs a provider and a model");
        }
    }

    public static ModelRef parse(String ref) {
        int colon = ref.indexOf(':');
        return colon < 0 ? new ModelRef(DEFAULT_PROVIDER, ref.trim())
                : new ModelRef(ref.substring(0, colon).trim(), ref.substring(colon + 1).trim());
    }

    /** As it is written in configuration, and keyed in {@code prices}. */
    @Override
    public String toString() {
        return DEFAULT_PROVIDER.equals(provider) ? model : provider + ":" + model;
    }
}
