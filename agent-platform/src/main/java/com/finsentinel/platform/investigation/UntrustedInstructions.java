package com.finsentinel.platform.investigation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Finds text, in data from outside the platform, that tries to instruct an automated reviewer (ADR 0016): naming a
 * disposition or a risk score, telling the reader to ignore its rules or keep quiet, addressing automated systems, or
 * imitating a system or assistant message. Deterministic and deliberately narrow: a staff note that says "do not call
 * before 9am" or "agent advised the customer" is ordinary bank data. What it finds does not depend on the model
 * noticing it: the platform flags the tool result, keeps such a case from being cleared, and records it on the case.
 */
final class UntrustedInstructions {

    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.MULTILINE;

    private static final Map<String, Pattern> SIGNALS = Map.of(
            "names a case disposition or risk score",
            Pattern.compile("\\b(likely[_ ]legitimate|likely[_ ]fraud|needs[_ ]review|risk[ _]?score)\\b", FLAGS),
            "tells the reader to ignore its instructions or keep something quiet",
            Pattern.compile("\\b(ignore|disregard|override)\\b.{0,30}\\b(instructions?|rules|guidance|playbook|prompt)\\b"
                    + "|\\bdo not (mention|report|flag|include|disclose)\\b", FLAGS),
            "addresses automated systems",
            Pattern.compile("\\b(note|message|instruction)s? (to|for) (the )?(automated|ai|llm|model|review system)"
                    + "|\\b(automated|ai|llm) (review|reviewers|systems?|agents?|assistants?)\\b", FLAGS),
            "imitates a system or assistant message",
            Pattern.compile("(^|[\\[\"{(]\\s*)(system|assistant|developer)\\s*[\\]:]|<\\|im_(start|end)\\|>", FLAGS));

    private UntrustedInstructions() {
    }

    /** What the text does that data should not do; empty when it reads like ordinary data. */
    static List<String> find(String text) {
        List<String> found = new ArrayList<>();
        if (text == null) {
            return found;
        }
        SIGNALS.forEach((signal, pattern) -> {
            if (pattern.matcher(text).find()) {
                found.add(signal);
            }
        });
        found.sort(null);
        return found;
    }
}
