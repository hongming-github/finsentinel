package com.finsentinel.platform.investigation;

import com.finsentinel.common.AlertRule;
import org.springframework.ai.chat.client.AdvisorParams;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Single-agent investigator: a ChatClient call with tools and a schema-constrained answer, then validation.
 * A rejected draft gets up to {@code report.max-repairs} more calls with the problems spelled out; if it is
 * still invalid the investigation fails and the queue retries or marks it FAILED for a human.
 */
@Component
class LlmCaseInvestigator implements CaseInvestigator {

    private final ChatClient chatClient;
    private final InvestigationToolsFactory toolsFactory;
    private final JsonMapper json;
    private final InvestigationProperties.Report rules;
    private final String systemPrompt;
    private final Map<AlertRule, String> playbooks;

    LlmCaseInvestigator(ChatClient.Builder chatClientBuilder, InvestigationToolsFactory toolsFactory, JsonMapper json,
                        InvestigationProperties props,
                        @Value("classpath:prompts/investigator-system.md") Resource systemPrompt) {
        this.chatClient = chatClientBuilder.build();
        this.toolsFactory = toolsFactory;
        this.json = json;
        this.rules = props.report();
        this.systemPrompt = read(systemPrompt);
        this.playbooks = loadPlaybooks();
    }

    /**
     * One playbook per rule, sent only for alerts of that rule. With all guidance in one system prompt, advice for
     * one rule leaked into others (transfer-scam guidance talked the model out of an impossible-travel card fraud).
     * Fails at startup if a rule has no playbook.
     */
    private static Map<AlertRule, String> loadPlaybooks() {
        Map<AlertRule, String> out = new EnumMap<>(AlertRule.class);
        for (AlertRule rule : AlertRule.values()) {
            out.put(rule, read(new ClassPathResource("prompts/playbooks/" + rule.name() + ".md")));
        }
        return out;
    }

    @Override
    public InvestigationResult investigate(CaseContext caseContext) {
        long start = System.nanoTime();
        InvestigationTools tools = toolsFactory.forCase(caseContext);
        String brief = brief(caseContext);

        Draft draft = ask(tools, brief);
        boolean involvesTransfer = tools.involvesTransfer();
        List<String> problems = CaseReportValidator.problems(draft.report(), tools.seenTxnIds(), tools.seenChunkIds(),
                involvesTransfer, rules);
        List<String> repaired = new ArrayList<>();
        for (int repair = 0; !problems.isEmpty() && repair < rules.maxRepairs(); repair++) {
            repaired.addAll(problems);
            draft = draft.then(ask(tools, repairPrompt(brief, draft.report(), problems)));
            problems = CaseReportValidator.problems(draft.report(), tools.seenTxnIds(), tools.seenChunkIds(),
                    involvesTransfer, rules);
        }
        if (!problems.isEmpty()) {
            throw new InvalidReportException(problems);
        }
        return new InvestigationResult(draft.report(), tools.calls(), List.copyOf(repaired), draft.model(),
                draft.inputTokens(), draft.outputTokens(), draft.cachedInputTokens(), draft.calls(),
                (System.nanoTime() - start) / 1_000_000);
    }

    /**
     * The case's alerts, the playbook of each rule that fired (and only those), and the analyst's note when the case
     * was sent back for another look.
     */
    private String brief(CaseContext caseContext) {
        StringBuilder brief = new StringBuilder("Investigate this case. Its alerts:\n")
                .append(json.writerWithDefaultPrettyPrinter().writeValueAsString(caseContext.alerts()));
        for (AlertRule rule : caseContext.rules()) {
            brief.append("\n\nPlaybook for ").append(rule).append(":\n").append(playbooks.get(rule));
        }
        if (caseContext.analystNote() != null) {
            brief.append("\n\nAn analyst reviewed an earlier report and sent the case back with this note. Address it "
                    + "explicitly in your findings:\n").append(caseContext.analystNote());
        }
        return brief.toString();
    }

    private Draft ask(InvestigationTools tools, String userMessage) {
        ResponseEntity<ChatResponse, CaseReport> response = chatClient.prompt()
                .system(systemPrompt)
                .user(userMessage)
                .tools(tools)
                .advisors(AdvisorParams.ENABLE_NATIVE_STRUCTURED_OUTPUT)
                .call()
                .responseEntity(CaseReport.class);
        if (response.entity() == null) {
            // Refusal or truncated output: nothing to repair, let the queue retry.
            throw new IllegalStateException("Model returned no parseable report");
        }
        ChatResponse chat = response.response();
        Usage usage = chat == null ? null : chat.getMetadata().getUsage();
        return new Draft(response.entity(),
                chat == null ? "unknown" : chat.getMetadata().getModel(),
                usage == null ? null : usage.getPromptTokens(),
                usage == null ? null : usage.getCompletionTokens(),
                usage == null || usage.getCacheReadInputTokens() == null ? null : Math.toIntExact(usage.getCacheReadInputTokens()),
                1);
    }

    private String repairPrompt(String brief, CaseReport rejected, List<String> problems) {
        return """
                %s

                Your previous report was rejected for these reasons:
                - %s

                Previous report:
                %s

                Call tools again if you need evidence, then return a corrected report.
                """.formatted(brief, String.join("\n- ", problems), json.writeValueAsString(rejected));
    }

    private static String read(Resource resource) {
        try {
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Missing prompt resource " + resource.getDescription(), e);
        }
    }

    /** The latest report, with token usage summed over every call so far. */
    private record Draft(CaseReport report, String model, Integer inputTokens, Integer outputTokens,
                         Integer cachedInputTokens, int calls) {

        Draft then(Draft next) {
            return new Draft(next.report, next.model, sum(inputTokens, next.inputTokens),
                    sum(outputTokens, next.outputTokens), sum(cachedInputTokens, next.cachedInputTokens), calls + next.calls);
        }

        // Not a nested ternary: "a == null ? b : ... a + b" types as int and unboxes a null b.
        private static Integer sum(Integer a, Integer b) {
            if (a == null) {
                return b;
            }
            if (b == null) {
                return a;
            }
            return a + b;
        }
    }
}
