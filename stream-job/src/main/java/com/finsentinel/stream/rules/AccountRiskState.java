package com.finsentinel.stream.rules;

/**
 * Everything the rules remember about one account.
 *
 * <p>Written as a Flink POJO (public fields, no-arg constructor, only primitives, strings and arrays)
 * so Flink uses its own serializer instead of falling back to Kryo.
 */
public class AccountRiskState {

    // Velocity: event times (ascending) and ids of recent transactions inside the window.
    public long[] recentTimesMs = new long[0];
    public String[] recentTxnIds = new String[0];
    public long lastVelocityAlertMs = Long.MIN_VALUE;

    // Large amount: running mean/variance of non-flagged amounts (Welford's algorithm).
    public long amountCount;
    public double amountMean;
    public double amountM2;

    // Geo jump: last card-present location.
    public String lastCardPresentCountry;
    public long lastCardPresentTimeMs;
    public String lastCardPresentTxnId;

    public AccountRiskState() {
    }

    double amountStdDev() {
        return amountCount < 2 ? 0.0 : Math.sqrt(amountM2 / (amountCount - 1));
    }

    void recordAmount(double amount) {
        amountCount++;
        double delta = amount - amountMean;
        amountMean += delta / amountCount;
        amountM2 += delta * (amount - amountMean);
    }
}
