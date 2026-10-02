package com.finsentinel.platform.knowledge;

/**
 * A passage returned to the agent.
 *
 * @param citation human-readable reference, e.g. "EUPG §4.7"
 */
public record RegulationHit(String chunkId, String citation, String section, int page, String text) {
}
