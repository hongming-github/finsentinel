package com.finsentinel.simulator;

import com.finsentinel.common.Channel;
import com.finsentinel.common.Transaction;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.SplittableRandom;
import java.util.UUID;

/** Produces normal and fraudulent transactions. Traffic is deterministic for a given seed. */
class TrafficGenerator {

    static final List<String> TENANTS = List.of("harbour", "straits");
    /** Shared pool of everyday merchants; fraud uses ids outside it so they are new to every account. */
    private static final int MERCHANT_POOL = 2000;
    private static final String[] MCC = {"5411", "5812", "5999", "4121", "5732", "4814"};
    private static final String[] ONLINE_COUNTRIES = {"SG", "SG", "SG", "US", "GB", "HK"};
    private static final String[] TRAVEL_COUNTRIES = {"GB", "US", "JP", "TH", "AU"};

    /**
     * Fixed on purpose: an account id must always describe the same customer (home country, spending level,
     * regular merchants) across runs and population sizes, or history from one run contradicts the next. Each account
     * draws from its own generator seeded by its id, so {@code --accounts} only adds or drops customers. Only traffic
     * varies by seed.
     */
    private static final long POPULATION_SEED = 20260930L;

    private final Random random;
    private final List<Account> accounts = new ArrayList<>();

    TrafficGenerator(int accountsPerTenant, long trafficSeed) {
        this.random = new Random(trafficSeed);
        for (String tenant : TENANTS) {
            for (int i = 0; i < accountsPerTenant; i++) {
                String accountId = "%s-acc-%04d".formatted(tenant, i);
                SplittableRandom population = new SplittableRandom(POPULATION_SEED ^ accountId.hashCode());
                String home = population.nextDouble() < 0.9 ? "SG" : "MY";
                BigDecimal typical = BigDecimal.valueOf(20 + population.nextInt(280));
                List<String> merchants = new ArrayList<>();
                for (int m = 0; m < 8; m++) {
                    merchants.add("m-" + population.nextInt(MERCHANT_POOL));
                }
                List<String> payees = List.of("payee-" + accountId + "-1", "payee-" + accountId + "-2");
                accounts.add(new Account(tenant, accountId, "%s-cus-%04d".formatted(tenant, i), home, typical,
                        List.copyOf(merchants), payees));
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

    /**
     * A scenario's transactions, some of them in the future: the simulator publishes each one at its event time, so a
     * burst takes as long as it lasts and detection latency measures the stream job, not a backdated timestamp
     * (ADR 0025).
     */
    List<Transaction> scenario(Scenario scenario, Account account, Instant now) {
        return switch (scenario) {
            case BURST -> {
                List<Transaction> burst = new ArrayList<>();
                for (int i = 0; i < 10; i++) {
                    burst.add(txn(account, BigDecimal.valueOf(1 + random.nextInt(5)), Channel.CARD_NOT_PRESENT,
                            "US", newMerchant(), now.plusSeconds(2L * i)));
                }
                yield burst;
            }
            case GEO_JUMP -> List.of(
                    txn(account, amount(account), Channel.CARD_PRESENT, account.homeCountry(),
                            regularMerchant(account), now.minus(Duration.ofMinutes(20))),
                    txn(account, amount(account), Channel.CARD_PRESENT,
                            TRAVEL_COUNTRIES[random.nextInt(TRAVEL_COUNTRIES.length)], newMerchant(), now));
            // Authorised-push-payment scam shape: a huge transfer to a payee the customer has never paid.
            case LARGE_AMOUNT -> List.of(txn(account,
                    account.typicalAmount().multiply(BigDecimal.valueOf(40 + random.nextInt(41))),
                    Channel.TRANSFER, account.homeCountry(), "payee-new-" + random.nextInt(1_000_000), now));
            case MALFORMED -> List.of();
        };
    }

    Account randomAccount() {
        return accounts.get(random.nextInt(accounts.size()));
    }

    /** {@code n} different accounts, at random. */
    List<Account> distinctAccounts(int n) {
        if (n > accounts.size()) {
            throw new IllegalArgumentException("a burst of " + n + " needs at least as many accounts, not "
                    + accounts.size());
        }
        List<Account> shuffled = new ArrayList<>(accounts);
        Collections.shuffle(shuffled, random);
        return List.copyOf(shuffled.subList(0, n));
    }

    /** A scenario that raises an alert (not the malformed message). */
    Scenario randomAlertingScenario() {
        Scenario scenario;
        do {
            scenario = randomScenario();
        } while (scenario == Scenario.MALFORMED);
        return scenario;
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
        String counterparty = channel == Channel.TRANSFER
                ? account.regularPayees().get(random.nextInt(account.regularPayees().size()))
                // Mostly regulars, occasionally somewhere new, as real customers do.
                : random.nextDouble() < 0.85 ? regularMerchant(account) : "m-" + random.nextInt(MERCHANT_POOL);
        return txn(account, amount(account), channel, country, counterparty, at);
    }

    private String regularMerchant(Account account) {
        return account.regularMerchants().get(random.nextInt(account.regularMerchants().size()));
    }

    private String newMerchant() {
        return "m-" + (MERCHANT_POOL + random.nextInt(1_000_000));
    }

    /** Log-normal around the account's typical amount; sigma 0.4 keeps honest outliers rare. */
    private BigDecimal amount(Account account) {
        double factor = Math.exp(0.4 * random.nextGaussian());
        return account.typicalAmount().multiply(BigDecimal.valueOf(factor)).setScale(2, RoundingMode.HALF_UP);
    }

    private Transaction txn(Account account, BigDecimal amount, Channel channel, String country, String counterparty,
                            Instant at) {
        return new Transaction(
                new UUID(random.nextLong(), random.nextLong()).toString(),
                account.tenantId(), account.accountId(), account.customerId(),
                amount.setScale(2, RoundingMode.HALF_UP), "SGD",
                counterparty, MCC[Math.floorMod(counterparty.hashCode(), MCC.length)],
                channel, country, at);
    }
}
