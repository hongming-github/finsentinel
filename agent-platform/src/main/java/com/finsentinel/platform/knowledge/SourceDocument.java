package com.finsentinel.platform.knowledge;

/** An entry of knowledge/sources.json. */
public record SourceDocument(String id, String shortName, String title, String url, String file) {
}
