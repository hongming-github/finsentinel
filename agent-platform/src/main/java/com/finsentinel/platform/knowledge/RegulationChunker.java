package com.finsentinel.platform.knowledge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits MAS-style documents into one chunk per numbered paragraph ("4.7 A responsible FI should ...").
 * Pure text in, chunks out: no PDF or Spring dependency, so it is unit-tested with plain strings.
 *
 * <p>Rules, all learned from the actual documents:
 * <ul>
 *   <li>Numbered sub-section headings (TRM's "3.1 Role of the Board ..."), Notice 626's roman-numbered ones
 *       ("(IV) Identification and Verification of Identity of Beneficial Owner") and wrapped unnumbered headings
 *       become the {@code section} context of the paragraphs they introduce instead of chunks of their own.</li>
 *   <li>Section headings ("4  Duties of the responsible FI") are accepted only when their number is the next
 *       expected one, which keeps footnotes ("13 The cooling off period ...") from posing as headings.</li>
 *   <li>Page numbers, "Internal" footers, "[Amended on ...]" notes, table-of-contents lines and running headers
 *       are dropped.</li>
 *   <li>A definitions paragraph is split into one chunk per defined term.</li>
 *   <li>Paragraphs longer than {@code maxChars} are split at line boundaries into parts.</li>
 * </ul>
 */
final class RegulationChunker {

    private static final Pattern PARAGRAPH = Pattern.compile("^\\s*(\\d{1,2}(?:\\.\\d{1,2}){1,3})\\s+(\\S.*)$");
    private static final Pattern SECTION = Pattern.compile("^\\s*(\\d{1,2})\\s+([A-Z][^.;:]{2,100}?)\\s*$");
    private static final Pattern PAGE_NUMBER = Pattern.compile("^\\s*(?:page\\s+)?\\d{1,3}(?:\\s+of\\s+\\d{1,3})?\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern EDIT_NOTE = Pattern.compile("^\\s*\\[(?:Amended|Deleted|Inserted|Added)[^\\]]*\\]\\s*$");
    private static final Pattern TOC_LEADER = Pattern.compile("\\.{5,}|…{3,}");
    /** Notice 626 numbers its sub-headings "(I) Identification of Customer", "(II) ...". */
    private static final Pattern ROMAN_HEADING = Pattern.compile("^\\(([IVX]{1,5})\\)\\s+([A-Z].*)$");
    /**
     * A defined term at the start of a line, in each form the MAS documents use: {@code “term” means}, {@code “term”
     * include(s)}, {@code “term”, in relation to X, means}, {@code “term” or “other term” means}, {@code “term” –}.
     * A term followed by anything else ({@code “bank” or “finance company” as the case may be}) continues the
     * definition before it.
     */
    private static final Pattern DEFINITION = Pattern.compile("^\\s*[“\"]([^”\"]{1,80})[”\"]\\s*,?\\s*"
            + "(?:(?:or\\s+[“\"][^”\"]{1,80}[”\"]\\s+)?(?:means|has|includes?|refers|shall)\\b|in relation to\\b|[–—]).*");

    private final int maxChars;

    RegulationChunker(int maxChars) {
        this.maxChars = maxChars;
    }

    List<RegulationChunk> chunk(String docId, List<String> pages) {
        List<Paragraph> paragraphs = collect(pages);
        promoteHeadings(paragraphs);
        return toChunks(docId, paragraphs);
    }

    /** First pass: every numbered paragraph with its raw lines, section and start page. */
    private List<Paragraph> collect(List<String> pages) {
        Set<String> runningHeaders = runningHeaders(pages);
        List<Paragraph> paragraphs = new ArrayList<>();
        Paragraph current = null;
        int section = 0;
        String sectionTitle = "";
        for (int p = 0; p < pages.size(); p++) {
            for (String raw : pages.get(p).split("\\R")) {
                String line = raw.strip();
                if (isNoise(line, runningHeaders)) {
                    continue;
                }
                Matcher sectionMatch = SECTION.matcher(line);
                if (sectionMatch.matches() && Integer.parseInt(sectionMatch.group(1)) == section + 1) {
                    section++;
                    sectionTitle = sectionMatch.group(2).strip();
                    current = null;
                    continue;
                }
                Matcher paragraphMatch = PARAGRAPH.matcher(line);
                if (paragraphMatch.matches() && belongsToSection(paragraphMatch.group(1), section)
                        && startsParagraph(paragraphMatch, current)) {
                    current = new Paragraph(paragraphMatch.group(1), sectionTitle, p + 1);
                    current.lines.add(paragraphMatch.group(2));
                    paragraphs.add(current);
                    continue;
                }
                if (current != null) {
                    current.lines.add(line);
                }
            }
        }
        return paragraphs;
    }

    /**
     * Second pass: find headings that the first pass read as text, and hand them to the paragraphs they introduce.
     * <ul>
     *   <li>A numbered heading ("3.1 Role of the Board of Directors" followed by 3.1.1, 3.1.2 ...) is removed
     *       and becomes the sub-heading of its children.</li>
     *   <li>Unpunctuated lines at the end of a paragraph that ends a sentence before them (a heading for the
     *       next paragraph, possibly wrapped over lines) move to the next paragraph.</li>
     * </ul>
     * Sub-headings carry forward within a section until the next one.
     */
    private static void promoteHeadings(List<Paragraph> paragraphs) {
        for (int i = 0; i < paragraphs.size() - 1; i++) {
            Paragraph paragraph = paragraphs.get(i);
            Paragraph next = paragraphs.get(i + 1);
            if (paragraph.lines.size() == 1 && isHeadingText(paragraph.lines.getFirst())
                    && next.ref.startsWith(paragraph.ref + ".")) {
                next.subHeading = paragraph.lines.getFirst();
                paragraph.heading = true;
                continue;
            }
            List<String> trailing = trailingHeading(paragraph.lines);
            if (!trailing.isEmpty() && next.sectionTitle.equals(paragraph.sectionTitle)) {
                paragraph.lines.subList(paragraph.lines.size() - trailing.size(), paragraph.lines.size()).clear();
                next.subHeading = joinHeading(trailing);
            }
        }
        paragraphs.removeIf(p -> p.heading);
        String inherited = null;
        String section = null;
        for (Paragraph paragraph : paragraphs) {
            if (!paragraph.sectionTitle.equals(section)) {
                section = paragraph.sectionTitle;
                inherited = null;
            }
            if (paragraph.subHeading != null) {
                inherited = paragraph.subHeading;
            }
            paragraph.section = inherited == null ? section : section + " / " + inherited;
        }
    }

    /** Up to three unpunctuated, capitalised lines after a line that ends a sentence. */
    private static List<String> trailingHeading(List<String> lines) {
        int end = lines.size();
        int start = end;
        while (start > 1 && end - start < 3 && !endsSentence(lines.get(start - 1))) {
            start--;
        }
        if (start == end || start < 1 || !endsSentence(lines.get(start - 1))) {
            return List.of();
        }
        List<String> candidate = lines.subList(start, end);
        String joined = String.join(" ", candidate);
        boolean headingLike = (ROMAN_HEADING.matcher(candidate.getFirst()).matches()
                || Character.isUpperCase(candidate.getFirst().charAt(0)))
                && joined.length() <= 200
                && !DEFINITION.matcher(candidate.getFirst()).matches();
        return headingLike ? List.copyOf(candidate) : List.of();
    }

    /**
     * A wrapped heading's lines joined with spaces; a roman-numbered heading under an unnumbered one ("Responsibility
     * of the Ordering Institution" then "(I) Identification and Recording of Information") is a level of its own.
     */
    private static String joinHeading(List<String> lines) {
        StringBuilder out = new StringBuilder();
        for (String line : lines) {
            Matcher roman = ROMAN_HEADING.matcher(line);
            String text = roman.matches() ? "(" + roman.group(1) + ") " + roman.group(2) : line;
            out.append(out.isEmpty() ? "" : roman.matches() ? " / " : " ").append(text);
        }
        return out.toString();
    }

    private static boolean endsSentence(String line) {
        return line.matches(".*[.;:]\\s*$") || line.matches(".*[.;:]\\d{1,2}$");
    }

    private static boolean isHeadingText(String line) {
        return line.length() <= 90 && Character.isUpperCase(line.charAt(0)) && !endsSentence(line) && !line.endsWith(",");
    }

    private List<RegulationChunk> toChunks(String docId, List<Paragraph> paragraphs) {
        List<RegulationChunk> chunks = new ArrayList<>();
        Map<String, Integer> seenIds = new HashMap<>();
        for (Paragraph paragraph : paragraphs) {
            for (Piece piece : splitDefinitions(paragraph)) {
                List<String> parts = splitLong(piece.text());
                for (int i = 0; i < parts.size(); i++) {
                    String id = docId + ":" + paragraph.ref + (piece.label() == null ? "" : ":" + piece.label())
                            + (parts.size() > 1 ? "-p" + (i + 1) : "");
                    int duplicate = seenIds.merge(id, 1, Integer::sum);
                    if (duplicate > 1) {
                        id = id + "#" + duplicate;
                    }
                    chunks.add(new RegulationChunk(id, docId, paragraph.ref, paragraph.section, paragraph.page, parts.get(i)));
                }
            }
        }
        return chunks;
    }

    /** A definitions paragraph ("2.1 For the purposes of these Guidelines:") becomes one piece per term. */
    private static List<Piece> splitDefinitions(Paragraph paragraph) {
        List<Piece> pieces = new ArrayList<>();
        StringBuilder intro = new StringBuilder();
        String label = null;
        StringBuilder body = null;
        for (String line : paragraph.lines) {
            Matcher definition = DEFINITION.matcher(line);
            if (definition.matches()) {
                if (body != null) {
                    pieces.add(new Piece(label, body.toString().strip()));
                }
                label = slug(definition.group(1));
                body = new StringBuilder(line);
            } else if (body != null) {
                body.append('\n').append(line);
            } else {
                intro.append(intro.isEmpty() ? "" : "\n").append(line);
            }
        }
        if (body == null) {
            return List.of(new Piece(null, intro.toString().strip()));
        }
        pieces.add(new Piece(label, body.toString().strip()));
        if (!intro.toString().isBlank() && pieces.size() < 2) {
            pieces.addFirst(new Piece(null, intro.toString().strip()));
        }
        return pieces;
    }

    private List<String> splitLong(String text) {
        if (text.length() <= maxChars) {
            return List.of(text);
        }
        List<String> parts = new ArrayList<>();
        StringBuilder part = new StringBuilder();
        for (String line : text.split("\n")) {
            if (part.length() + line.length() + 1 > maxChars && !part.isEmpty()) {
                parts.add(part.toString().strip());
                part.setLength(0);
            }
            part.append(line).append('\n');
        }
        if (!part.toString().isBlank()) {
            parts.add(part.toString().strip());
        }
        return parts;
    }

    /**
     * A cross-reference that wraps onto a new line ("... under paragraph 6.2 or" / "6.4 of these Guidelines, the
     * account holder ...") looks like a paragraph start. Real paragraphs number upwards and open with a capital,
     * a quote or a bracket, never mid-sentence.
     */
    private static boolean startsParagraph(Matcher paragraph, Paragraph current) {
        boolean ascending = current == null || compareRefs(paragraph.group(1), current.ref) > 0;
        return ascending && !Character.isLowerCase(paragraph.group(2).charAt(0));
    }

    static int compareRefs(String a, String b) {
        String[] x = a.split("\\.");
        String[] y = b.split("\\.");
        for (int i = 0; i < Math.min(x.length, y.length); i++) {
            int c = Integer.compare(Integer.parseInt(x[i]), Integer.parseInt(y[i]));
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(x.length, y.length);
    }

    private static boolean belongsToSection(String ref, int section) {
        int major = Integer.parseInt(ref.substring(0, ref.indexOf('.')));
        return section > 0 && major == section;
    }

    private static boolean isNoise(String line, Set<String> runningHeaders) {
        return line.isEmpty()
                || PAGE_NUMBER.matcher(line).matches()
                || line.equalsIgnoreCase("internal")
                || EDIT_NOTE.matcher(line).matches()
                || TOC_LEADER.matcher(line).find()
                || runningHeaders.contains(withoutPageNumber(line));
    }

    /** Lines repeated on most pages: document titles in headers, confidentiality footers. */
    private static Set<String> runningHeaders(List<String> pages) {
        Set<String> headers = new HashSet<>();
        if (pages.size() < 4) {
            return headers;
        }
        Map<String, Integer> counts = new HashMap<>();
        for (String page : pages) {
            Set<String> onPage = new HashSet<>();
            for (String line : page.split("\\R")) {
                String l = withoutPageNumber(line.strip());
                if (l.length() > 3 && !PARAGRAPH.matcher(l).matches()) {
                    onPage.add(l);
                }
            }
            onPage.forEach(l -> counts.merge(l, 1, Integer::sum));
        }
        counts.forEach((line, n) -> {
            if (n >= pages.size() * 0.4) {
                headers.add(line);
            }
        });
        return headers;
    }

    /** "Monetary Authority of Singapore  7" and "... 8" are the same running footer. */
    private static String withoutPageNumber(String line) {
        return line.replaceFirst("\\s+\\d{1,3}$", "");
    }

    static String slug(String term) {
        return term.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }

    private static final class Paragraph {
        final String ref;
        final String sectionTitle;
        final int page;
        final List<String> lines = new ArrayList<>();
        String subHeading;
        String section;
        boolean heading;

        Paragraph(String ref, String sectionTitle, int page) {
            this.ref = ref;
            this.sectionTitle = sectionTitle;
            this.page = page;
        }
    }

    private record Piece(String label, String text) {
    }
}
