package com.finsentinel.platform.privacy;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replaces personal data with pseudonyms before text reaches a model provider (ADR 0015): Singapore NRIC/FIN numbers,
 * email addresses, phone numbers and payment card numbers (Luhn-checked). A pseudonym is {@code [KIND_8 hex]}, an HMAC
 * of the value under a secret key and a scope (the case): the same number is the same pseudonym throughout one case,
 * so the model can still notice that the phone changed in online banking is not the one on the CRM profile, but
 * different cases get unrelated pseudonyms and the provider cannot reverse them.
 *
 * <p>Pattern-based: it finds identifiers with a fixed shape. Names and free-form addresses are not detected; the
 * defence there is data minimisation (the CRM view sent to the agents carries no name or address fields).
 */
@Component
public class PiiMasker {

    private record Rule(String kind, Pattern pattern, Predicate<String> accept) {
    }

    // Never part of a longer token: ids, UUIDs, amounts and timestamps contain digit runs too.
    private static final String START = "(?<![\\w.@+-])";
    private static final String END = "(?![\\w@-]|\\.\\d)";

    private static final List<Rule> RULES = List.of(
            new Rule("EMAIL", Pattern.compile(START + "[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}"
                    + END), v -> true),
            new Rule("NRIC", Pattern.compile(START + "[STFGM]\\d{7}[A-Z]" + END), v -> true),
            new Rule("CARD", Pattern.compile(START + "\\d(?:[ -]?\\d){12,18}" + END), PiiMasker::luhn),
            // +65 or any international number, or a bare Singapore number (8 digits starting 3, 6, 8 or 9).
            new Rule("PHONE", Pattern.compile("(?:(?<![\\w.@+-])|(?<=\\s))\\+\\d{1,3}[ -]?\\d(?:[ -]?\\d){6,13}" + END
                    + "|" + START + "[3689]\\d{3}[ -]?\\d{4}" + END), v -> true));

    private final SecretKeySpec key;

    PiiMasker(PrivacyProperties props) {
        this.key = new SecretKeySpec(props.pseudonymKey().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    /** {@code text} with every recognised identifier replaced by its pseudonym within {@code scope}. */
    public String mask(String text, String scope) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = text;
        for (Rule rule : RULES) {
            Matcher m = rule.pattern().matcher(out);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                String value = m.group();
                String replacement = rule.accept().test(value) ? pseudonym(rule.kind(), normalise(value), scope) : value;
                m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
            }
            m.appendTail(sb);
            out = sb.toString();
        }
        return out;
    }

    private String pseudonym(String kind, String value, String scope) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            byte[] digest = mac.doFinal((scope + "\u0000" + value).getBytes(StandardCharsets.UTF_8));
            // "_" before the hex: masking already masked text changes nothing, even when the hex is all digits.
            return "[" + kind + "_" + HexFormat.of().formatHex(digest, 0, 4) + "]";
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** "+65 9123-4567" and "91234567" are the same number for the pseudonym; case does not matter for emails. */
    private static String normalise(String value) {
        String digits = value.replaceAll("[ -]", "");
        if (digits.startsWith("+65")) {
            digits = digits.substring(3);
        }
        return digits.toLowerCase(java.util.Locale.ROOT);
    }

    private static boolean luhn(String number) {
        String digits = number.replaceAll("[ -]", "");
        int sum = 0;
        boolean twice = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (twice) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            twice = !twice;
        }
        return sum % 10 == 0;
    }
}
