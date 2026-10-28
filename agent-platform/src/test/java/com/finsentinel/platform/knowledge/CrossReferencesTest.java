package com.finsentinel.platform.knowledge;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CrossReferencesTest {

    private static final SourceDocument NOTICE = doc("notice", "MAS Notice 626");
    private static final SourceDocument EUPG = doc("eupg", "EUPG");

    private static final List<RegulationChunk> NOTICE_CHUNKS = List.of(
            chunk("notice", "6.1", "CUSTOMER DUE DILIGENCE / (I) Identification of Customer", "A bank shall identify."),
            chunk("notice", "6.9", "CUSTOMER DUE DILIGENCE / (II) Verification of Identity", "A bank shall verify."),
            chunk("notice", "7.1", "SIMPLIFIED CUSTOMER DUE DILIGENCE", "A bank may perform simplified CDD."),
            chunk("notice", "8.1", "ENHANCED CUSTOMER DUE DILIGENCE", "A bank shall perform enhanced CDD."),
            chunk("notice", "14.4", "SUSPICIOUS TRANSACTIONS REPORTING", """
                    Where a bank reasonably believes that performing any of the measures as required by
                    paragraphs 6, 7 or 8 will tip-off a customer, the bank may stop performing those measures."""));
    private static final List<RegulationChunk> EUPG_CHUNKS = List.of(
            chunk("eupg", "4.7", "Duties of the responsible FI / Responsible FI to impose a cooling off period", "..."),
            chunk("eupg", "4.10", "Duties of the responsible FI / Responsible FI to provide transaction alerts", "..."));

    @Test
    void namesTheSectionsAListOfNumbersPointsTo() {
        assertThat(references(300).contextOf("notice", NOTICE_CHUNKS.get(4))).isEqualTo("Refers to: "
                + "§6 (CUSTOMER DUE DILIGENCE); §7 (SIMPLIFIED CUSTOMER DUE DILIGENCE); §8 (ENHANCED CUSTOMER DUE DILIGENCE)");
    }

    @Test
    void expandsARangeOfSections() {
        assertThat(references(300).contextOf("notice", chunk("notice", "9.1", "RECORD KEEPING",
                "the measures in paragraphs 6 to 8 of this Notice")))
                .contains("§6 (", "§7 (", "§8 (");
    }

    @Test
    void aParagraphInTheSameSectionKeepsOnlyItsSubHeading() {
        RegulationChunk chunk = chunk("notice", "6.20", "CUSTOMER DUE DILIGENCE / (V) Ongoing Monitoring",
                "verified in accordance with paragraph 6.9");

        assertThat(references(300).contextOf("notice", chunk)).isEqualTo("Refers to: §6.9 ((II) Verification of Identity)");
    }

    @Test
    void theSectionAPassageIsInIsNotRepeated() {
        RegulationChunk chunk = chunk("notice", "6.20", "CUSTOMER DUE DILIGENCE / (V) Ongoing Monitoring",
                "the measures in paragraph 6 apply");

        assertThat(references(300).contextOf("notice", chunk)).isEmpty();
    }

    @Test
    void resolvesAnotherDocumentOfTheCorpusByItsShortName() {
        RegulationChunk srf = chunk("notice", "4.2.1", "Duties of the responsible FI",
                "the cooling off period in paragraph 4.7 of the EUPG");

        assertThat(references(300).contextOf("notice", srf))
                .isEqualTo("Refers to: EUPG §4.7 (Duties of the responsible FI / Responsible FI to impose a cooling off period)");
    }

    @Test
    void leavesOutOtherInstrumentsAndNumbersThatNameNoSection() {
        RegulationChunk chunk = chunk("notice", "2.1", "DEFINITIONS",
                "as defined in paragraph 2.1 of MAS Notice TCA-N03, under paragraph 3 of the First Schedule, "
                        + "and in paragraph 99");

        assertThat(references(300).contextOf("notice", chunk)).isEmpty();
    }

    @Test
    void dropsWholeEntriesPastTheLimitAndZeroTurnsItOff() {
        RegulationChunk chunk = NOTICE_CHUNKS.get(4);

        assertThat(references(60).contextOf("notice", chunk)).isEqualTo("Refers to: §6 (CUSTOMER DUE DILIGENCE)");
        assertThat(references(20).contextOf("notice", chunk)).isEmpty();
        assertThat(references(0).contextOf("notice", chunk)).isEmpty();
    }

    private static CrossReferences references(int maxChars) {
        Map<SourceDocument, List<RegulationChunk>> corpus = new LinkedHashMap<>();
        corpus.put(NOTICE, NOTICE_CHUNKS);
        corpus.put(EUPG, EUPG_CHUNKS);
        return new CrossReferences(corpus, maxChars);
    }

    private static SourceDocument doc(String id, String shortName) {
        return new SourceDocument(id, shortName, "Test " + id, "https://example.test/" + id + ".pdf", id + ".pdf");
    }

    private static RegulationChunk chunk(String docId, String ref, String section, String text) {
        return new RegulationChunk(docId + ":" + ref, docId, ref, section, 1, text);
    }
}
