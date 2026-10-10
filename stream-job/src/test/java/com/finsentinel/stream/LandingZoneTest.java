package com.finsentinel.stream;

import com.finsentinel.common.Channel;
import com.finsentinel.common.Transaction;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class LandingZoneTest {

    private static final Transaction LATE_EVENING = new Transaction("t-1", "straits", "acc-1", null,
            new BigDecimal("12.30"), "SGD", "m-1", "5411", Channel.CARD_PRESENT, "SG",
            Instant.parse("2026-10-09T23:59:59.999999Z"));

    @Test
    void eachTransactionIsOneJsonLineWithIsoTimesAndExactAmounts() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new LandingZone.JsonLines().encode(LATE_EVENING, out);
        new LandingZone.JsonLines().encode(LATE_EVENING, out);

        String[] lines = out.toString(StandardCharsets.UTF_8).split("\n");
        assertThat(lines).hasSize(2);
        assertThat(lines[0]).contains("\"eventTime\":\"2026-10-09T23:59:59.999999Z\"").contains("\"amount\":12.30");
        assertThat(JsonMapper.builder().build().readValue(lines[0], Transaction.class)).isEqualTo(LATE_EVENING);
    }

    @Test
    void transactionsAreBucketedByTheirUtcEventDateNotByArrival() {
        assertThat(new LandingZone.EventDate().getBucketId(LATE_EVENING, null)).isEqualTo("dt=2026-10-09");
    }
}
