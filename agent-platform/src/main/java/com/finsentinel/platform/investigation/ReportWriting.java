package com.finsentinel.platform.investigation;

import com.finsentinel.common.AlertRule;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Prompt pieces shared by the agents: the case brief, playbooks, revision and repair notes. */
final class ReportWriting {

    private ReportWriting() {
    }

    /**
     * One playbook per rule, sent only for alerts of that rule. With all guidance in one system prompt, advice for
     * one rule leaked into others (transfer-scam guidance talked the model out of an impossible-travel card fraud).
     * Fails at startup if a rule has no playbook.
     */
    static Map<AlertRule, String> loadPlaybooks() {
        Map<AlertRule, String> out = new EnumMap<>(AlertRule.class);
        for (AlertRule rule : AlertRule.values()) {
            out.put(rule, read(new ClassPathResource("prompts/playbooks/" + rule.name() + ".md")));
        }
        return out;
    }

    /**
     * The playbook of each rule that fired (and only those), the case's alerts, and the analyst's note when the case
     * was sent back for another look. Text shared by cases comes first and case data last: the provider caches the
     * longest prompt prefix it has seen, so with the playbooks ahead of the alerts the next case of the same rules
     * reads most of its prompt from the cache; with the alerts first, no case did (measured, ADR 0013).
     */
    static String caseBrief(CaseContext caseContext, Map<AlertRule, String> playbooks, JsonMapper json) {
        StringBuilder brief = new StringBuilder();
        for (AlertRule rule : caseContext.rules()) {
            brief.append("\n\nPlaybook for ").append(rule).append(":\n").append(playbooks.get(rule));
        }
        brief.append("\n\nIts alerts:\n")
                .append(json.writerWithDefaultPrettyPrinter().writeValueAsString(caseContext.alerts()));
        if (caseContext.analystNote() != null) {
            brief.append("\n\nAn analyst reviewed an earlier report and sent the case back with this note. Address it "
                    + "explicitly in your findings:\n").append(caseContext.analystNote());
        }
        return brief.toString();
    }

    /** Empty on a first pass; otherwise the reviewer's objections to the previous report. */
    static String revisionNote(Revision revision, JsonMapper json) {
        if (revision == null) {
            return "";
        }
        return """


                A reviewer sent your previous report back. Fix every point, keeping what was right:
                - %s

                Previous report:
                %s""".formatted(String.join("\n- ", revision.issues()), json.writeValueAsString(revision.previousReport()));
    }

    static final String FIX_WITH_TOOLS = "Call tools again if you need evidence, then return a corrected report.";
    static final String FIX_WITHOUT_TOOLS = "You have no tools: use only transaction ids and passages that appear in "
            + "the specialist reports, and drop any claim they do not support. Return a corrected report.";

    static String repairPrompt(String brief, CaseReport rejected, List<String> problems, JsonMapper json,
                               String howToFix) {
        return """
                %s

                Your previous report was rejected for these reasons:
                - %s

                Previous report:
                %s

                %s
                """.formatted(brief, String.join("\n- ", problems), json.writeValueAsString(rejected), howToFix);
    }

    static String read(Resource resource) {
        try {
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Missing prompt resource " + resource.getDescription(), e);
        }
    }
}
