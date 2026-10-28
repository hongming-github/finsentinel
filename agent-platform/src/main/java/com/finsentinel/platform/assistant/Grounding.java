package com.finsentinel.platform.assistant;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks that what the model wrote rests on what it was given (ADR 0028, as reports are checked in ADR 0006): every
 * cited transaction is in this turn's fact sheet, every cited passage was retrieved or is cited by the report, and the
 * text names no transaction or passage id from anywhere else. The last check is what keeps another case out of an
 * answer even if the model were told about it: transaction and case ids are UUIDs, passage ids {@code doc:section}
 * with an optional {@code :name}.
 */
final class Grounding {

    private static final Pattern UUID_ID = Pattern.compile(
            "\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b");
    /**
     * Some passages carry a name after the section number ({@code srf-2024:2.1:high-risk-activities}), an inserted
     * section a letter ({@code notice-626-2025:11A.3}), a part of a long paragraph a suffix ({@code 6.14-p2}).
     */
    private static final Pattern CHUNK_ID = Pattern.compile(
            "\\b[a-z][a-z0-9]*(?:-[a-z0-9]+)*:\\d+[A-Z]?(?:\\.\\d+)*(?::[a-z0-9]+(?:-[a-z0-9]+)*)?(?:-p\\d+)?\\b");

    private Grounding() {
    }

    /**
     * @param txnIds     transaction ids of this turn's fact sheet
     * @param chunkIds   passages retrieved this turn and passages the report cites
     * @param mentioned  text the analyst wrote or was already told (the question, earlier turns): an id that came from
     *                   there may be repeated, e.g. to say it is not part of this case
     */
    static List<String> problems(AssistantModel.Answer answer, String caseId, Set<String> txnIds, Set<String> chunkIds,
                                 String mentioned) {
        List<String> problems = new ArrayList<>();
        if (answer.answer() == null || answer.answer().isBlank()) {
            problems.add("the answer is empty");
            return problems;
        }
        for (String id : nullSafe(answer.citedTxnIds())) {
            if (!txnIds.contains(id)) {
                problems.add("citedTxnIds has " + id + ", which is not a transaction in the fact sheet");
            }
        }
        for (String id : nullSafe(answer.citedChunkIds())) {
            if (!chunkIds.contains(id)) {
                problems.add("citedChunkIds has " + id + ", which is not a passage you were given");
            }
        }
        Set<String> known = new LinkedHashSet<>(txnIds);
        known.add(caseId);
        known.addAll(ids(UUID_ID, mentioned));
        for (String id : ids(UUID_ID, answer.answer())) {
            if (!known.contains(id)) {
                problems.add("the answer names " + id + ", which is not this case or one of its transactions");
            }
        }
        Set<String> knownChunks = new LinkedHashSet<>(chunkIds);
        knownChunks.addAll(ids(CHUNK_ID, mentioned));
        for (String id : ids(CHUNK_ID, answer.answer())) {
            if (!knownChunks.contains(id)) {
                problems.add("the answer names passage " + id + ", which you were not given");
            }
        }
        return problems;
    }

    /** A summary may only repeat ids that the turns it folds (or the summary before it) contained. */
    static List<String> summaryProblems(String summary, String folded, int maxChars) {
        List<String> problems = new ArrayList<>();
        if (summary == null || summary.isBlank()) {
            problems.add("the summary is empty");
            return problems;
        }
        if (summary.length() > maxChars) {
            problems.add("the summary is longer than " + maxChars + " characters");
        }
        Set<String> known = ids(UUID_ID, folded);
        known.addAll(ids(CHUNK_ID, folded));
        Set<String> named = ids(UUID_ID, summary);
        named.addAll(ids(CHUNK_ID, summary));
        named.stream().filter(id -> !known.contains(id))
                .forEach(id -> problems.add("the summary names " + id + ", which the folded turns do not"));
        return problems;
    }

    private static Set<String> ids(Pattern pattern, String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null) {
            return out;
        }
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            out.add(pattern == UUID_ID ? m.group().toLowerCase(Locale.ROOT) : m.group());
        }
        return out;
    }

    private static List<String> nullSafe(List<String> ids) {
        return ids == null ? List.of() : ids;
    }
}
