package com.finsentinel.simulator;

import com.finsentinel.common.Transaction;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class TrafficGeneratorTest {

    /** History from a run with one population size must describe the same customers in a run with another. */
    @Test
    void anAccountIsTheSameCustomerWhateverThePopulationSize() {
        Map<String, Account> small = byId(new TrafficGenerator(100, 1).accounts());
        Map<String, Account> large = byId(new TrafficGenerator(5_000, 2).accounts());

        assertThat(small).hasSize(200);
        small.forEach((id, account) -> assertThat(large.get(id)).as(id).isEqualTo(account));
    }

    /**
     * Live scenarios happen now, not in the past: the simulator publishes each transaction at its event time, so
     * detection latency measures the stream job (ADR 0025). A backdated burst read as 6 to 14 s of detection.
     */
    @Test
    void noScenarioTransactionIsBackdatedExceptTheEarlierLegOfAGeoJump() {
        TrafficGenerator generator = new TrafficGenerator(10, 3);
        Account account = generator.accounts().getFirst();
        Instant now = Instant.parse("2026-10-16T08:00:00Z");

        List<Transaction> burst = generator.scenario(Scenario.BURST, account, now);
        assertThat(burst).hasSize(10).allSatisfy(txn -> assertThat(txn.eventTime()).isAfterOrEqualTo(now));
        assertThat(burst.getLast().eventTime()).isEqualTo(now.plusSeconds(18));

        assertThat(generator.scenario(Scenario.LARGE_AMOUNT, account, now).getFirst().eventTime()).isEqualTo(now);
        assertThat(generator.scenario(Scenario.GEO_JUMP, account, now).getLast().eventTime()).isEqualTo(now);
    }

    @Test
    void aBurstUsesDistinctAccountsAndOnlyScenariosThatAlert() {
        TrafficGenerator generator = new TrafficGenerator(50, 4);

        List<Account> accounts = generator.distinctAccounts(100);
        assertThat(new HashSet<>(accounts)).hasSize(100);
        for (int i = 0; i < 200; i++) {
            assertThat(generator.randomAlertingScenario()).isNotEqualTo(Scenario.MALFORMED);
        }
    }

    private static Map<String, Account> byId(List<Account> accounts) {
        return accounts.stream().collect(Collectors.toMap(Account::accountId, Function.identity()));
    }
}
