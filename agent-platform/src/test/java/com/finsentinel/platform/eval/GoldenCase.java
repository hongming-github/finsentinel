package com.finsentinel.platform.eval;

import com.finsentinel.common.AlertRule;
import com.finsentinel.common.Channel;
import com.finsentinel.common.Severity;
import com.finsentinel.platform.investigation.Disposition;
import com.finsentinel.platform.systems.BankSystem;

import java.util.List;
import java.util.Set;

/**
 * One case of the golden dataset ({@code eval/golden-cases.json}, ADR 0017): the data to set up, the alert to raise,
 * and what an expert would accept. Times are ISO-8601 durations relative to the start of a run.
 *
 * @param critical   held to its own pass-rate threshold: a regression here fails the gate even when the overall
 *                   rate holds
 * @param expertView what an experienced analyst would conclude; for the LLM judge only, never shown to the agents
 */
public record GoldenCase(String id, String category, Boolean critical, String description, String expertView,
                         Setup setup, AlertSpec alert, Expect expect) {

    public GoldenCase {
        // Optional in the JSON; the shared mapper refuses null for a primitive.
        critical = critical != null && critical;
        setup = setup == null ? new Setup(null, null, null, null, null, null, null, null) : setup;
        expect = expect == null ? new Expect(null, null, null, null, null, null, null, null, null) : expect;
    }

    /**
     * @param history          ordinary daily card spending before the case's own transactions; null for none
     * @param systemsDown      fake systems that fail ({@code banking}, {@code compliance})
     * @param connectedSystems the bank's connected systems; null means all
     */
    public record Setup(String account, History history, List<TxnSpec> transactions, ProfileSpec profile,
                        List<EventSpec> securityEvents, List<ScreeningSpec> screenings, List<String> systemsDown,
                        Set<BankSystem> connectedSystems) {

        public Setup {
            transactions = transactions == null ? List.of() : List.copyOf(transactions);
            securityEvents = securityEvents == null ? List.of() : List.copyOf(securityEvents);
            screenings = screenings == null ? List.of() : List.copyOf(screenings);
            systemsDown = systemsDown == null ? List.of() : List.copyOf(systemsDown);
            connectedSystems = connectedSystems == null ? BankSystem.ALL : Set.copyOf(connectedSystems);
        }
    }

    /** {@code days} daily payments of about 100 SGD at merchants m-0 to m-4, the last one {@code before} the run. */
    public record History(int days, String before) {
    }

    public record TxnSpec(String ref, String amount, Channel channel, String country, String counterparty, String at) {
    }

    public record ProfileSpec(String segment, String kycRiskRating, String occupation, String residenceCountry,
                              List<TravelSpec> travelNotices, List<NoteSpec> notes) {

        public ProfileSpec {
            travelNotices = travelNotices == null ? List.of() : List.copyOf(travelNotices);
            notes = notes == null ? List.of() : List.copyOf(notes);
        }
    }

    public record TravelSpec(String country, String from, String to) {
    }

    public record NoteSpec(String at, String author, String text) {
    }

    public record EventSpec(String type, String at, String detail) {
    }

    public record ScreeningSpec(String counterparty, List<HitSpec> hits) {

        public ScreeningSpec {
            hits = hits == null ? List.of() : List.copyOf(hits);
        }
    }

    public record HitSpec(String list, String reason, String listedOn) {
    }

    /** @param transactions refs of the case's transactions behind the alert; the alert time is the latest of them */
    public record AlertSpec(AlertRule rule, Severity severity, List<String> transactions) {
    }

    /**
     * @param dispositions                 any of these is a correct verdict
     * @param requiredTools                tools a competent investigation calls (tool selection)
     * @param forbiddenTools               tools the agent must not get or call (e.g. an unconnected system)
     * @param untrustedInstructionsFlagged the platform must flag planted instructions
     * @param missingChecksReported        the report must say which checks could not be done
     * @param mustNotContain               text that must not appear anywhere in the report (raw personal data)
     */
    public record Expect(Set<Disposition> dispositions, Integer minRiskScore, Integer maxRiskScore,
                         List<String> requiredTools, List<String> forbiddenTools, Regulations regulations,
                         Boolean untrustedInstructionsFlagged, Boolean missingChecksReported,
                         List<String> mustNotContain) {

        public Expect {
            untrustedInstructionsFlagged = untrustedInstructionsFlagged != null && untrustedInstructionsFlagged;
            missingChecksReported = missingChecksReported != null && missingChecksReported;
            dispositions = dispositions == null ? Set.of() : Set.copyOf(dispositions);
            requiredTools = requiredTools == null ? List.of() : List.copyOf(requiredTools);
            forbiddenTools = forbiddenTools == null ? List.of() : List.copyOf(forbiddenTools);
            mustNotContain = mustNotContain == null ? List.of() : List.copyOf(mustNotContain);
        }
    }

    /**
     * @param required         the report must carry regulatory notes
     * @param relevantChunkSet name of a chunk set in the dataset: at least one cited passage must be in it
     */
    public record Regulations(Boolean required, String relevantChunkSet) {

        public Regulations {
            required = required != null && required;
        }
    }
}
