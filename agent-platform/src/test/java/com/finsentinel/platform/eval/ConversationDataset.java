package com.finsentinel.platform.eval;

import com.finsentinel.platform.investigation.Disposition;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

/**
 * The case assistant's scripted conversations (ADR 0028), loaded and checked before any run, like the golden cases:
 * a mistake in the data fails for free.
 */
record ConversationDataset(String about, List<Conversation> conversations) {

    static final String RESOURCE = "eval/conversation-cases.json";
    /** What the set must cover: the failure modes of a multi-turn assistant this evaluation exists for. */
    static final Set<String> REQUIRED_KINDS = Set.of("follow-up", "pronoun", "regulation-follow-up", "other-bank",
            "injection");
    static final String OTHER_CASE = "{otherCase}";

    ConversationDataset {
        conversations = conversations == null ? List.of() : List.copyOf(conversations);
    }

    /**
     * @param goldenCase whose data the case is built from (transactions, profile, alert)
     * @param report     stored as the case's validated analysis, so only the assistant is measured
     */
    record Conversation(String id, String kind, boolean critical, String description, String goldenCase,
                        Report report, List<Turn> turns) {
    }

    record Report(Disposition disposition, int riskScore, String summary, List<Finding> findings,
                  List<String> recommendedActions) {
    }

    /** @param txns golden-case transaction refs */
    record Finding(String statement, List<String> txns) {
    }

    record Turn(String ask, Expect expect) {
    }

    /**
     * Every field is optional.
     *
     * @param citesTxns          refs the answer must cite
     * @param standaloneMatches  pattern the condensed question must match
     * @param search             whether a regulation search must (true) or must not (false) run
     * @param retrievesAnyOf     chunk ids or id prefixes: at least one passage handed to the model must match one
     * @param citesChunkAnyOf    the same for the passages the answer cites
     * @param answerMatches      patterns the answer must each match
     * @param answerMustNotMatch patterns the answer must not match
     * @param flagged            whether the platform must flag the question as carrying instructions
     */
    record Expect(List<String> citesTxns, String standaloneMatches, Boolean search, List<String> retrievesAnyOf,
                  List<String> citesChunkAnyOf, List<String> answerMatches, List<String> answerMustNotMatch,
                  Boolean flagged) {

        Expect {
            citesTxns = citesTxns == null ? List.of() : List.copyOf(citesTxns);
            retrievesAnyOf = retrievesAnyOf == null ? List.of() : List.copyOf(retrievesAnyOf);
            citesChunkAnyOf = citesChunkAnyOf == null ? List.of() : List.copyOf(citesChunkAnyOf);
            answerMatches = answerMatches == null ? List.of() : List.copyOf(answerMatches);
            answerMustNotMatch = answerMustNotMatch == null ? List.of() : List.copyOf(answerMustNotMatch);
        }
    }

    static ConversationDataset load(JsonMapper json, GoldenDataset golden) {
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            ConversationDataset dataset = json.readValue(in, ConversationDataset.class);
            List<String> problems = dataset.problems(golden);
            if (!problems.isEmpty()) {
                throw new IllegalStateException(RESOURCE + " is invalid:\n- " + String.join("\n- ", problems));
            }
            return dataset;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** All, or those named in {@code ids} (comma-separated); unknown names are an error. */
    List<Conversation> select(String ids) {
        if (ids == null || ids.isBlank()) {
            return conversations;
        }
        Set<String> wanted = Arrays.stream(ids.split(",")).map(String::trim).collect(Collectors.toSet());
        List<Conversation> selected = conversations.stream().filter(c -> wanted.contains(c.id())).toList();
        if (selected.size() != wanted.size()) {
            throw new IllegalArgumentException("unknown conversation ids among " + wanted);
        }
        return selected;
    }

    List<String> problems(GoldenDataset golden) {
        List<String> problems = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        Set<String> kinds = new HashSet<>();
        for (Conversation c : conversations) {
            String at = "conversation " + c.id() + ": ";
            if (c.id() == null || !ids.add(c.id())) {
                problems.add(at + "missing or duplicate id");
            }
            kinds.add(c.kind());
            if (c.turns() == null || c.turns().size() < 2) {
                problems.add(at + "a conversation has at least two turns");
                continue;
            }
            List<GoldenCase> base = golden.cases().stream().filter(g -> g.id().equals(c.goldenCase())).toList();
            if (base.isEmpty()) {
                problems.add(at + "unknown golden case " + c.goldenCase());
                continue;
            }
            Set<String> refs = base.getFirst().setup().transactions().stream().map(GoldenCase.TxnSpec::ref)
                    .collect(Collectors.toSet());
            if (c.report() == null || c.report().disposition() == null || c.report().findings() == null) {
                problems.add(at + "a report with a disposition and findings is required");
            } else {
                c.report().findings().forEach(f -> f.txns().stream().filter(r -> !refs.contains(r))
                        .forEach(r -> problems.add(at + "report cites unknown ref " + r)));
            }
            boolean other = false;
            for (int i = 0; i < c.turns().size(); i++) {
                Turn t = c.turns().get(i);
                String turnAt = at + "turn " + (i + 1) + ": ";
                if (t.ask() == null || t.ask().isBlank() || t.expect() == null) {
                    problems.add(turnAt + "a question and an expectation are required");
                    continue;
                }
                other |= t.ask().contains(OTHER_CASE);
                t.expect().citesTxns().stream().filter(r -> !refs.contains(r))
                        .forEach(r -> problems.add(turnAt + "cites unknown ref " + r));
                List<String> patterns = new ArrayList<>(t.expect().answerMatches());
                patterns.addAll(t.expect().answerMustNotMatch());
                if (t.expect().standaloneMatches() != null) {
                    patterns.add(t.expect().standaloneMatches());
                }
                for (String p : patterns) {
                    try {
                        Pattern.compile(p);
                    } catch (PatternSyntaxException e) {
                        problems.add(turnAt + "bad pattern " + p);
                    }
                }
            }
            if (c.kind().equals("other-bank") && !other) {
                problems.add(at + "an other-bank conversation asks about " + OTHER_CASE);
            }
        }
        Set<String> missing = new HashSet<>(REQUIRED_KINDS);
        missing.removeAll(kinds);
        if (!missing.isEmpty()) {
            problems.add("no conversation of kinds " + missing);
        }
        return problems;
    }
}
