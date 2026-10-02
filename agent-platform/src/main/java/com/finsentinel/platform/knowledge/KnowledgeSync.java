package com.finsentinel.platform.knowledge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Makes the database match knowledge/sources.json: new or changed PDFs (by SHA-256) are chunked, embedded and
 * swapped in; documents no longer listed are removed; unchanged ones are skipped. Safe to run on every start.
 */
@Component
public class KnowledgeSync {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeSync.class);

    private final KnowledgeRepository repository;
    private final Embedder embedder;
    private final KnowledgeProperties props;
    private final JsonMapper json;

    KnowledgeSync(KnowledgeRepository repository, Embedder embedder, KnowledgeProperties props, JsonMapper json) {
        this.repository = repository;
        this.embedder = embedder;
        this.props = props;
        this.json = json;
    }

    @EventListener(ApplicationReadyEvent.class)
    void onStartup() {
        if (props.syncOnStartup()) {
            // Off the startup thread: alert intake should not wait for embeddings.
            Thread.ofVirtual().name("knowledge-sync").start(() -> {
                try {
                    log.info("Knowledge sync: {}", sync());
                } catch (RuntimeException e) {
                    log.error("Knowledge sync failed; regulation search uses whatever was indexed before", e);
                }
            });
        }
    }

    public Result sync() {
        List<SourceDocument> sources = readManifest();
        Map<String, String> indexed = repository.documentHashes();
        List<Planned> planned = new ArrayList<>();
        int missing = 0;
        int unreadable = 0;
        for (SourceDocument doc : sources) {
            Path file = props.dir().resolve("docs").resolve(doc.file());
            if (!Files.exists(file)) {
                log.warn("{} not found; run scripts/fetch-knowledge.sh", file);
                missing++;
                continue;
            }
            String sha = sha256(file);
            if (!sha.equals(indexed.get(doc.id()))) {
                List<RegulationChunk> chunks = new RegulationChunker(props.chunkChars()).chunk(doc.id(), PdfText.pages(file, props.minFontPt()));
                if (chunks.isEmpty()) {
                    // Indexing it empty would hide the problem behind a "done" sync.
                    log.warn("{} produced no chunks; its layout does not match the chunker (numbered sections from 1)", file);
                    unreadable++;
                    continue;
                }
                planned.add(new Planned(doc, sha, chunks));
            }
        }
        int toEmbed = planned.stream().mapToInt(p -> p.chunks().size()).sum();
        if (toEmbed > props.maxChunks()) {
            throw new IllegalStateException("Sync would embed %d chunks, above max-chunks %d; check the documents"
                    .formatted(toEmbed, props.maxChunks()));
        }
        for (Planned p : planned) {
            List<float[]> vectors = embedder.embed(p.chunks().stream().map(c -> embeddingText(p.doc(), c)).toList());
            repository.replace(p.doc(), p.sha256(), p.chunks(), vectors);
            log.info("Indexed {} ({} chunks)", p.doc().id(), p.chunks().size());
        }
        repository.deleteDocumentsNotIn(sources.stream().map(SourceDocument::id).toList());
        return new Result(sources.size() - missing - unreadable - planned.size(), planned.size(), toEmbed, missing,
                unreadable);
    }

    /**
     * What gets embedded: the passage plus where it sits. "EUPG §4.7 (Duties of the responsible FI / ...)" gives the
     * vector the document and topic that the bare paragraph text often leaves implicit.
     */
    static String embeddingText(SourceDocument doc, RegulationChunk chunk) {
        return "%s §%s (%s)%n%s".formatted(doc.shortName(), chunk.ref(), chunk.section(), chunk.text());
    }

    private List<SourceDocument> readManifest() {
        JsonNode root = json.readTree(props.dir().resolve("sources.json").toFile());
        List<SourceDocument> docs = new ArrayList<>();
        root.get("documents").forEach(node -> docs.add(json.treeToValue(node, SourceDocument.class)));
        return docs;
    }

    private static String sha256(Path file) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * @param unchanged documents already indexed with the same content
     * @param indexed   documents (re)indexed by this run
     * @param chunks    chunks embedded by this run (many may have come from the embedding cache)
     * @param missing    documents listed in sources.json but not downloaded
     * @param unreadable documents that produced no chunks and were not indexed
     */
    public record Result(int unchanged, int indexed, int chunks, int missing, int unreadable) {
    }

    private record Planned(SourceDocument doc, String sha256, List<RegulationChunk> chunks) {
    }
}
