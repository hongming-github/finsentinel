package com.finsentinel.platform.knowledge;

/**
 * One retrievable passage of a regulatory document.
 *
 * @param chunkId stable id such as {@code eupg-2024:4.7} or {@code eupg-2024:2.1:protected-account}; it is what
 *                reports cite, so it must not change while the document is unchanged
 * @param ref     the paragraph number as printed in the document, e.g. {@code 4.7}
 * @param section the numbered section title, plus an unnumbered sub-heading when there is one
 * @param page    1-based page where the passage starts
 */
public record RegulationChunk(String chunkId, String docId, String ref, String section, int page, String text) {
}
