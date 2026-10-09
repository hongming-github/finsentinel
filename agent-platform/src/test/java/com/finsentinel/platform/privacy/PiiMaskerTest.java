package com.finsentinel.platform.privacy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class PiiMaskerTest {

    private final PiiMasker masker = new PiiMasker(new PrivacyProperties("test-key-of-sixteen-chars"));

    @Test
    void personalIdentifiersInFreeTextAreReplaced() {
        String note = "Customer S1234567D called from +65 9123 4567 (alt 81234567), email Tan.Wei@example.com.sg, "
                + "card 4111 1111 1111 1111 reported lost.";

        String masked = masker.mask(note, "case-1");

        assertThat(masked).doesNotContain("S1234567D", "9123 4567", "81234567", "Tan.Wei@example.com.sg",
                "4111 1111 1111 1111");
        assertThat(masked).containsPattern("\\[NRIC_[0-9a-f]{8}]").containsPattern("\\[PHONE_[0-9a-f]{8}]")
                .containsPattern("\\[EMAIL_[0-9a-f]{8}]").containsPattern("\\[CARD_[0-9a-f]{8}]")
                .contains("Customer ", " reported lost.");
    }

    @Test
    void theSameValueIsTheSamePseudonymWithinACaseOnly() {
        String a = masker.mask("+65 9123 4567", "case-1");

        assertThat(masker.mask("91234567", "case-1")).as("same number, other formatting").isEqualTo(a);
        assertThat(masker.mask("+65 9123 4567", "case-2")).isNotEqualTo(a);
        assertThat(new PiiMasker(new PrivacyProperties("another-key-sixteen")).mask("+65 9123 4567", "case-1"))
                .as("without the key, not reproducible").isNotEqualTo(a);
    }

    @Test
    void maskingTwiceChangesNothing() {
        String once = masker.mask("+65 9123 4567, S1234567D, a@b.co, 4111111111111111", "case-1");

        assertThat(masker.mask(once, "case-1")).isEqualTo(once);
        assertThat(masker.mask("[PHONE_91234567]", "case-1")).as("an all-digit pseudonym").isEqualTo("[PHONE_91234567]");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "\"txnId\":\"straits-txn-91234567\"",
            "\"caseId\":\"97021919-15c6-4b74-8395-846d343df296\"",
            "\"amount\":91234567.50",
            "\"eventTime\":\"2026-10-09T05:04:53.274Z\"",
            "\"alertId\":\"a91234567b\"",
            "\"accountId\":\"straits-acc-0073\",\"merchantId\":\"payee-new-659773\"",
            "\"chunkId\":\"notice-626-2025:14.2\"",
            "4111 1111 1111 1112",
            "MAS Notice 626 paragraph 14.2, S$1,500 threshold"})
    void dataThatOnlyLooksNumericIsLeftAlone(String text) {
        assertThat(masker.mask(text, "case-1")).isEqualTo(text);
    }
}
