package com.finsentinel.platform.knowledge;

/**
 * Exact McNemar test for two rankers scored on the same questions. Only discordant questions count: b where the
 * first is right and the second wrong, c the reverse. Under "no difference" each discordant question is a fair coin,
 * so the two-sided p-value is twice the binomial(b + c, 1/2) tail at min(b, c), capped at 1.
 */
final class McNemar {

    private McNemar() {
    }

    static double exactP(int b, int c) {
        int n = b + c;
        double tail = 0;
        double choose = 1;
        for (int i = 0; i <= Math.min(b, c); i++) {
            tail += choose / Math.pow(2, n);
            choose = choose * (n - i) / (i + 1);
        }
        return Math.min(1, 2 * tail);
    }
}
