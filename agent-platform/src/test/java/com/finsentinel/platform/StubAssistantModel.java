package com.finsentinel.platform;

import com.finsentinel.platform.assistant.AssistantModel;
import com.finsentinel.platform.assistant.ConversationTurn;
import com.finsentinel.platform.investigation.CaseContext;
import com.finsentinel.platform.knowledge.RegulationHit;
import com.finsentinel.platform.llm.LlmUsage;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.annotation.Primary;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Replaces the case assistant's model in tests: the whole pipeline around it (budget, facts, folding, retrieval,
 * grounding, storage, audit) runs for real. Remembers what each call was given; behaviour is swappable per test.
 */
@TestComponent
@Primary
public class StubAssistantModel implements AssistantModel {

    /** What an answer call was given. */
    public record AnswerCall(CaseContext caseContext, String factSheet, History history, List<RegulationHit> passages,
                             String question, List<String> problems) {
    }

    public static final LlmUsage USAGE = new LlmUsage("stub-assistant", 100, 20, 40, 0, 1, 1, new BigDecimal("0.0001"),
            Map.of("stub-assistant", new BigDecimal("0.0001")));

    private volatile Function<String, Condensed> condenser = StubAssistantModel::standsAlone;
    private volatile Function<AnswerCall, Answer> answerer = StubAssistantModel::echo;
    private volatile Function<List<ConversationTurn>, Summary> summariser = StubAssistantModel::listing;
    private volatile AssistantModel delegate;
    public final List<AnswerCall> answers = new CopyOnWriteArrayList<>();
    public final List<History> condensed = new CopyOnWriteArrayList<>();
    public final List<List<ConversationTurn>> summarised = new CopyOnWriteArrayList<>();

    @Override
    public Reply<Condensed> condense(CaseContext caseContext, History history, String question) {
        condensed.add(history);
        if (delegate != null) {
            return delegate.condense(caseContext, history, question);
        }
        return new Reply<>(condenser.apply(question), USAGE);
    }

    @Override
    public Reply<Answer> answer(CaseContext caseContext, String factSheet, History history,
                                List<RegulationHit> passages, String question, List<String> problems) {
        AnswerCall call = new AnswerCall(caseContext, factSheet, history, passages, question, problems);
        answers.add(call);
        if (delegate != null) {
            return delegate.answer(caseContext, factSheet, history, passages, question, problems);
        }
        return new Reply<>(answerer.apply(call), USAGE);
    }

    @Override
    public Reply<Summary> summarise(CaseContext caseContext, String previous, List<ConversationTurn> folded,
                                    int maxChars) {
        summarised.add(folded);
        if (delegate != null) {
            return delegate.summarise(caseContext, previous, folded, maxChars);
        }
        return new Reply<>(summariser.apply(folded), USAGE);
    }

    public void condenseWith(Function<String, Condensed> condenser) {
        this.condenser = condenser;
    }

    public void answerWith(Function<AnswerCall, Answer> answerer) {
        this.answerer = answerer;
    }

    public void summariseWith(Function<List<ConversationTurn>, Summary> summariser) {
        this.summariser = summariser;
    }

    /** Hands every call to {@code model} (the real one, for a test against fake-llm); reset() ends it. */
    public void delegateTo(AssistantModel model) {
        this.delegate = model;
    }

    public void reset() {
        delegate = null;
        condenser = StubAssistantModel::standsAlone;
        answerer = StubAssistantModel::echo;
        summariser = StubAssistantModel::listing;
        answers.clear();
        condensed.clear();
        summarised.clear();
    }

    public static Condensed standsAlone(String question) {
        return new Condensed(question, false, "");
    }

    public static Answer echo(AnswerCall call) {
        return new Answer("Stub answer.", List.of(), List.of());
    }

    public static Summary listing(List<ConversationTurn> folded) {
        return new Summary("Turns " + folded.stream().map(t -> String.valueOf(t.seq()))
                .collect(Collectors.joining(", ")) + " were about the case.");
    }
}
