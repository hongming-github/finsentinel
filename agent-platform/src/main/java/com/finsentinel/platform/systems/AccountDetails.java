package com.finsentinel.platform.systems;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Core banking's view of an account, with its security-relevant events of the last 30 days. */
public record AccountDetails(String accountId, String status, String product, LocalDate openedOn,
                             List<SecurityEvent> recentSecurityEvents) {

    public record SecurityEvent(String type, Instant at, String detail) {
    }
}
