package com.finsentinel.platform.investigation;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Finds text, in data from outside the platform, that tries to instruct an automated reviewer (ADR 0016): naming a
 * disposition or a risk score, telling the reader to ignore its rules or keep quiet, addressing automated systems, or
 * imitating a system or assistant message. Deterministic and deliberately narrow: a staff note that says "do not call
 * before 9am" or "agent advised the customer" is ordinary bank data. What it finds does not depend on the model
 * noticing it: the platform flags the tool result, keeps such a case from being cleared, and records it on the case.
 */
public final class UntrustedInstructions {

    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.MULTILINE;

    private static final String DISPOSITION = "names a case disposition or risk score";
    private static final String IGNORE = "tells the reader to ignore its instructions or keep something quiet";
    private static final String ADDRESSES = "addresses automated systems";
    private static final String IMITATES = "imitates a system or assistant message";

    private static final Pattern NAMES_DISPOSITION =
            Pattern.compile("\\b(likely[_ ]legitimate|likely[_ ]fraud|needs[_ ]review|risk[ _]?score)\\b", FLAGS);
    private static final Pattern TELLS_TO_IGNORE =
            Pattern.compile("\\b(ignore|disregard|override)\\b.{0,30}\\b(instructions?|rules|guidance|playbook|prompt)\\b"
                    + "|\\bdo not (mention|report|flag|include|disclose)\\b", FLAGS);
    /** A note written to an automated reader. */
    private static final Pattern WRITES_TO_AI =
            Pattern.compile("\\b(note|message|instruction)s? (to|for) (the )?(automated|ai|llm|model|review system)", FLAGS);
    /** Any mention of automated reviewers: suspicious in bank data, ordinary in an analyst's question. */
    private static final Pattern MENTIONS_AI =
            Pattern.compile("\\b(automated|ai|llm) (review|reviewers|systems?|agents?|assistants?)\\b", FLAGS);
    private static final Pattern IMITATION =
            Pattern.compile("(^|[\\[\"{(]\\s*)(system|assistant|developer)\\s*[\\]:]|<\\|im_(start|end)\\|>", FLAGS);

    private UntrustedInstructions() {
    }

    /** What the text does that data should not do; empty when it reads like ordinary data. */
    public static List<String> find(String text) {
        List<String> found = new ArrayList<>();
        if (text == null) {
            return found;
        }
        add(found, DISPOSITION, NAMES_DISPOSITION.matcher(text).find());
        add(found, IGNORE, TELLS_TO_IGNORE.matcher(text).find());
        add(found, ADDRESSES, WRITES_TO_AI.matcher(text).find() || MENTIONS_AI.matcher(text).find());
        add(found, IMITATES, IMITATION.matcher(text).find());
        found.sort(null);
        return found;
    }

    /**
     * The same check for an analyst's question to the case assistant (ADR 0028), where the threat is text pasted from
     * outside (a customer's or a scammer's email), not the analyst. An analyst naturally names dispositions and asks
     * what "the AI agent" found, so those two signals are left out; instructions to ignore rules, notes written to an
     * automated reader and imitated system messages stay.
     */
    public static List<String> findInAnalystQuestion(String text) {
        List<String> found = new ArrayList<>();
        if (text == null) {
            return found;
        }
        add(found, IGNORE, TELLS_TO_IGNORE.matcher(text).find());
        add(found, ADDRESSES, WRITES_TO_AI.matcher(text).find());
        add(found, IMITATES, IMITATION.matcher(text).find());
        found.sort(null);
        return found;
    }

    private static void add(List<String> found, String signal, boolean present) {
        if (present) {
            found.add(signal);
        }
    }
}
