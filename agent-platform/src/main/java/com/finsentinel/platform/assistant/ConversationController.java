package com.finsentinel.platform.assistant;

import com.finsentinel.platform.security.Caller;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * The case assistant's API (ADR 0028), analysts only. {@code TenantPathFilter} has already checked the path's tenant
 * against the token; the analyst is the token's user. A turn names the conversation's turn count in {@code If-Match}
 * (the ETag of the conversation), like a decision names the case version: two tabs cannot both add turn n.
 */
@RestController
@RequestMapping("/api/tenants/{tenantId}/cases/{caseId}/conversations")
class ConversationController {

    private final CaseAssistant assistant;

    ConversationController(CaseAssistant assistant) {
        this.assistant = assistant;
    }

    @PostMapping
    ResponseEntity<Conversation> start(@PathVariable String tenantId, @PathVariable String caseId) {
        return assistant.start(caller(), caseId)
                .map(c -> ResponseEntity.status(HttpStatus.CREATED).eTag(etag(c)).body(c))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping
    List<Conversation> list(@PathVariable String tenantId, @PathVariable String caseId) {
        return assistant.list(caller(), caseId);
    }

    @GetMapping("/{conversationId}")
    ResponseEntity<CaseAssistant.View> read(@PathVariable String tenantId, @PathVariable String caseId,
                                            @PathVariable String conversationId) {
        return assistant.read(caller(), caseId, conversationId)
                .map(v -> ResponseEntity.ok().eTag(etag(v.conversation())).body(v))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/{conversationId}/turns")
    ResponseEntity<?> ask(@PathVariable String tenantId, @PathVariable String caseId,
                          @PathVariable String conversationId,
                          @RequestHeader(name = "If-Match", required = false) String ifMatch,
                          @RequestBody QuestionRequest request) {
        if (ifMatch == null || ifMatch.isBlank()) {
            return ResponseEntity.status(HttpStatus.PRECONDITION_REQUIRED)
                    .body(Map.of("error", "If-Match with the conversation's turn count is required"));
        }
        int turns;
        try {
            turns = Integer.parseInt(ifMatch.replace("W/", "").replace("\"", "").trim());
        } catch (NumberFormatException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "If-Match must be the conversation's turn count"));
        }
        return switch (assistant.ask(caller(), caseId, conversationId, turns, request.question())) {
            case CaseAssistant.Answered answered -> ResponseEntity.ok()
                    .eTag(etag(answered.view().conversation())).body(answered.view());
            case CaseAssistant.NotFound notFound -> ResponseEntity.notFound().build();
            case CaseAssistant.Conflict conflict -> ResponseEntity.status(HttpStatus.CONFLICT)
                    .eTag(etag(conflict.current().conversation())).body(conflict.current());
            case CaseAssistant.Refused refused -> ResponseEntity.status(switch (refused.reason()) {
                case INVALID -> HttpStatus.BAD_REQUEST;
                case CONVERSATION_FULL -> HttpStatus.UNPROCESSABLE_CONTENT;
                case OVER_BUDGET -> HttpStatus.TOO_MANY_REQUESTS;
            }).body(Map.of("error", refused.message()));
            // The model failed to stay within what it was given: an upstream failure, not the analyst's.
            case CaseAssistant.Ungrounded ungrounded -> ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("error", "the assistant could not give an answer grounded in this case; try "
                            + "rephrasing the question", "problems", ungrounded.problems()));
        };
    }

    record QuestionRequest(String question) {
    }

    private static Caller caller() {
        return Caller.current().orElseThrow();
    }

    private static String etag(Conversation c) {
        return "\"" + c.turns() + "\"";
    }
}
