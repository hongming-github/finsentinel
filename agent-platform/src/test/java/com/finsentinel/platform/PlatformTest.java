package com.finsentinel.platform;

import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Shared setup for platform integration tests. Every test class uses the same properties and imports,
 * so Spring caches one context (and one set of containers) for the whole run. The server listens on a random port,
 * so tests can also reach it as a client would (the MCP endpoint). Scheduled investigation
 * polling and knowledge sync are off, reranking is off, and embeddings are faked: normal tests never call a
 * model API, every agent (investigator, specialists, writer, reviewer) is a stub, and the external systems are
 * fakes. Analyses run in SINGLE mode unless a test asks for MULTI. Live tests opt back in with
 * {@code finsentinel.test.fake-ai=false}. Requests authenticate with {@link TestTokens}.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "finsentinel.investigation.enabled=false",
        "finsentinel.knowledge.sync-on-startup=false",
        "finsentinel.outbox.enabled=false",
        "finsentinel.knowledge.search.rerank.enabled=false",
        // Absolute: with a real servlet context a relative "../" path is resolved against the web root and fails.
        "finsentinel.knowledge.dir=${user.dir}/../knowledge",
        "finsentinel.analysis.mode=SINGLE"})
@AutoConfigureMockMvc
@ExtendWith(SystemScopeExtension.class)
@Import({TestcontainersConfig.class, StubCaseInvestigator.class, StubSpecialistAgents.class, StubReportWriter.class,
        StubCaseReviewer.class, FakeEmbedder.class, FakeExternalSystems.class})
public @interface PlatformTest {
}
