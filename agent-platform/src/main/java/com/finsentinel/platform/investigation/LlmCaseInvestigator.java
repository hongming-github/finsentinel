package com.finsentinel.platform.investigation;

import com.finsentinel.platform.alert.StoredAlert;
import org.springframework.ai.chat.client.AdvisorParams;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

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

    LlmCaseInvestigator(ChatClient.Builder chatClientBuilder, InvestigationToolsFactory toolsFactory, JsonMapper json,
                        InvestigationProperties props,
                        @Value("classpath:prompts/investigator-system.md") Resource systemPrompt) {
        this.chatClient = chatClientBuilder.build();
        this.toolsFactory = toolsFactory;
        this.json = json;
        this.rules = props.report();
        this.systemPrompt = read(systemPrompt);
    }

    @Override
    public InvestigationResult investigate(StoredAlert alert) {
        long start = System.nanoTime();
        InvestigationTools tools = toolsFactory.forCase(alert);
        String alertJson = json.writerWithDefaultPrettyPrinter().writeValueAsString(alert.alert());

        Draft draft = ask(tools, "Investigate this alert:\n" + alertJson);
        List<String> problems = CaseReportValidator.problems(draft.report(), tools.seenTxnIds(), rules);
        List<String> repaired = new ArrayList<>();
        for (int repair = 0; !problems.isEmpty() && repair < rules.maxRepairs(); repair++) {
            repaired.addAll(problems);
            draft = draft.then(ask(tools, repairPrompt(alertJson, draft.report(), problems)));
            problems = CaseReportValidator.problems(draft.report(), tools.seenTxnIds(), rules);
        }
        if (!problems.isEmpty()) {
            throw new InvalidReportException(problems);
        }
        return new InvestigationResult(draft.report(), tools.calls(), List.copyOf(repaired), draft.model(),
                draft.inputTokens(), draft.outputTokens(), draft.cachedInputTokens(), draft.calls(),
                (System.nanoTime() - start) / 1_000_000);
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

    private String repairPrompt(String alertJson, CaseReport rejected, List<String> problems) {
        return """
                Investigate this alert:
                %s

                Your previous report was rejected for these reasons:
                - %s

                Previous report:
                %s

                Call tools again if you need evidence, then return a corrected report.
                """.formatted(alertJson, String.join("\n- ", problems), json.writeValueAsString(rejected));
    }

    private static String read(Resource resource) {
        try {
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
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
