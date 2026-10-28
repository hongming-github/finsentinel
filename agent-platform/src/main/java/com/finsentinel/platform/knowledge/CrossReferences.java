package com.finsentinel.platform.knowledge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Names what a passage's cross-references point to, from the documents' own headings. Notice 626 14.4 says "the
 * measures as required by paragraphs 6, 7 or 8"; a question about "due diligence" shares no word with it, and the
 * cross-encoder ranked it below passages that merely contain the term (ADR 0030). The context line, "Refers to: §6
 * (Customer Due Diligence); ...", is stored with the passage and shown to the reranker, which reads the question and
 * the passage together. It is not embedded (see {@link KnowledgeSync#embeddingText}), and the passage text the agent
 * quotes stays the document's own.
 *
 * <p>Resolved: "paragraph(s) X[, Y and|or|to Z]" inside the same document ("of these Guidelines", "of this Notice"
 * or nothing), or "of the EUPG"-style references to another document of the corpus by its short name, and "Annex A".
 * References to other instruments (an Act, another notice) and numbers that name no section are left out, as are
 * titles the passage already carries in its own section.
 */
final class CrossReferences {

    private static final String REF = "\\d+[A-Z]?(?:\\.\\d+[A-Z]?)*(?:\\([a-z]+\\))*";
    private static final Pattern PARAGRAPHS = Pattern.compile(
            "\\bparagraphs?\\s+(" + REF + "(?:\\s*(?:,\\s*and|,\\s*or|,|and|or|to)\\s+(?:paragraphs?\\s+)?" + REF
                    + ")*)(\\s+(?:of|in|under)\\s+(?:the\\s+)?([\\w-]+(?:\\s+[\\w-]+)?))?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ANNEX = Pattern.compile("\\bAnnex\\s+([A-Z])\\b");
    private static final Pattern ONE_REF = Pattern.compile(REF);
    private static final Pattern RANGE = Pattern.compile("(?<![.\\d])(\\d+)\\s+to\\s+(?:paragraphs?\\s+)?(\\d+)(?![.\\dA-Z])");
    private static final Pattern SUFFIX = Pattern.compile("(?:[A-Z]|\\([a-z]+\\))+$");

    private final Map<String, Headings> byDoc = new HashMap<>();
    private final Map<String, String> docByShortName = new HashMap<>();
    private final int maxChars;

    /**
     * @param corpus   every document's chunks; references may point across documents of the corpus
     * @param maxChars longest line; entries past it are dropped whole, 0 means no lines at all
     */
    CrossReferences(Map<SourceDocument, List<RegulationChunk>> corpus, int maxChars) {
        this.maxChars = maxChars;
        corpus.forEach((doc, chunks) -> {
            byDoc.put(doc.id(), new Headings(doc.shortName(), chunks));
            docByShortName.put(doc.shortName(), doc.id());
        });
    }

    /** "Refers to: ..." for this chunk of {@code docId}, or an empty string when it refers to nothing resolvable. */
    String contextOf(String docId, RegulationChunk chunk) {
        Map<String, List<String>> refsByTarget = new LinkedHashMap<>();
        String text = chunk.text().replaceAll("\\s+", " ");
        Matcher m = PARAGRAPHS.matcher(text);
        while (m.find()) {
            String target = targetDoc(docId, m.group(3));
            if (target != null) {
                for (String ref : refs(m.group(1))) {
                    add(refsByTarget, docId, chunk, target, ref);
                }
            }
        }
        Matcher annex = ANNEX.matcher(text);
        while (annex.find()) {
            add(refsByTarget, docId, chunk, docId, annex.group(1));
        }
        StringBuilder line = new StringBuilder("Refers to: ");
        int entries = 0;
        for (Map.Entry<String, List<String>> e : refsByTarget.entrySet()) {
            String entry = (entries == 0 ? "" : "; ") + e.getKey().replace("{refs}", String.join(", ", e.getValue()));
            if (line.length() + entry.length() > maxChars) {
                break;
            }
            line.append(entry);
            entries++;
        }
        return entries == 0 ? "" : line.toString();
    }

    private void add(Map<String, List<String>> refsByTarget, String docId, RegulationChunk chunk, String target,
                     String ref) {
        Headings headings = byDoc.get(target);
        String title = headings.titleOf(ref);
        if (title == null) {
            return;
        }
        // Within the passage's own section, its title is already in the passage's header: keep the sub-heading.
        String ownTop = topTitle(chunk.section());
        if (target.equals(docId) && title.startsWith(ownTop + " / ")) {
            title = title.substring(ownTop.length() + 3);
        }
        boolean ownSection = target.equals(docId)
                && chunk.section().toLowerCase(Locale.ROOT).contains(title.toLowerCase(Locale.ROOT));
        if (ownSection || ref.equals(chunk.ref())) {
            return;
        }
        String prefix = target.equals(docId) ? "" : headings.shortName + " ";
        String key = prefix + "{refs} (" + title + ")";
        List<String> refs = refsByTarget.computeIfAbsent(key, k -> new ArrayList<>());
        if (!refs.contains("§" + ref)) {
            refs.add("§" + ref);
        }
    }

    /** The document a reference points into: this one, another of the corpus, or null for an outside instrument. */
    private String targetDoc(String docId, String named) {
        if (named == null) {
            return docId;
        }
        String lower = named.toLowerCase(Locale.ROOT);
        if (lower.startsWith("these ") || lower.startsWith("this ")) {
            return docId;
        }
        for (Map.Entry<String, String> doc : docByShortName.entrySet()) {
            if (named.startsWith(doc.getKey())) {
                return doc.getValue();
            }
        }
        // A capitalised name is another instrument ("of the CDSA", "of MAS Notice TCA-N03"); lower case words
        // ("in relation to") are the sentence going on.
        return Character.isUpperCase(named.charAt(0)) ? null : docId;
    }

    /** "6, 7 or 8" -> 6, 7, 8; "6 to 8" -> 6, 7, 8; "6.3 to 6.5" -> 6.3, 6.5. */
    private static List<String> refs(String list) {
        List<String> out = new ArrayList<>();
        Matcher range = RANGE.matcher(list);
        while (range.find()) {
            int from = Integer.parseInt(range.group(1));
            int to = Integer.parseInt(range.group(2));
            for (int n = from + 1; n < to; n++) {
                out.add(String.valueOf(n));
            }
        }
        Matcher one = ONE_REF.matcher(list);
        while (one.find()) {
            out.add(one.group());
        }
        return out;
    }

    /** "CUSTOMER DUE DILIGENCE (“CDD”) / (II) Verification ..." -> the numbered section's title alone. */
    private static String topTitle(String section) {
        int slash = section.indexOf(" / ");
        return slash < 0 ? section : section.substring(0, slash);
    }

    /** A document's section titles by section number ("6", "11A", "A") and full sections by paragraph ("6.14"). */
    private static final class Headings {

        private final String shortName;
        private final Map<String, String> sections = new HashMap<>();
        private final Map<String, String> paragraphs = new HashMap<>();

        Headings(String shortName, List<RegulationChunk> chunks) {
            this.shortName = shortName;
            for (RegulationChunk c : chunks) {
                String title = topTitle(c.section());
                int dot = c.ref().indexOf('.');
                if (dot > 0) {
                    sections.putIfAbsent(c.ref().substring(0, dot), title);
                }
                paragraphs.putIfAbsent(c.ref(), c.section());
            }
        }

        String titleOf(String ref) {
            String bare = SUFFIX.matcher(ref).replaceAll("");
            if (bare.contains(".")) {
                String paragraph = paragraphs.get(ref);
                if (paragraph == null) {
                    paragraph = paragraphs.get(bare);
                }
                return paragraph != null ? paragraph : sections.get(bare.substring(0, bare.indexOf('.')));
            }
            // "paragraph 11A" names a lettered section; "paragraph 6(b)" the section 6.
            String section = sections.get(ref.replaceAll("\\([a-z]+\\)", ""));
            return section != null ? section : sections.get(bare);
        }
    }
}
