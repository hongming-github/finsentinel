package com.finsentinel.platform.assistant;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class GroundingTest {

    private static final String CASE = "11111111-1111-4111-8111-111111111111";
    private static final String TXN = "22222222-2222-4222-8222-222222222222";
    private static final String OTHER = "33333333-3333-4333-8333-333333333333";

    @Test
    void anAnswerWithinTheFactsAndPassagesPasses() {
        AssistantModel.Answer answer = new AssistantModel.Answer("Transfer " + TXN + " of case " + CASE
                + " breaks eupg-2024:4.2.", List.of(TXN), List.of("eupg-2024:4.2"));

        assertThat(Grounding.problems(answer, CASE, Set.of(TXN), Set.of("eupg-2024:4.2"), "why?")).isEmpty();
    }

    @Test
    void citedOrNamedIdsFromElsewhereAreProblems() {
        AssistantModel.Answer answer = new AssistantModel.Answer("Like " + OTHER + ", see mas-626:7.3.",
                List.of(OTHER), List.of("eupg-2024:9.9"));

        assertThat(Grounding.problems(answer, CASE, Set.of(TXN), Set.of(), "why?")).containsExactly(
                "citedTxnIds has " + OTHER + ", which is not a transaction in the fact sheet",
                "citedChunkIds has eupg-2024:9.9, which is not a passage you were given",
                "the answer names " + OTHER + ", which is not this case or one of its transactions",
                "the answer names passage mas-626:7.3, which you were not given");
    }

    @Test
    void anIdTheAnalystWroteMayBeRepeatedAndTimesAreNotPassages() {
        AssistantModel.Answer answer = new AssistantModel.Answer("I only see this case, not "
                + OTHER.toUpperCase(java.util.Locale.ROOT) + ". The transfer was at 10:32.", List.of(), List.of());

        assertThat(Grounding.problems(answer, CASE, Set.of(TXN), Set.of(), "And case " + OTHER + "?")).isEmpty();
        assertThat(Grounding.problems(new AssistantModel.Answer(" ", List.of(), List.of()), CASE, Set.of(), Set.of(),
                "")).containsExactly("the answer is empty");
    }

    /** A live answer cited such an id; in the text it would have been cut to its prefix and refused. */
    @Test
    void aPassageIdWithANameIsReadWhole() {
        String named = "srf-2024:2.1:high-risk-activities";
        AssistantModel.Answer answer = new AssistantModel.Answer("High-risk activities (" + named + ") include adding "
                + "payees.", List.of(), List.of(named));

        assertThat(Grounding.problems(answer, CASE, Set.of(), Set.of(named), "and the cooling-off?")).isEmpty();
        assertThat(Grounding.problems(answer, CASE, Set.of(), Set.of("srf-2024:2.1"), "")).contains(
                "the answer names passage " + named + ", which you were not given");
    }

    /** Lettered sections ("11A") and parts of long paragraphs ("-p2") are passage ids too, read whole. */
    @Test
    void letteredAndSplitPassageIdsAreReadWhole() {
        String lettered = "notice-626-2025:11A.3";
        String part = "notice-626-2025:6.14-p2";
        AssistantModel.Answer answer = new AssistantModel.Answer("Value transfers (" + lettered + ") and beneficial "
                + "owners (" + part + ").", List.of(), List.of(lettered, part));

        assertThat(Grounding.problems(answer, CASE, Set.of(), Set.of(lettered, part), "")).isEmpty();
        assertThat(Grounding.problems(answer, CASE, Set.of(), Set.of("notice-626-2025:6.14-p1"), "")).contains(
                "the answer names passage " + lettered + ", which you were not given",
                "the answer names passage " + part + ", which you were not given");
    }

    @Test
    void aSummaryRepeatsOnlyIdsOfTheTurnsItFolds() {
        assertThat(Grounding.summaryProblems("Asked about " + TXN + " and eupg-2024:4.2.",
                "q " + TXN + " a eupg-2024:4.2", 500)).isEmpty();
        assertThat(Grounding.summaryProblems("Asked about " + OTHER + ".", "q " + TXN, 500))
                .containsExactly("the summary names " + OTHER + ", which the folded turns do not");
        assertThat(Grounding.summaryProblems("x".repeat(20), "", 10))
                .containsExactly("the summary is longer than 10 characters");
    }
}
