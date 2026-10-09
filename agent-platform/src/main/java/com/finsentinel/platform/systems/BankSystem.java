package com.finsentinel.platform.systems;

import java.util.EnumSet;
import java.util.Set;

/**
 * The outside systems a bank may connect to the platform. A bank's agents get the tools of its connected systems only
 * (ADR 0015): the platform never queries a system on behalf of a bank that has not connected it.
 */
public enum BankSystem {

    /** The bank's CRM and core banking, through mcp-banking. */
    BANKING,
    /** The watchlist screening vendor, through mcp-compliance. */
    COMPLIANCE;

    public static final Set<BankSystem> ALL = Set.copyOf(EnumSet.allOf(BankSystem.class));
}
