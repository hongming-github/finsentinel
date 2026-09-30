package com.finsentinel.common;

public enum AlertRule {
    /** Too many transactions in a short window. */
    VELOCITY,
    /** Amount far outside the account's own history. */
    LARGE_AMOUNT,
    /** Card-present payments in two countries too close together to travel between. */
    GEO_JUMP
}
