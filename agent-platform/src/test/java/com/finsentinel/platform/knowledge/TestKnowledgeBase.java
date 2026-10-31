package com.finsentinel.platform.knowledge;

import com.finsentinel.platform.FakeEmbedder;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Path;

/** Puts the small test payments document into the shared database, for tests outside this package. */
public final class TestKnowledgeBase {

    private TestKnowledgeBase() {
    }

    /** Replaces the knowledge base with one document, chunk ids {@code pay:1.1} to {@code pay:3.1}. */
    public static void indexPaymentsDoc(ApplicationContext context, Path dir) throws IOException {
        context.getBean(JdbcClient.class).sql("delete from kb_document").update();
        KnowledgeTestDocs.manifest(dir, "pay");
        KnowledgeTestDocs.pdf(dir.resolve("docs/pay.pdf"), KnowledgeTestDocs.paymentsDoc());
        KnowledgeProperties props = context.getBean(KnowledgeProperties.class);
        KnowledgeProperties local = new KnowledgeProperties(dir, false, 5000, props.chunkChars(), props.contextChars(),
                props.minFontPt(), props.batchSize(), props.search());
        new KnowledgeSync(context.getBean(KnowledgeRepository.class), new FakeEmbedder(), local,
                context.getBean(JsonMapper.class)).sync();
    }
}
