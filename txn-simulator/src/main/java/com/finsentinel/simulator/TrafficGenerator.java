package com.finsentinel.simulator;

import com.finsentinel.common.Channel;
import com.finsentinel.common.Transaction;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/** Produces normal and fraudulent transactions. Deterministic for a given seed. */
class TrafficGenerator {

    static final List<String> TENANTS = List.of("harbour", "straits");
    private static final String[] MCC = {"5411", "5812", "5999", "4121", "5732", "4814"};
    private static final String[] ONLINE_COUNTRIES = {"SG", "SG", "SG", "US", "GB", "HK"};
    private static final String[] TRAVEL_COUNTRIES = {"GB", "US", "JP", "TH", "AU"};

    private final Random random;
    private final List<Account> accounts = new ArrayList<>();

    TrafficGenerator(int accountsPerTenant, long seed) {
        this.random = new Random(seed);
        for (String tenant : TENANTS) {
            for (int i = 0; i < accountsPerTenant; i++) {
                String home = random.nextDouble() < 0.9 ? "SG" : "MY";
                BigDecimal typical = BigDecimal.valueOf(20 + random.nextInt(280));
                accounts.add(new Account(tenant, "%s-acc-%04d".formatted(tenant, i),
                        "%s-cus-%04d".formatted(tenant, i), home, typical));
            }
        }
    }

    List<Account> accounts() {
        return accounts;
    }

    /** History spread over the past week, oldest first, so amount baselines exist from the start. */
    List<Transaction> backfill(int perAccount, Instant now) {
        List<Transaction> out = new ArrayList<>(accounts.size() * perAccount);
        for (Account account : accounts) {
            for (int i = 0; i < perAccount; i++) {
                Instant at = now.minus(Duration.ofSeconds(60 + random.nextInt(7 * 24 * 3600)));
                out.add(normal(account, at));
            }
        }
        out.sort(Comparator.comparing(Transaction::eventTime));
        return out;
    }

    Transaction normal(Instant at) {
        return normal(randomAccount(), at);
    }

    List<Transaction> scenario(Scenario scenario, Account account, Instant now) {
        return switch (scenario) {
            case BURST -> {
                List<Transaction> burst = new ArrayList<>();
                for (int i = 0; i < 10; i++) {
                    burst.add(txn(account, BigDecimal.valueOf(1 + random.nextInt(5)), Channel.CARD_NOT_PRESENT,
                            "US", now.minusSeconds(20 - 2L * i)));
                }
                yield burst;
            }
            case GEO_JUMP -> List.of(
                    txn(account, amount(account), Channel.CARD_PRESENT, account.homeCountry(), now.minus(Duration.ofMinutes(40))),
                    txn(account, amount(account), Channel.CARD_PRESENT,
                            TRAVEL_COUNTRIES[random.nextInt(TRAVEL_COUNTRIES.length)], now));
            case LARGE_AMOUNT -> List.of(txn(account,
                    account.typicalAmount().multiply(BigDecimal.valueOf(40 + random.nextInt(41))),
                    Channel.TRANSFER, account.homeCountry(), now));
            case MALFORMED -> List.of();
        };
    }

    Account randomAccount() {
        return accounts.get(random.nextInt(accounts.size()));
    }

    Scenario randomScenario() {
        Scenario[] all = Scenario.values();
        return all[random.nextInt(all.length)];
    }

    private Transaction normal(Account account, Instant at) {
        double r = random.nextDouble();
        Channel channel = r < 0.5 ? Channel.CARD_PRESENT : r < 0.85 ? Channel.CARD_NOT_PRESENT : Channel.TRANSFER;
        String country = channel == Channel.CARD_NOT_PRESENT
                ? ONLINE_COUNTRIES[random.nextInt(ONLINE_COUNTRIES.length)]
                : account.homeCountry();
        return txn(account, amount(account), channel, country, at);
    }

    /** Log-normal around the account's typical amount; sigma 0.4 keeps honest outliers rare. */
    private BigDecimal amount(Account account) {
        double factor = Math.exp(0.4 * random.nextGaussian());
        return account.typicalAmount().multiply(BigDecimal.valueOf(factor)).setScale(2, RoundingMode.HALF_UP);
    }

    private Transaction txn(Account account, BigDecimal amount, Channel channel, String country, Instant at) {
        return new Transaction(
                new UUID(random.nextLong(), random.nextLong()).toString(),
                account.tenantId(), account.accountId(), account.customerId(),
                amount.setScale(2, RoundingMode.HALF_UP), "SGD",
                "m-" + random.nextInt(2000), MCC[random.nextInt(MCC.length)],
                channel, country, at);
    }
}
