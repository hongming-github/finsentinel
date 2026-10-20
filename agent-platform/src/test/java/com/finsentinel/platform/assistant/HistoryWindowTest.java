package com.finsentinel.platform.assistant;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class HistoryWindowTest {

    @Test
    void turnsInsideTheWindowAreNeverFolded() {
        HistoryWindow.Plan plan = HistoryWindow.plan(0, turns(9, 10), 6, 4, 3000);

        assertThat(plan.toFold()).isEmpty();
        assertThat(plan.foldThrough()).isZero();
        assertThat(plan.recent()).hasSize(9);
    }

    @Test
    void olderTurnsAreFoldedOnlyOnceAWholeBatchHasPiledUp() {
        HistoryWindow.Plan plan = HistoryWindow.plan(0, turns(10, 10), 6, 4, 3000);

        assertThat(plan.toFold()).extracting(ConversationTurn::seq).containsExactly(1, 2, 3, 4);
        assertThat(plan.foldThrough()).isEqualTo(4);
        assertThat(plan.recent()).extracting(ConversationTurn::seq).containsExactly(5, 6, 7, 8, 9, 10);

        HistoryWindow.Plan next = HistoryWindow.plan(4, turns(13, 10), 6, 4, 3000);
        assertThat(next.toFold()).as("three beyond the window: not a batch yet").isEmpty();
        assertThat(next.recent()).extracting(ConversationTurn::seq).containsExactly(5, 6, 7, 8, 9, 10, 11, 12, 13);
    }

    @Test
    void longTurnsAreFoldedEarlyToStayWithinTheBudgetButTheLatestStays() {
        // About 516 tokens per turn: 3 fit in 1,600, the 4th does not.
        HistoryWindow.Plan plan = HistoryWindow.plan(0, turns(5, 2000), 6, 4, 1600);
        assertThat(plan.recent()).extracting(ConversationTurn::seq).containsExactly(3, 4, 5);
        assertThat(plan.toFold()).extracting(ConversationTurn::seq).containsExactly(1, 2);

        HistoryWindow.Plan huge = HistoryWindow.plan(0, turns(2, 40_000), 6, 4, 1600);
        assertThat(huge.recent()).as("the latest turn is never dropped").extracting(ConversationTurn::seq)
                .containsExactly(2);
    }

    static List<ConversationTurn> turns(int n, int chars) {
        return IntStream.rangeClosed(1, n).mapToObj(i -> turn(i, "x".repeat(chars))).toList();
    }

    static ConversationTurn turn(int seq, String answer) {
        return new ConversationTurn(seq, "q" + seq, "q" + seq, null, answer, List.of(), List.of(), List.of(), 1, "m",
                1, 1, 0, 0, 1, BigDecimal.ZERO, 1, Instant.EPOCH);
    }
}
