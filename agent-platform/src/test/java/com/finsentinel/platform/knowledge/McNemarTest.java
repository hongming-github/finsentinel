package com.finsentinel.platform.knowledge;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class McNemarTest {

    @Test
    void matchesTheBinomialTail() {
        assertThat(McNemar.exactP(0, 5)).isCloseTo(2.0 / 32, within(1e-12));
        assertThat(McNemar.exactP(1, 9)).isCloseTo(2.0 * 11 / 1024, within(1e-12));
        assertThat(McNemar.exactP(9, 1)).isEqualTo(McNemar.exactP(1, 9));
    }

    @Test
    void noDiscordantQuestionsMeansNoEvidence() {
        assertThat(McNemar.exactP(0, 0)).isEqualTo(1);
        assertThat(McNemar.exactP(3, 3)).isEqualTo(1);
    }

    @Test
    void oneQuestionApartOnThirtyTwoIsNoise() {
        // The cross-encoder's hit@3 gap in ADR 0030: one question, the tipping-off one.
        assertThat(McNemar.exactP(1, 0)).isEqualTo(1);
    }
}
