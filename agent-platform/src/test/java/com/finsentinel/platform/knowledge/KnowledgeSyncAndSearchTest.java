package com.finsentinel.platform.knowledge;

import com.finsentinel.platform.FakeEmbedder;
import com.finsentinel.platform.PlatformTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@PlatformTest
class KnowledgeSyncAndSearchTest {

    @Autowired
    KnowledgeRepository repository;
    @Autowired
    KnowledgeProperties props;
    @Autowired
    JsonMapper json;
    @Autowired
    JdbcClient jdbc;

    @TempDir
    Path dir;

    private final FakeEmbedder embedder = new FakeEmbedder();

    @BeforeEach
    void cleanKnowledgeBase() throws IOException {
        jdbc.sql("delete from kb_document").update();
        KnowledgeTestDocs.manifest(dir, "pay");
        KnowledgeTestDocs.pdf(dir.resolve("docs/pay.pdf"), KnowledgeTestDocs.paymentsDoc());
    }

    @Test
    void syncIsIdempotentAndReindexesOnlyChangedDocuments() throws IOException {
        KnowledgeSync sync = sync(5000);

        assertThat(sync.sync()).isEqualTo(new KnowledgeSync.Result(0, 1, 4, 0, 0));
        int embedded = embedder.textsEmbedded();
        assertThat(sync.sync()).as("unchanged file").isEqualTo(new KnowledgeSync.Result(1, 0, 0, 0, 0));
        assertThat(embedder.textsEmbedded()).isEqualTo(embedded);

        KnowledgeTestDocs.pdf(dir.resolve("docs/pay.pdf"), List.of("1    Overview", "1.1  Rewritten."));
        assertThat(sync.sync().indexed()).isEqualTo(1);
        assertThat(repository.chunkCount()).contains(1);
    }

    @Test
    void documentsDroppedFromTheManifestAreRemoved() throws IOException {
        sync(5000).sync();
        KnowledgeTestDocs.manifest(dir);

        sync(5000).sync();

        assertThat(repository.chunkCount()).contains(0);
    }

    @Test
    void oversizedSyncIsRefusedBeforeAnyEmbedding() {
        assertThatThrownBy(() -> sync(2).sync()).hasMessageContaining("above max-chunks 2");
        assertThat(embedder.textsEmbedded()).isZero();
    }

    @Test
    void missingPdfIsReportedNotFatal() throws IOException {
        KnowledgeTestDocs.manifest(dir, "pay", "absent");

        assertThat(sync(5000).sync().missing()).isEqualTo(1);
    }

    @Test
    void documentTheChunkerCannotReadIsNotIndexedAsEmpty() throws IOException {
        KnowledgeTestDocs.pdf(dir.resolve("docs/pay.pdf"), List.of("Just prose without numbered paragraphs."));

        KnowledgeSync.Result result = sync(5000).sync();

        assertThat(result.unreadable()).isEqualTo(1);
        assertThat(result.indexed()).isZero();
    }

    @Test
    void keywordVectorAndHybridSearchFindTheCoolingOffParagraph() {
        sync(5000).sync();
        KnowledgeSearch search = search((q, candidates) -> candidates.stream().map(Reranker.Candidate::id).toList());

        for (KnowledgeSearch.Mode mode : KnowledgeSearch.Mode.values()) {
            List<RegulationHit> hits = search.search("how long is the cooling off period for a new payee", mode, 2);
            assertThat(hits).as(mode.name()).first().satisfies(h -> {
                assertThat(h.chunkId()).isEqualTo("pay:2.1");
                assertThat(h.citation()).isEqualTo("pay §2.1");
                assertThat(h.section()).isEqualTo("Duties of the responsible FI");
            });
        }
    }

    @Test
    void rerankerOrderWinsAndAFailingRerankerFallsBackToFusion() {
        sync(5000).sync();
        KnowledgeSearch reordering = search((q, candidates) -> List.of("pay:3.1", "pay:2.1"));
        KnowledgeSearch broken = search((q, candidates) -> {
            throw new IllegalStateException("rerank model down");
        });

        assertThat(reordering.search("cooling off period", KnowledgeSearch.Mode.HYBRID_RERANK, 1))
                .extracting(RegulationHit::chunkId).containsExactly("pay:3.1");
        assertThat(broken.search("cooling off period", KnowledgeSearch.Mode.HYBRID_RERANK, 1))
                .extracting(RegulationHit::chunkId).containsExactly("pay:2.1");
    }

    @Test
    void reciprocalRankFusionRewardsAgreementBetweenLists() {
        List<String> fused = KnowledgeSearch.fuse(List.of(List.of("a", "b", "c"), List.of("c", "b", "d")), 60);

        assertThat(fused.subList(0, 2)).containsExactlyInAnyOrder("b", "c");
        assertThat(fused).containsExactlyInAnyOrder("a", "b", "c", "d");
    }

    private KnowledgeSync sync(int maxChunks) {
        KnowledgeProperties p = new KnowledgeProperties(dir, false, maxChunks, props.chunkChars(), props.minFontPt(), props.batchSize(),
                props.search());
        return new KnowledgeSync(repository, embedder, p, json);
    }

    private KnowledgeSearch search(Reranker reranker) {
        return new KnowledgeSearch(repository, embedder, reranker, props);
    }
}
