package com.finsentinel.platform.investigation;

import java.util.Set;

/**
 * The specialist agents of the multi-agent analysis. Each sees only its own tools, so its context holds only the
 * evidence it is responsible for.
 */
public enum Specialist {

    /** The account's own transactions, baseline, counterparties and earlier alerts. */
    TRANSACTIONS(Set.of("getAlertTransactions", "getRecentTransactions", "getAccountProfile", "getCounterpartyHistory",
            "getPriorAlerts")),
    /** The bank's and vendors' systems over MCP: CRM profile, security events, payee screening. */
    CUSTOMER(Set.of("getCustomerProfile", "getAccountSecurityEvents", "screenTransferPayees")),
    /** MAS regulations, for transfers: who bears the loss, reporting duties. */
    REGULATIONS(Set.of("searchRegulations"));

    @SuppressWarnings("ImmutableEnumChecker") // Set.of is unmodifiable; the checker only knows Guava's ImmutableSet
    private final Set<String> tools;

    Specialist(Set<String> tools) {
        this.tools = tools;
    }

    Set<String> tools() {
        return tools;
    }

    String prompt() {
        return "prompts/specialists/" + name().toLowerCase(java.util.Locale.ROOT) + ".md";
    }
}
