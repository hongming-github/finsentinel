package com.finsentinel.platform.investigation;

import java.math.BigDecimal;

/**
 * Audit record of one tool invocation made by the agent.
 *
 * @param cacheHit regulation searches: served from the search cache; null for tools without a cache, when the cache
 *                 is off, and in analyses stored before it existed
 * @param savedUsd on a cache hit, what producing the cached result had cost (the rerank call it avoided)
 */
public record ToolCall(String tool, String arguments, int resultCount, long durationMs, Boolean cacheHit,
                       BigDecimal savedUsd) {

    public ToolCall(String tool, String arguments, int resultCount, long durationMs) {
        this(tool, arguments, resultCount, durationMs, null, null);
    }
}
