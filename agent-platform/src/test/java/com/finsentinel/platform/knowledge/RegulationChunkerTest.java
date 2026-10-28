package com.finsentinel.platform.knowledge;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RegulationChunkerTest {

    private final RegulationChunker chunker = new RegulationChunker(400);

    @Test
    void oneChunkPerNumberedParagraphWithSectionAndPage() {
        List<RegulationChunk> chunks = chunker.chunk("doc", List.of("""
                Cover page text that is not a paragraph
                1        Overview
                1.1      These Guidelines apply to responsible FIs.
                1.2      They take effect from 16 December 2024.
                """, """
                2        Duties of the responsible FI
                2.1      A responsible FI should impose a cooling off period of at least 12 hours
                         where high-risk activities cannot be performed.
                """));

        assertThat(chunks).extracting(RegulationChunk::chunkId).containsExactly("doc:1.1", "doc:1.2", "doc:2.1");
        RegulationChunk cooling = chunks.get(2);
        assertThat(cooling.section()).isEqualTo("Duties of the responsible FI");
        assertThat(cooling.page()).isEqualTo(2);
        assertThat(cooling.text()).contains("12 hours").contains("high-risk activities cannot be performed");
    }

    @Test
    void noiseLinesAreDropped() {
        List<RegulationChunk> chunks = chunker.chunk("doc", List.of("""
                1        Overview
                1.1      Text before the footer.
                                                             [Amended on 24 October 2024]
                                          Internal
                3
                More text after the page break.
                """));

        assertThat(chunks).singleElement().extracting(RegulationChunk::text)
                .isEqualTo("Text before the footer.\nMore text after the page break.");
    }

    @Test
    void footnoteThatLooksLikeAHeadingDoesNotStartASection() {
        List<RegulationChunk> chunks = chunker.chunk("doc", List.of("""
                1        Overview
                1.1      A responsible FI should impose a cooling off period.
                13 The cooling off period applies to new devices
                2        Definitions
                2.1      Words have meanings.
                """));

        assertThat(chunks).extracting(RegulationChunk::section).containsExactly("Overview", "Definitions");
        assertThat(chunks.getFirst().text()).contains("13 The cooling off period");
    }

    @Test
    void wrappedHeadingAtTheEndOfAParagraphIntroducesTheNextOne() {
        List<RegulationChunk> chunks = chunker.chunk("doc", List.of("""
                1        Duties of the responsible FI
                1.6      A responsible FI should list its website on the FID.
                Responsible FI to impose a cooling off period when performing
                high-risk activities
                1.7      A responsible FI should impose a cooling off period of at least 12 hours.
                1.8      A responsible FI should inform the account user of the risks.
                """));

        assertThat(chunks.getFirst().text()).isEqualTo("A responsible FI should list its website on the FID.");
        assertThat(chunks.get(1).section())
                .isEqualTo("Duties of the responsible FI / Responsible FI to impose a cooling off period when performing high-risk activities");
        assertThat(chunks.get(2).section()).as("sub-heading carries forward").isEqualTo(chunks.get(1).section());
    }

    @Test
    void romanNumeralSubheadingsIntroduceTheirParagraphs() {
        // Notice 626 numbers its sub-headings "(I)", "(II)" ...: read as text, 6.13 kept the sub-heading of 6.2.
        List<RegulationChunk> chunks = chunker.chunk("doc", List.of("""
                1        Customer due diligence
                1.1      A bank shall not open anonymous accounts.
                Where There Are Reasonable Grounds for Suspicion
                1.2      Where a bank suspects money laundering, it shall perform CDD measures.
                (I)      Identification of Customer
                1.3      A bank shall identify each customer.
                (II)     Identification and Verification of Identity of Natural Person Appointed to Act on a
                Customer’s Behalf
                1.4      Where a customer appoints a natural person to act on its behalf, the bank shall identify them.
                1.5      A bank shall verify the due authority of each such person.
                """));

        assertThat(chunks).extracting(RegulationChunk::ref).containsExactly("1.1", "1.2", "1.3", "1.4", "1.5");
        assertThat(chunks.get(1).text()).isEqualTo("Where a bank suspects money laundering, it shall perform CDD measures.");
        assertThat(chunks.get(2).section()).isEqualTo("Customer due diligence / (I) Identification of Customer");
        assertThat(chunks.get(3).section()).isEqualTo("Customer due diligence / (II) Identification and Verification of "
                + "Identity of Natural Person Appointed to Act on a Customer’s Behalf");
        assertThat(chunks.get(4).section()).as("sub-heading carries forward").isEqualTo(chunks.get(3).section());
    }

    @Test
    void aLetteredSectionInsertedByAmendmentIsASectionOfItsOwn() {
        // Notice 626 inserted "11A VALUE TRANSFERS" between 11 and 12: read as text, the whole section sat inside
        // 11.2's last definition. Lettered paragraphs within a section (6.8A) stay with the paragraph before them.
        List<RegulationChunk> chunks = chunker.chunk("doc", List.of("""
                1        Wire transfers
                1.1      This paragraph applies to wire transfers.
                1.2      For the purposes of paragraph 1 –
                “wire transfer originator” means the account holder who allows the wire transfer.
                1A       Value transfers
                1A.1 Paragraph 1A shall apply to a bank that sends digital tokens.
                1A.2 Before effecting a value transfer, a bank shall identify the originator, as paragraph
                1A.1 requires.
                1A.3 A bank shall keep the records for five years.
                1A.3A A bank shall also keep the originator's address.
                2        Record keeping
                2.1      A bank shall keep records.
                """));

        assertThat(chunks).extracting(RegulationChunk::chunkId).containsExactly("doc:1.1", "doc:1.2",
                "doc:1.2:wire-transfer-originator", "doc:1A.1", "doc:1A.2", "doc:1A.3", "doc:2.1");
        assertThat(chunks.get(2).text()).isEqualTo(
                "“wire transfer originator” means the account holder who allows the wire transfer.");
        assertThat(chunks.get(3).section()).isEqualTo("Value transfers");
        assertThat(chunks.get(4).text()).as("a cross-reference wrapped to a line start stays in its paragraph")
                .endsWith("1A.1 requires.");
        assertThat(chunks.get(5).text()).contains("1A.3A A bank shall also keep the originator's address.");
        assertThat(chunks.get(6).section()).isEqualTo("Record keeping");
    }

    @Test
    void aRomanNumeralSubheadingUnderAnUnnumberedOneIsALevelOfItsOwn() {
        List<RegulationChunk> chunks = chunker.chunk("doc", List.of("""
                1        Wire transfers
                1.1      This paragraph applies to wire transfers.
                Responsibility of the Ordering Institution
                (I)      Identification and Recording of Information
                1.2      Before effecting a wire transfer, a bank shall identify the originator.
                """));

        assertThat(chunks.getFirst().text()).isEqualTo("This paragraph applies to wire transfers.");
        assertThat(chunks.get(1).section()).isEqualTo(
                "Wire transfers / Responsibility of the Ordering Institution / (I) Identification and Recording of Information");
    }

    @Test
    void numberedSubsectionHeadingIsNotItsOwnChunk() {
        List<RegulationChunk> chunks = chunker.chunk("doc", List.of("""
                1        Technology Risk Governance and Oversight
                1.1      Role of the Board of Directors and Senior Management
                1.1.1    Technology is a key business enabler.
                1.1.2    The board should approve the framework.
                """));

        assertThat(chunks).extracting(RegulationChunk::ref).containsExactly("1.1.1", "1.1.2");
        assertThat(chunks).allSatisfy(c -> assertThat(c.section())
                .isEqualTo("Technology Risk Governance and Oversight / Role of the Board of Directors and Senior Management"));
    }

    @Test
    void definitionsSplitIntoOneChunkPerTerm() {
        List<RegulationChunk> chunks = chunker.chunk("doc", List.of("""
                1        Definitions
                1.1      For the purposes of these Guidelines:
                “account holder” means any person in whose name a payment account has been opened;
                “protected account” means any payment account that—
                        (a) is held in the name of one or more individuals;
                """));

        assertThat(chunks).extracting(RegulationChunk::chunkId)
                .containsExactly("doc:1.1:account-holder", "doc:1.1:protected-account");
        assertThat(chunks.get(1).text()).contains("(a) is held in the name");
    }

    @Test
    void definitionsInEveryFormTheMasDocumentsUseStartTheirOwnChunk() {
        // Each form below was merged into the previous definition's chunk, so "unauthorised transaction" was
        // retrieved, and cited, as "unique identifier".
        List<RegulationChunk> chunks = chunker.chunk("doc", List.of("""
                1        Definitions
                1.1      For the purposes of these Guidelines:
                “unique identifier” means a combination of letters, numbers or symbols;
                “unauthorised transaction”   in relation to any protected account, means any payment
                transaction initiated by any person without the knowledge of an account user;
                “subscriber”, in relation to a responsible Telco, means—
                “high-risk activities” include, but are not limited to—
                “CDD measures” or “customer due diligence measures” means the measures required;
                “connected party” –
                        (a) in relation to a legal person, means any director;
                “similar services” includes services undertaken for securities transactions;
                """));

        assertThat(chunks).extracting(RegulationChunk::chunkId).containsExactly("doc:1.1:unique-identifier",
                "doc:1.1:unauthorised-transaction", "doc:1.1:subscriber", "doc:1.1:high-risk-activities",
                "doc:1.1:cdd-measures", "doc:1.1:connected-party", "doc:1.1:similar-services");
        assertThat(chunks.get(1).text()).endsWith("without the knowledge of an account user;");
        assertThat(chunks.get(5).text()).contains("(a) in relation to a legal person");
    }

    @Test
    void aQuotedTermInsideADefinitionDoesNotStartANewOne() {
        List<RegulationChunk> chunks = chunker.chunk("doc", List.of("""
                1        Definitions
                1.1      For the purposes of these Guidelines:
                “protected account” means any payment account that—
                “bank” or “finance company” as the case may be, and held in the name of individuals;
                """));

        assertThat(chunks).extracting(RegulationChunk::chunkId).doesNotContain("doc:1.1:bank");
        assertThat(chunks.getLast().text()).contains("“bank” or “finance company” as the case may be");
    }

    @Test
    void crossReferenceWrappedToLineStartStaysInItsParagraph() {
        List<RegulationChunk> chunks = chunker.chunk("doc", List.of("""
                1        Responsibility for losses
                1.6      The Telco bears the loss in these cases.
                1.7      Where neither party bears the loss under paragraph 1.2 or
                1.4 of these Guidelines, the account holder should bear any loss.
                1.9 Further details are in the annex.
                """));

        assertThat(chunks).extracting(RegulationChunk::ref).containsExactly("1.6", "1.7", "1.9");
        assertThat(chunks.get(1).text()).contains("1.4 of these Guidelines, the account holder should bear any loss.");
    }

    @Test
    void documentWhoseFirstSectionIsNotOneYieldsNothingByDesign() {
        // A date on a cover page ("16 December 2024") has the shape of a heading. Requiring section 1 first keeps
        // it from derailing numbering; KnowledgeSync warns when a document produces no chunks.
        assertThat(chunker.chunk("doc", List.of("16 December 2024\n2        Duties\n2.1      Text.\n"))).isEmpty();
    }

    @Test
    void longParagraphsAreSplitIntoParts() {
        String longText = "Word ".repeat(60).strip();
        List<RegulationChunk> chunks = chunker.chunk("doc", List.of("""
                1        Overview
                1.1      %s
                %s
                %s
                """.formatted(longText, longText, longText)));

        assertThat(chunks).extracting(RegulationChunk::chunkId).containsExactly("doc:1.1-p1", "doc:1.1-p2", "doc:1.1-p3");
        assertThat(chunks).allSatisfy(c -> assertThat(c.text().length()).isLessThanOrEqualTo(400));
    }

    @Test
    void runningFooterWithChangingPageNumberIsDropped() {
        List<String> pages = List.of(
                "1        Overview\n1.1      First.\nMonetary Authority of Singapore  1\n",
                "1.2      Second.\nMonetary Authority of Singapore  2\n",
                "1.3      Third.\nMonetary Authority of Singapore  3\n",
                "1.4      Fourth.\nMonetary Authority of Singapore  4\n");

        assertThat(chunker.chunk("doc", pages)).extracting(RegulationChunk::text)
                .containsExactly("First.", "Second.", "Third.", "Fourth.");
    }
}
