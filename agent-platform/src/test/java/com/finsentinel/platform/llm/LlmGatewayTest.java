package com.finsentinel.platform.llm;

import com.finsentinel.platform.investigation.ScriptedChatModel;
import com.openai.errors.OpenAIIoException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Provider fallback and circuit breaking, with scripted models: no network, no cost. */
class LlmGatewayTest {

    record Answer(String verdict) {
    }

    private final JsonMapper json = JsonMapper.builder().build();
    private final LlmProperties props = TestLlm.properties(Set.of());
    private final MutableClock clock = new MutableClock();
    private final ModelRef mini = ModelRef.parse("gpt-5.4-mini");

    @Test
    void failingProviderFallsBackAndTheCostIsTheFallbacksOwn() {
        DownModel openai = new DownModel();
        ScriptedChatModel groq = new ScriptedChatModel(json, new Answer("ok"));

        LlmGateway.Answer<Answer> answer = gateway(openai, groq).ask(mini, "s", "u", List.of(), Answer.class);

        assertThat(answer.value().verdict()).isEqualTo("ok");
        assertThat(answer.answeredBy()).isEqualTo(ModelRef.parse("groq:openai/gpt-oss-120b"));
        assertThat(groq.modelsAsked).containsExactly("openai/gpt-oss-120b");
        assertThat(answer.usage().costByModel()).containsOnlyKeys("groq:openai/gpt-oss-120b");
    }

    @Test
    void circuitOpensAfterTheThresholdThenOneTrialCallDecides() {
        DownModel openai = new DownModel();
        ScriptedChatModel groq = new ScriptedChatModel(json, new Answer("1"), new Answer("2"), new Answer("3"),
                new Answer("4"));
        LlmGateway gateway = gateway(openai, groq);

        gateway.ask(mini, "s", "u", List.of(), Answer.class);
        gateway.ask(mini, "s", "u", List.of(), Answer.class); // second failure: threshold 2, circuit opens
        gateway.ask(mini, "s", "u", List.of(), Answer.class);
        assertThat(openai.calls).as("open circuit: OpenAI not even tried").hasValue(2);

        clock.advance(Duration.ofSeconds(61));
        gateway.ask(mini, "s", "u", List.of(), Answer.class);
        assertThat(openai.calls).as("after open-for, one trial call").hasValue(3);
    }

    @Test
    void aBadAnswerFromAHealthyProviderIsNotAProviderFailure() {
        ScriptedChatModel openai = new ScriptedChatModel(json, "not the expected record at all");
        ScriptedChatModel groq = new ScriptedChatModel(json, new Answer("never asked"));

        assertThatThrownBy(() -> gateway(openai, groq).ask(mini, "s", "u", List.of(), Answer.class));
        assertThat(groq.modelsAsked).as("validation and repair handle bad answers, not another provider").isEmpty();
    }

    @Test
    void aProviderThatCannotEnforceASchemaWithToolsInvestigatesThenStructuresInASecondCall() {
        ScriptedChatModel groq = new ScriptedChatModel(json, "notes: verdict ok, txn t-1", new Answer("with tools"),
                new Answer("without tools"));
        ToolCallback tool = FunctionToolCallback.builder("lookup", (String in) -> "x").inputType(String.class).build();
        LlmGateway gateway = gateway(new DownModel(), groq);
        ModelRef oss = ModelRef.parse("groq:openai/gpt-oss-120b");

        LlmGateway.Answer<Answer> withTools = gateway.ask(oss, "s", "u", List.of(tool), Answer.class);
        LlmGateway.Answer<Answer> withoutTools = gateway.ask(oss, "s", "u", List.of(), Answer.class);

        assertThat(withTools.value().verdict()).isEqualTo("with tools");
        assertThat(withoutTools.value().verdict()).isEqualTo("without tools");
        // Tools without a schema, then the schema without tools; a call without tools needs one step.
        assertThat(groq.schemaEnforced).containsExactly(false, true, true);
        assertThat(groq.toolsOffered).containsExactly(Set.of("lookup"), Set.of(), Set.of());
        assertThat(groq.prompts.get(0)).endsWith(LlmGateway.NOTES_INSTRUCTION);
        assertThat(groq.prompts.get(1)).contains("notes: verdict ok, txn t-1").endsWith(LlmGateway.STRUCTURE_INSTRUCTION);
        assertThat(withTools.usage().calls()).isEqualTo(2);
    }

    @Test
    void openAiStillAnswersInOneCallWithTheSchemaEnforced() {
        ScriptedChatModel openai = new ScriptedChatModel(json, new Answer("one call"));
        ToolCallback tool = FunctionToolCallback.builder("lookup", (String in) -> "x").inputType(String.class).build();

        LlmGateway.Answer<Answer> answer = gateway(openai, new DownModel()).ask(mini, "s", "u", List.of(tool),
                Answer.class);

        assertThat(answer.value().verdict()).isEqualTo("one call");
        assertThat(openai.schemaEnforced).containsExactly(true);
        assertThat(openai.prompts).containsExactly("u");
    }

    @Test
    void whenEveryProviderFailsTheCallFails() {
        assertThatThrownBy(() -> gateway(new DownModel(), new DownModel()).ask(mini, "s", "u", List.of(), Answer.class))
                .hasMessageContaining("no LLM provider answered");
    }

    private LlmGateway gateway(ChatModel openai, ChatModel groq) {
        return TestLlm.gateway(openai, groq, props, clock);
    }

    /** A provider that is down: the OpenAI SDK reports an I/O failure. */
    static final class DownModel implements ChatModel {

        final AtomicInteger calls = new AtomicInteger();

        @Override
        public ChatResponse call(Prompt prompt) {
            calls.incrementAndGet();
            throw new OpenAIIoException("connection refused", new IOException("connection refused"));
        }

        @Override
        public ChatOptions getOptions() {
            return org.springframework.ai.openai.OpenAiChatOptions.builder().build();
        }
    }

    static final class MutableClock extends Clock {

        private Instant now = Instant.parse("2026-10-03T00:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}
