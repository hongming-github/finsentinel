package com.finsentinel.platform.systems;

import java.time.LocalDate;
import java.util.List;

/** Watchlist screening of one counterparty; no hits means not listed. */
public record ScreeningResult(String counterpartyId, List<Hit> hits) {

    public record Hit(String list, String reason, LocalDate listedOn) {
    }
}
