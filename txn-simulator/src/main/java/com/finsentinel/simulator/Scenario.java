package com.finsentinel.simulator;

/** Fraud patterns the simulator can inject. Each maps to one stream rule, except MALFORMED. */
enum Scenario {
    /** 10 small online payments in ~20 seconds (card testing). */
    BURST,
    /**
     * A cloned card: card-present at home, then abroad 20 minutes later, sooner than any flight allows. A Malaysian
     * account paying in Thailand raises nothing: the countries share a land border, so the trip is possible.
     */
    GEO_JUMP,
    /** One transfer 40–80x the account's typical amount. */
    LARGE_AMOUNT,
    /** An unparseable message, to exercise the stream job's dead-letter path. */
    MALFORMED
}
