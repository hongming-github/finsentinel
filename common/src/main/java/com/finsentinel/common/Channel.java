package com.finsentinel.common;

public enum Channel {
    /** Physical card at a terminal: the customer must be where the terminal is. */
    CARD_PRESENT,
    /** Online card payment: location is not meaningful. */
    CARD_NOT_PRESENT,
    /** Account-to-account transfer (FAST / PayNow style). */
    TRANSFER
}
