package com.finsentinel.platform.knowledge;

import com.finsentinel.platform.FakeEmbedder;
import com.finsentinel.platform.PlatformTest;
import com.finsentinel.platform.llm.LlmUsage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@PlatformTest
class SearchCacheTest {

    private static final BigDecimal RERANK_COST = new BigDecimal("0.000800");

    @Autowired
    KnowledgeRepository repository;
    @Autowired
    KnowledgeProperties props;
    @Autowired
    JsonMapper json;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    SearchCache cache;

    @TempDir
    Path dir;

    private final FakeEmbedder embedder = new FakeEmbedder();
    private final AtomicInteger rerankCalls = new AtomicInteger();

    @BeforeEach
    void index() throws IOException {
        jdbc.sql("delete from kb_document").update();
        KnowledgeTestDocs.manifest(dir, "pay");
        KnowledgeTestDocs.pdf(dir.resolve("docs/pay.pdf"), KnowledgeTestDocs.paymentsDoc());
        sync();
    }

    @Test
    void aRepeatedQuestionSkipsEmbeddingAndRerankingAndReportsWhatItSaved() {
        KnowledgeSearch search = search(cache, rerankReversed());

        KnowledgeSearch.SearchResult first = search.searchWithUsage("Cooling off period for a new payee?");
        int embedded = embedder.textsEmbedded();
        KnowledgeSearch.SearchResult second = search.searchWithUsage("  cooling OFF period for a   new payee ");

        assertThat(first.cacheHit()).isFalse();
        assertThat(first.usage().costUsd()).isEqualByComparingTo(RERANK_COST);
        assertThat(second.cacheHit()).isTrue();
        assertThat(second.savedUsd()).isEqualByComparingTo(RERANK_COST);
        assertThat(second.usage()).isEqualTo(LlmUsage.NONE);
        assertThat(second.hits()).isEqualTo(first.hits());
        assertThat(rerankCalls).hasValue(1);
        assertThat(embedder.textsEmbedded()).isEqualTo(embedded);
    }

    @Test
    void questionsOneWordApartAreDifferentKeys() {
        KnowledgeSearch search = search(cache, rerankReversed());

        search.searchWithUsage("who bears the loss for an authorised transfer scam");
        KnowledgeSearch.SearchResult other = search.searchWithUsage("who bears the loss for an unauthorised transfer scam");

        assertThat(other.cacheHit()).isFalse();
        assertThat(rerankCalls).hasValue(2);
    }

    @Test
    void aReingestedCorpusMisses() throws IOException {
        KnowledgeSearch search = search(cache, rerankReversed());
        search.searchWithUsage("kill switch");

        KnowledgeTestDocs.pdf(dir.resolve("docs/pay.pdf"), List.of("1    Overview", "1.1  A kill switch, rewritten."));
        sync();
        KnowledgeSearch.SearchResult after = search.searchWithUsage("kill switch");

        assertThat(after.cacheHit()).isFalse();
        assertThat(after.hits()).extracting(RegulationHit::chunkId).containsExactly("pay:1.1");
    }

    @Test
    void aDegradedRankingIsServedButNotCached() {
        AtomicInteger calls = new AtomicInteger();
        KnowledgeSearch search = search(cache, (q, candidates) -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("rerank provider down");
            }
            return candidates.stream().map(Reranker.Candidate::id).toList();
        });

        KnowledgeSearch.SearchResult duringOutage = search.searchWithUsage("suspicious transaction report");
        KnowledgeSearch.SearchResult afterOutage = search.searchWithUsage("suspicious transaction report");
        KnowledgeSearch.SearchResult cached = search.searchWithUsage("suspicious transaction report");

        assertThat(duringOutage.hits()).isNotEmpty();
        assertThat(afterOutage.cacheHit()).as("the fused order from the outage was not remembered").isFalse();
        assertThat(cached.cacheHit()).isTrue();
        assertThat(calls).hasValue(2);
    }

    @Test
    void searchWorksWithoutRedis() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        LettuceConnectionFactory factory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration("localhost", closedPort),
                LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(250)).build());
        factory.afterPropertiesSet();
        factory.start();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        try {
            SearchCache down = new SearchCache(new StringRedisTemplate(factory), json, props, meters,
                    "text-embedding-3-small");
            KnowledgeSearch search = search(down, rerankReversed());

            long started = System.nanoTime();
            KnowledgeSearch.SearchResult result = search.searchWithUsage("cooling off period");

            assertThat(result.hits()).isNotEmpty();
            assertThat(result.cacheHit()).isFalse();
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
            assertThat(meters.counter("finsentinel.knowledge.search.cache", "result", "error").count()).isEqualTo(2);
        } finally {
            factory.destroy();
        }
    }

    @Test
    void normalisingFoldsCaseSpacingAndTrailingPunctuationOnly() {
        assertThat(SearchCache.normalise("  MAS Notice 626:  STR\twhen to file?! "))
                .isEqualTo("mas notice 626: str when to file");
        assertThat(SearchCache.normalise("ｓｔｒ")).as("full-width").isEqualTo("str");
        assertThat(SearchCache.normalise("unauthorized")).isNotEqualTo(SearchCache.normalise("unauthorised"));
    }

    private Reranker rerankReversed() {
        return new Reranker() {
            @Override
            public List<String> rerank(String query, List<Candidate> candidates) {
                return candidates.stream().map(Candidate::id).toList().reversed();
            }

            @Override
            public Ranking rankWithUsage(String query, List<Candidate> candidates) {
                rerankCalls.incrementAndGet();
                return new Ranking(rerank(query, candidates), new LlmUsage("gpt-5.4-nano", 1000, 20, null, 1, 5,
                        RERANK_COST, Map.of("gpt-5.4-nano", RERANK_COST)));
            }
        };
    }

    private KnowledgeSearch search(SearchCache searchCache, Reranker reranker) {
        KnowledgeProperties.Search s = props.search();
        KnowledgeProperties withRerank = new KnowledgeProperties(dir, false, props.maxChunks(), props.chunkChars(),
                props.contextChars(), props.minFontPt(), props.batchSize(), new KnowledgeProperties.Search(s.candidates(), s.topK(), s.rrfK(),
                s.maxSearchesPerCase(), s.agentPassageChars(),
                new KnowledgeProperties.Rerank(true, s.rerank().using(), s.rerank().model(), s.rerank().maxPassageChars(),
                        s.rerank().crossEncoder()), s.cache()));
        return new KnowledgeSearch(repository, embedder, reranker, reranker, searchCache, withRerank);
    }

    private void sync() {
        KnowledgeProperties local = new KnowledgeProperties(dir, false, 5000, props.chunkChars(), props.contextChars(),
                props.minFontPt(), props.batchSize(), props.search());
        new KnowledgeSync(repository, embedder, local, json).sync();
    }
}
