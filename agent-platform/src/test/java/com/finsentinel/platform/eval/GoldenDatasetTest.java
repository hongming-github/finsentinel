package com.finsentinel.platform.eval;

import com.finsentinel.common.AlertRule;
import com.finsentinel.common.Channel;
import com.finsentinel.common.Severity;
import com.finsentinel.platform.investigation.Disposition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class GoldenDatasetTest {

    @Test
    void mistakesInACaseAreFoundBeforeAnyRun() {
        GoldenCase broken = new GoldenCase("c1", "card-fraud", false, "d", "e",
                new GoldenCase.Setup("acc", null, List.of(
                        new GoldenCase.TxnSpec("t1", "10.00", Channel.CARD_PRESENT, "SG", "m-1", "40 minutes ago")),
                        null, null, null, List.of("crm"), null),
                new GoldenCase.AlertSpec(AlertRule.GEO_JUMP, Severity.HIGH, List.of("t1", "t2")),
                new GoldenCase.Expect(Set.of(Disposition.LIKELY_FRAUD), 80, 20, List.of("getProfile"), List.of(),
                        new GoldenCase.Regulations(true, List.of("no-such-set")), false, false, List.of()));
        GoldenDataset dataset = new GoldenDataset(Map.of("set", Set.of("srf 6.2")), List.of(broken, broken));

        assertThat(dataset.problems()).containsExactlyInAnyOrder(
                "chunk set set: malformed chunk id srf 6.2",
                "case c1: transaction t1: not an ISO-8601 duration: 40 minutes ago",
                "case c1: unknown system crm",
                "case c1: alert names unknown transaction t2",
                "case c1: minRiskScore above maxRiskScore",
                "case c1: unknown tool getProfile",
                "case c1: unknown chunk set no-such-set",
                "case c1: missing or duplicate id",
                "case c1: transaction t1: not an ISO-8601 duration: 40 minutes ago",
                "case c1: unknown system crm",
                "case c1: alert names unknown transaction t2",
                "case c1: minRiskScore above maxRiskScore",
                "case c1: unknown tool getProfile",
                "case c1: unknown chunk set no-such-set");
    }

    @Test
    void theToolNamesComeFromTheToolsClass() {
        assertThat(GoldenDataset.toolNames()).contains("getAlertTransactions", "searchRegulations", "screenTransferPayees")
                .hasSize(9);
    }
}
