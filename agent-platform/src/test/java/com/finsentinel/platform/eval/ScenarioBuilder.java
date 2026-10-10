package com.finsentinel.platform.eval;

import com.finsentinel.common.Alert;
import com.finsentinel.common.Channel;
import com.finsentinel.common.Transaction;
import com.finsentinel.platform.FakeExternalSystems;
import com.finsentinel.platform.TestData;
import com.finsentinel.platform.systems.AccountDetails;
import com.finsentinel.platform.systems.CustomerProfile;
import com.finsentinel.platform.systems.ScreeningResult;
import com.finsentinel.platform.transaction.TransactionRepository;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Turns a golden case into data: transactions in the store, the fake bank systems' answers, and the alert. Each run
 * gets its own tenant, so runs never see each other's history. The ordinary history is random but seeded by the case,
 * so every run of a case sees the same amounts.
 */
final class ScenarioBuilder {

    private final TransactionRepository transactions;
    private final FakeExternalSystems systems;

    ScenarioBuilder(TransactionRepository transactions, FakeExternalSystems systems) {
        this.transactions = transactions;
        this.systems = systems;
    }

    /** @param ids the stored transaction id of each ref in the case */
    record Scenario(String tenant, Alert alert, Map<String, String> ids) {
    }

    Scenario build(GoldenCase c, String tenant, Instant now) {
        GoldenCase.Setup s = c.setup();
        String account = s.account();
        List<Transaction> rows = new ArrayList<>();
        if (s.history() != null) {
            Random random = new Random(c.id().hashCode());
            Instant last = at(now, s.history().before());
            for (int day = s.history().days(); day >= 1; day--) {
                Channel channel = day % 3 == 0 ? Channel.CARD_NOT_PRESENT : Channel.CARD_PRESENT;
                String amount = "%.2f".formatted(100 * Math.exp(0.3 * random.nextGaussian()));
                rows.add(TestData.txn(tenant, account, amount, channel, "SG", "m-" + (day % 5),
                        last.minus(Duration.ofDays(day - 1L))));
            }
        }
        Map<String, Transaction> byRef = new LinkedHashMap<>();
        for (GoldenCase.TxnSpec t : s.transactions()) {
            Transaction txn = TestData.txn(tenant, account, t.amount(), t.channel(), t.country(), t.counterparty(),
                    at(now, t.at()));
            byRef.put(t.ref(), txn);
            rows.add(txn);
        }
        transactions.insertAll(rows);

        String customer = "cus-" + account;
        if (s.profile() != null) {
            GoldenCase.ProfileSpec p = s.profile();
            systems.profile(tenant, new CustomerProfile(customer, p.segment(), LocalDate.parse("2019-03-14"),
                    p.kycRiskRating(), p.occupation(), p.residenceCountry(),
                    p.travelNotices().stream().map(n -> new CustomerProfile.TravelNotice(n.country(),
                            date(now, n.from()), date(now, n.to()))).toList(),
                    p.notes().stream().map(n -> new CustomerProfile.Note(at(now, n.at()), n.author(), n.text()))
                            .toList()));
        }
        if (!s.securityEvents().isEmpty()) {
            systems.account(tenant, new AccountDetails(account, "ACTIVE", "Everyday Savings",
                    LocalDate.parse("2018-11-20"), s.securityEvents().stream()
                    .map(e -> new AccountDetails.SecurityEvent(e.type(), at(now, e.at()), e.detail())).toList()));
        }
        for (GoldenCase.ScreeningSpec screening : s.screenings()) {
            systems.screening(tenant, new ScreeningResult(screening.counterparty(), screening.hits().stream()
                    .map(h -> new ScreeningResult.Hit(h.list(), h.reason(), date(now, h.listedOn()))).toList()));
        }
        s.systemsDown().forEach(systems::down);

        List<Transaction> alerted = c.alert().transactions().stream().map(byRef::get).toList();
        Instant alertTime = alerted.stream().map(Transaction::eventTime).max(Comparator.naturalOrder()).orElseThrow();
        Alert alert = TestData.alert(tenant, account, c.alert().rule(), c.alert().severity(),
                alerted.stream().map(Transaction::txnId).toList(), alertTime);
        Map<String, String> ids = new LinkedHashMap<>();
        byRef.forEach((ref, txn) -> ids.put(ref, txn.txnId()));
        return new Scenario(tenant, alert, ids);
    }

    private static Instant at(Instant now, String offset) {
        return now.plus(Duration.parse(offset));
    }

    private static LocalDate date(Instant now, String offset) {
        return LocalDate.ofInstant(at(now, offset), ZoneOffset.UTC);
    }
}
