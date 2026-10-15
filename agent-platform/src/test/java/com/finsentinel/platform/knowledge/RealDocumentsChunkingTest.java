package com.finsentinel.platform.knowledge;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Spot checks against the real MAS PDFs. They are not committed, so this skips unless scripts/fetch-knowledge.sh
 * has been run. Free: no embeddings, no model calls.
 */
class RealDocumentsChunkingTest {

    private static final Path DOCS = Path.of("../knowledge/docs");
    private final RegulationChunker chunker = new RegulationChunker(2400);

    @Test
    void eupgCoolingOffParagraphIsIntactAndWellLabelled() {
        RegulationChunk chunk = find(chunks("eupg-2024"), "eupg-2024:4.7");

        assertThat(chunk.text()).contains("cooling off period of at least 12 hours").doesNotContain("Responsible FI to inform");
        assertThat(chunk.section()).startsWith("Duties of the responsible FI / Responsible FI to impose a cooling off period");
    }

    @Test
    void footnotesAndMarkersAreNotPartOfParagraphs() {
        RegulationChunk reporting = find(chunks("eupg-2024"), "eupg-2024:3.14");

        assertThat(reporting.text()).contains("no later than 30 calendar days").doesNotContain("Such links are only");
        assertThat(find(chunks("eupg-2024"), "eupg-2024:4.7").text()).contains("12 hours where").doesNotContain("hours13");
    }

    @Test
    void trmParagraphsCarryTheirSubsectionNotTheFooter() {
        List<RegulationChunk> trm = chunks("trm-2021");

        assertThat(find(trm, "trm-2021:3.1.1").section()).endsWith("Role of the Board of Directors and Senior Management");
        assertThat(trm).noneMatch(c -> c.section().contains("Monetary Authority of Singapore"));
        assertThat(trm).noneMatch(c -> c.text().matches("(?s).*\\.{5,}.*"));
    }

    @Test
    void notice626HasSuspiciousTransactionReporting() {
        assertThat(chunks("notice-626-2025"))
                .anyMatch(c -> c.section().toUpperCase(Locale.ROOT).contains("SUSPICIOUS TRANSACTIONS REPORTING"));
    }

    private List<RegulationChunk> chunks(String docId) {
        Path pdf = DOCS.resolve(docId + ".pdf");
        assumeTrue(Files.exists(pdf), "run scripts/fetch-knowledge.sh to enable this test");
        return chunker.chunk(docId, PdfText.pages(pdf, 10f));
    }

    private static RegulationChunk find(List<RegulationChunk> chunks, String id) {
        return chunks.stream().filter(c -> c.chunkId().equals(id)).findFirst()
                .orElseThrow(() -> new AssertionError("no chunk " + id));
    }
}
