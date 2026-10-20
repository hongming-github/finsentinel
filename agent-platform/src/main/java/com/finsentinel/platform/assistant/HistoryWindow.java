package com.finsentinel.platform.assistant;

import java.util.List;

/**
 * Which earlier turns the model sees word for word, and which are folded into the summary (ADR 0028). The latest
 * {@code window} turns always go word for word. Older ones are folded once {@code foldBatch} of them have piled up,
 * not one per turn: every fold changes the prompt's prefix, and with it what the provider can serve from its cache.
 * When the word-for-word turns would exceed the token budget anyway (long answers), more are folded, down to the
 * latest turn. Tokens are estimated at four characters each: a budget, not a bill.
 */
final class HistoryWindow {

    /** Per turn on top of its text: message framing and the cited ids. */
    private static final int TURN_OVERHEAD_TOKENS = 16;

    private HistoryWindow() {
    }

    /**
     * @param foldThrough the turn the summary will cover once {@code toFold} is folded; the current one when nothing is
     * @param toFold      turns to fold into the summary now, oldest first
     * @param recent      turns to send word for word, oldest first
     */
    record Plan(int foldThrough, List<ConversationTurn> toFold, List<ConversationTurn> recent) {
    }

    /** @param turns every stored turn, oldest first */
    static Plan plan(int summaryThrough, List<ConversationTurn> turns, int window, int foldBatch, int maxTokens) {
        List<ConversationTurn> unfolded = turns.stream().filter(t -> t.seq() > summaryThrough).toList();
        int fold = unfolded.size() - window >= foldBatch ? unfolded.size() - window : 0;
        while (fold < unfolded.size() - 1 && tokens(unfolded.subList(fold, unfolded.size())) > maxTokens) {
            fold++;
        }
        List<ConversationTurn> toFold = unfolded.subList(0, fold);
        return new Plan(toFold.isEmpty() ? summaryThrough : toFold.getLast().seq(), List.copyOf(toFold),
                List.copyOf(unfolded.subList(fold, unfolded.size())));
    }

    static int tokens(List<ConversationTurn> turns) {
        return turns.stream().mapToInt(HistoryWindow::tokens).sum();
    }

    static int tokens(ConversationTurn turn) {
        return estimate(turn.question()) + estimate(turn.answer()) + TURN_OVERHEAD_TOKENS;
    }

    static int estimate(String text) {
        return text == null ? 0 : (text.length() + 3) / 4;
    }
}
