package com.finsentinel.platform.assistant;

import com.finsentinel.platform.audit.AuditEntry;
import com.finsentinel.platform.audit.AuditLog;
import com.finsentinel.platform.budget.RateLimiter;
import com.finsentinel.platform.investigation.UntrustedInstructions;
import com.finsentinel.platform.knowledge.KnowledgeSearch;
import com.finsentinel.platform.knowledge.RegulationHit;
import com.finsentinel.platform.llm.LlmUsage;
import com.finsentinel.platform.security.Caller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The case assistant (ADR 0028): an analyst asks about one case, turn after turn. Per turn: the budget, the question's
 * own checks, the case read afresh, older turns folded into the summary when the window is full, the question
 * condensed with the history, a regulation search with the condensed question when it needs one, the answer, its
 * grounding check (one repair), and only then the stored turn. The tenant and the analyst come from the token; the
 * case from the stored conversation, never from a request: a path that names another case finds nothing.
 */
@Service
class CaseAssistant {

    private static final Logger log = LoggerFactory.getLogger(CaseAssistant.class);

    static final String ANALYST_LIMIT_KEY = "assistant:analyst:";
    static final String TENANT_LIMIT_KEY = "assistant:tenant:";

    private final ConversationRepository conversations;
    private final CaseFacts facts;
    private final AssistantModel model;
    private final KnowledgeSearch knowledge;
    private final RateLimiter rateLimiter;
    private final AuditLog audit;
    private final AssistantProperties props;

    CaseAssistant(ConversationRepository conversations, CaseFacts facts, AssistantModel model,
                  KnowledgeSearch knowledge, RateLimiter rateLimiter, AuditLog audit, AssistantProperties props) {
        this.conversations = conversations;
        this.facts = facts;
        this.model = model;
        this.knowledge = knowledge;
        this.rateLimiter = rateLimiter;
        this.audit = audit;
        this.props = props;
    }

    /** A new conversation on a case of the caller's bank; empty when there is no such case. */
    Optional<Conversation> start(Caller caller, String caseId) {
        if (!isUuid(caseId) || facts.load(caller.tenant(), caseId).isEmpty()) {
            return Optional.empty();
        }
        Conversation created = conversations.create(caller.tenant(), caseId, caller.subject(), caller.username());
        audit.record(AuditEntry.forCurrentCaller("assistant start", caseId, "OK",
                Map.of("conversationId", created.conversationId())));
        return Optional.of(created);
    }

    List<Conversation> list(Caller caller, String caseId) {
        return isUuid(caseId) ? conversations.list(caller.tenant(), caller.subject(), caseId,
                props.maxConversationsListed()) : List.of();
    }

    /** The conversation with its turns, if it is the caller's and on this case. */
    Optional<View> read(Caller caller, String caseId, String conversationId) {
        return find(caller, caseId, conversationId)
                .map(c -> new View(c, conversations.turns(c.tenantId(), c.conversationId())));
    }

    record View(Conversation conversation, List<ConversationTurn> turns) {
    }

    /** What became of a question. */
    sealed interface Outcome {
    }

    record Answered(View view, ConversationTurn turn) implements Outcome {
    }

    /** No such conversation for this caller and case. */
    record NotFound() implements Outcome {
    }

    /** The conversation moved on since the caller read it (another tab, another instance): nothing was stored. */
    record Conflict(View current) implements Outcome {
    }

    /** Refused before any model call: a bad question, a full conversation, a used-up budget. */
    record Refused(Reason reason, String message) implements Outcome {
    }

    enum Reason { INVALID, CONVERSATION_FULL, OVER_BUDGET }

    /** The model's answer cited what it was not given, also after a repair: nothing was stored. */
    record Ungrounded(List<String> problems) implements Outcome {
    }

    /**
     * @param expectedTurns the turns the caller has seen (its {@code If-Match}); a new turn is stored only on top of them
     */
    Outcome ask(Caller caller, String caseId, String conversationId, int expectedTurns, String question) {
        long start = System.nanoTime();
        Optional<Conversation> found = find(caller, caseId, conversationId);
        if (found.isEmpty()) {
            return new NotFound();
        }
        Conversation conversation = found.get();
        if (question == null || question.isBlank()) {
            return new Refused(Reason.INVALID, "a question is required");
        }
        if (question.length() > props.maxQuestionChars()) {
            return new Refused(Reason.INVALID, "a question has at most " + props.maxQuestionChars() + " characters");
        }
        if (conversation.turns() != expectedTurns) {
            return new Conflict(current(conversation));
        }
        if (conversation.turns() >= props.maxTurnsPerConversation()) {
            return new Refused(Reason.CONVERSATION_FULL, "this conversation has reached "
                    + props.maxTurnsPerConversation() + " turns; start a new one");
        }
        if (!withinBudget(caller)) {
            audit.record(AuditEntry.forCurrentCaller("assistant turn", caseId, "OVER_BUDGET",
                    Map.of("conversationId", conversationId)));
            return new Refused(Reason.OVER_BUDGET, "the assistant's budget of questions per hour is used up; "
                    + "try again later");
        }
        // The case as it is now: a decision or a new analysis since the last turn is in this answer.
        Optional<CaseFacts.Facts> loaded = facts.load(caller.tenant(), conversation.caseId());
        if (loaded.isEmpty()) {
            return new NotFound();
        }
        CaseFacts.Facts caseFacts = loaded.get();
        List<ConversationTurn> turns = conversations.turns(caller.tenant(), conversationId);
        Folded history = history(caseFacts, conversation, turns);
        LlmUsage usage = history.usage();

        List<String> untrusted = UntrustedInstructions.findInAnalystQuestion(question);
        AssistantModel.Reply<AssistantModel.Condensed> condensed = model.condense(caseFacts.context(),
                history.history(), question);
        usage = usage.plus(condensed.usage());
        String standalone = blankToNull(condensed.value().standaloneQuestion()) == null ? question
                : condensed.value().standaloneQuestion();
        String searchQuery = null;
        List<RegulationHit> passages = List.of();
        if (condensed.value().needsRegulations()) {
            searchQuery = blankToNull(condensed.value().searchQuery()) == null ? standalone
                    : condensed.value().searchQuery();
            KnowledgeSearch.SearchResult search = knowledge.searchWithUsage(searchQuery);
            usage = usage.plus(search.usage());
            passages = search.hits().stream().map(this::trimmed).toList();
        }

        Set<String> chunkIds = new LinkedHashSet<>(caseFacts.chunkIds());
        passages.forEach(p -> chunkIds.add(p.chunkId()));
        String mentioned = mentioned(question, history.history());
        String asked = LlmAssistantModel.asked(question, untrusted);
        AssistantModel.Reply<AssistantModel.Answer> answer = model.answer(caseFacts.context(), caseFacts.text(),
                history.history(), passages, asked, List.of());
        usage = usage.plus(answer.usage());
        List<String> problems = Grounding.problems(answer.value(), conversation.caseId(), caseFacts.txnIds(),
                chunkIds, mentioned);
        for (int repair = 0; !problems.isEmpty() && repair < props.maxRepairs(); repair++) {
            answer = model.answer(caseFacts.context(), caseFacts.text(), history.history(), passages, asked, problems);
            usage = usage.plus(answer.usage());
            problems = Grounding.problems(answer.value(), conversation.caseId(), caseFacts.txnIds(), chunkIds,
                    mentioned);
        }
        long latency = (System.nanoTime() - start) / 1_000_000;
        if (!problems.isEmpty()) {
            log.warn("Assistant answer on case {} refused after {} repairs: {}", conversation.caseId(),
                    props.maxRepairs(), problems);
            audit.record(AuditEntry.forCurrentCaller("assistant turn", caseId, "UNGROUNDED", Map.of(
                    "conversationId", conversationId, "problems", String.valueOf(problems.size()),
                    "costUsd", usage.costUsd().toPlainString())));
            return new Ungrounded(problems);
        }

        AssistantModel.Answer value = answer.value();
        ConversationTurn turn = new ConversationTurn(expectedTurns + 1, question, standalone, searchQuery,
                value.answer().strip(), distinct(value.citedTxnIds()), distinct(value.citedChunkIds()), untrusted,
                caseFacts.fraudCase().version(), usage.model(), usage.inputTokens(), usage.outputTokens(),
                usage.cachedInputTokens(), usage.cacheWriteTokens(), usage.calls(), usage.costUsd(), latency, null);
        if (!conversations.append(caller.tenant(), conversationId, turn)) {
            return new Conflict(current(conversation));
        }
        // The text stays in the conversation table; the audit chain holds its hashes, so an edit there would show.
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("conversationId", conversationId);
        detail.put("turn", String.valueOf(turn.seq()));
        detail.put("questionSha256", sha256(question));
        detail.put("answerSha256", sha256(turn.answer()));
        detail.put("regulationSearch", String.valueOf(searchQuery != null));
        detail.put("untrustedInstructions", untrusted.isEmpty() ? "none" : String.join("; ", untrusted));
        detail.put("costUsd", usage.costUsd().toPlainString());
        audit.record(AuditEntry.forCurrentCaller("assistant turn", caseId, "OK", detail));
        log.info("Assistant turn {} on case {}: search={} cited={}/{} untrusted={} calls={} tokens={}/{} cached={} "
                        + "cost=${} {}ms", turn.seq(), conversation.caseId(), searchQuery != null,
                turn.citedTxnIds().size(), turn.citedChunkIds().size(), !untrusted.isEmpty(), usage.calls(),
                usage.inputTokens(), usage.outputTokens(), usage.cachedInputTokens(), usage.costUsd(), latency);
        View view = current(conversation);
        return new Answered(view, view.turns().getLast());
    }

    private record Folded(AssistantModel.History history, LlmUsage usage) {
    }

    /**
     * The history the model sees this turn, folding older turns into the summary when the window is full. A summary
     * that names an id the folded turns did not, or that another request folded first, is not stored: the turns it
     * would have covered are then left out of this prompt (the budget holds) and folded on a later turn.
     */
    private Folded history(CaseFacts.Facts caseFacts, Conversation conversation, List<ConversationTurn> turns) {
        HistoryWindow.Plan plan = HistoryWindow.plan(conversation.summaryThrough(), turns, props.windowTurns(),
                props.foldTurns(), props.historyMaxTokens());
        if (plan.toFold().isEmpty()) {
            return new Folded(new AssistantModel.History(conversation.summary(), plan.recent()), LlmUsage.NONE);
        }
        AssistantModel.Reply<AssistantModel.Summary> summary = model.summarise(caseFacts.context(),
                conversation.summary(), plan.toFold(), props.summaryMaxChars());
        String text = summary.value() == null ? null : summary.value().summary();
        List<String> problems = Grounding.summaryProblems(text, folded(conversation.summary(), plan.toFold()),
                props.summaryMaxChars());
        if (problems.isEmpty() && conversations.fold(conversation.tenantId(), conversation.conversationId(),
                conversation.summaryThrough(), plan.foldThrough(), text.strip())) {
            return new Folded(new AssistantModel.History(text.strip(), plan.recent()), summary.usage());
        }
        log.warn("Summary of turns {}-{} of conversation {} not stored: {}", conversation.summaryThrough() + 1,
                plan.foldThrough(), conversation.conversationId(), problems.isEmpty() ? "folded concurrently" : problems);
        return new Folded(new AssistantModel.History(conversation.summary(), plan.recent()), summary.usage());
    }

    private static String folded(String previous, List<ConversationTurn> turns) {
        StringBuilder text = new StringBuilder(previous == null ? "" : previous);
        for (ConversationTurn t : turns) {
            text.append('\n').append(t.question()).append('\n').append(t.answer()).append('\n')
                    .append(String.join(" ", t.citedTxnIds())).append(' ').append(String.join(" ", t.citedChunkIds()));
        }
        return text.toString();
    }

    /** What the analyst wrote or was told: ids from there may be repeated in an answer. */
    private static String mentioned(String question, AssistantModel.History history) {
        StringBuilder text = new StringBuilder(question);
        if (history.summary() != null) {
            text.append('\n').append(history.summary());
        }
        history.recent().forEach(t -> text.append('\n').append(t.question()).append('\n').append(t.answer()));
        return text.toString();
    }

    private boolean withinBudget(Caller caller) {
        return rateLimiter.tryAcquire(ANALYST_LIMIT_KEY + caller.tenant() + ":" + caller.subject(),
                props.maxTurnsPerAnalystPerHour(), Duration.ofHours(1))
                && rateLimiter.tryAcquire(TENANT_LIMIT_KEY + caller.tenant(), props.maxTurnsPerTenantPerHour(),
                Duration.ofHours(1));
    }

    private Optional<Conversation> find(Caller caller, String caseId, String conversationId) {
        if (!isUuid(caseId) || !isUuid(conversationId)) {
            return Optional.empty();
        }
        // The path's case only has to match: the conversation's own case is the one it is about.
        return conversations.find(caller.tenant(), caller.subject(), conversationId)
                .filter(c -> c.caseId().equals(caseId));
    }

    private View current(Conversation conversation) {
        Conversation fresh = conversations.find(conversation.tenantId(), conversation.analystSubject(),
                conversation.conversationId()).orElse(conversation);
        return new View(fresh, conversations.turns(fresh.tenantId(), fresh.conversationId()));
    }

    /** Long passages crowd out the case in the model's context; the citation still points to the full text. */
    private RegulationHit trimmed(RegulationHit hit) {
        int max = knowledge.maxPassageCharsForAgent();
        return hit.text().length() <= max ? hit : new RegulationHit(hit.chunkId(), hit.citation(), hit.section(),
                hit.page(), hit.text().substring(0, max) + " [...]");
    }

    private static List<String> distinct(List<String> ids) {
        return ids == null ? List.of() : new ArrayList<>(new LinkedHashSet<>(ids));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static boolean isUuid(String id) {
        try {
            return id != null && UUID.fromString(id).toString().equals(id.toLowerCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
