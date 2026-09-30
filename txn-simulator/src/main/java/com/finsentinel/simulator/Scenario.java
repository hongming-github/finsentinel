package com.finsentinel.simulator;

/** Fraud patterns the simulator can inject. Each maps to one stream rule, except MALFORMED. */
enum Scenario {
    /** 10 small online payments in ~20 seconds (card testing). */
    BURST,
    /** Card-present at home, then card-present abroad 40 minutes later. */
    GEO_JUMP,
    /** One transfer 40–80x the account's typical amount. */
    LARGE_AMOUNT,
    /** An unparseable message, to exercise the stream job's dead-letter path. */
    MALFORMED
}
