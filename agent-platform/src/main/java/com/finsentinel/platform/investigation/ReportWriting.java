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

/** Prompt pieces shared by the agents: the system prompt with its playbooks, the case data, revision and repair notes. */
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
     * The agent's system message: its role prompt, the playbook of each rule that fired (and only those), and what the
     * bank has not connected. Everything here is shared by many cases and nothing is case data, so the whole message
     * is a reusable cache prefix. It must be its own message, and long: from GPT-5.6 on, the provider reuses a prefix
     * only up to a message boundary (the end of the system block, the end of an earlier message) and only from 1,024
     * tokens on. With the playbook at the head of the user message the system block's prefix was about 1,000 tokens
     * and nothing was read back (ADR 0023: 0 of 2,600 prompt tokens cached on a second case; 1,489 with the playbook
     * in the system message, a 1,561-token prefix).
     */
    static String systemPrompt(String rolePrompt, CaseContext caseContext, Map<AlertRule, String> playbooks) {
        StringBuilder system = new StringBuilder(rolePrompt);
        for (AlertRule rule : caseContext.rules()) {
            system.append("\n\nPlaybook for ").append(rule).append(":\n").append(playbooks.get(rule));
        }
        if (!caseContext.unconnectedSystems().isEmpty()) {
            // Only for banks missing a system, so every other bank's prompt (and its prompt-cache prefix) is unchanged.
            system.append("\n\nThis bank has not connected ").append(caseContext.unconnectedSystems())
                    .append(": their checks are not available. Say in the summary which checks could not be done.");
        }
        return system.toString();
    }

    /** The case itself, for the user message: its alerts, and the analyst's note when it was sent back. */
    static String caseData(CaseContext caseContext, JsonMapper json) {
        StringBuilder data = new StringBuilder("\n\nIts alerts:\n")
                .append(json.writerWithDefaultPrettyPrinter().writeValueAsString(caseContext.alerts()));
        if (caseContext.analystNote() != null) {
            data.append("\n\nAn analyst reviewed an earlier report and sent the case back with this note. Address it "
                    + "explicitly in your findings:\n").append(caseContext.analystNote());
        }
        return data.toString();
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
