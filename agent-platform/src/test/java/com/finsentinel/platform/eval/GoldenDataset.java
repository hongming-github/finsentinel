package com.finsentinel.platform.eval;

import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The golden dataset, loaded and checked before any run: a mistake in the data must fail for free, not after paying
 * for the analyses it spoils.
 */
public record GoldenDataset(Map<String, Set<String>> chunkSets, List<GoldenCase> cases) {

    static final String RESOURCE = "eval/golden-cases.json";
    private static final Pattern CHUNK_ID = Pattern.compile("[a-z0-9-]+:[0-9]+[A-Z]?[0-9.]*(:[a-z0-9-]+)?(-p[0-9]+)?");
    private static final Set<String> SYSTEMS = Set.of("banking", "compliance");

    public GoldenDataset {
        chunkSets = chunkSets == null ? Map.of() : Map.copyOf(chunkSets);
        cases = cases == null ? List.of() : List.copyOf(cases);
    }

    public static GoldenDataset load(JsonMapper json) {
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            GoldenDataset dataset = json.readValue(in, GoldenDataset.class);
            List<String> problems = dataset.problems();
            if (!problems.isEmpty()) {
                throw new IllegalStateException(RESOURCE + " is invalid:\n- " + String.join("\n- ", problems));
            }
            return dataset;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The cases to run: all, or those named in {@code ids} (comma-separated); unknown names are an error. */
    public List<GoldenCase> select(String ids) {
        if (ids == null || ids.isBlank()) {
            return cases;
        }
        Set<String> wanted = Arrays.stream(ids.split(",")).map(String::trim).collect(Collectors.toSet());
        List<GoldenCase> selected = cases.stream().filter(c -> wanted.contains(c.id())).toList();
        if (selected.size() != wanted.size()) {
            throw new IllegalArgumentException("unknown case ids among " + wanted);
        }
        return selected;
    }

    public Set<String> chunks(String set) {
        return set == null ? Set.of() : chunkSets.getOrDefault(set, Set.of());
    }

    List<String> problems() {
        List<String> problems = new ArrayList<>();
        Set<String> tools = toolNames();
        Set<String> ids = new HashSet<>();
        chunkSets.forEach((name, chunks) -> chunks.stream().filter(c -> !CHUNK_ID.matcher(c).matches())
                .forEach(c -> problems.add("chunk set " + name + ": malformed chunk id " + c)));
        for (GoldenCase c : cases) {
            String at = "case " + c.id() + ": ";
            if (c.id() == null || !ids.add(c.id())) {
                problems.add(at + "missing or duplicate id");
            }
            if (c.category() == null || c.description() == null || c.expertView() == null) {
                problems.add(at + "category, description and expertView are required");
            }
            if (c.setup().account() == null) {
                problems.add(at + "setup.account is required");
            }
            Set<String> refs = new HashSet<>();
            for (GoldenCase.TxnSpec t : c.setup().transactions()) {
                if (t.ref() == null || !refs.add(t.ref())) {
                    problems.add(at + "missing or duplicate transaction ref " + t.ref());
                }
                if (t.amount() == null || t.channel() == null || t.country() == null || t.counterparty() == null) {
                    problems.add(at + "transaction " + t.ref() + " needs amount, channel, country and counterparty");
                }
                duration(t.at(), at + "transaction " + t.ref(), problems);
            }
            if (c.setup().history() != null) {
                duration(c.setup().history().before(), at + "history.before", problems);
            }
            c.setup().securityEvents().forEach(e -> duration(e.at(), at + "security event " + e.type(), problems));
            c.setup().screenings().forEach(s -> s.hits().forEach(h -> duration(h.listedOn(), at + "screening hit",
                    problems)));
            if (c.setup().profile() != null) {
                c.setup().profile().notes().forEach(n -> duration(n.at(), at + "note", problems));
                c.setup().profile().travelNotices().forEach(t -> {
                    duration(t.from(), at + "travel notice from", problems);
                    duration(t.to(), at + "travel notice to", problems);
                });
            }
            c.setup().systemsDown().stream().filter(s -> !SYSTEMS.contains(s))
                    .forEach(s -> problems.add(at + "unknown system " + s));
            if (c.alert() == null || c.alert().rule() == null || c.alert().severity() == null
                    || c.alert().transactions() == null || c.alert().transactions().isEmpty()) {
                problems.add(at + "alert needs a rule, a severity and at least one transaction");
            } else {
                c.alert().transactions().stream().filter(r -> !refs.contains(r))
                        .forEach(r -> problems.add(at + "alert names unknown transaction " + r));
            }
            GoldenCase.Expect e = c.expect();
            if (e.dispositions().isEmpty()) {
                problems.add(at + "expect.dispositions is required");
            }
            if (e.minRiskScore() != null && e.maxRiskScore() != null && e.minRiskScore() > e.maxRiskScore()) {
                problems.add(at + "minRiskScore above maxRiskScore");
            }
            List<String> named = new ArrayList<>(e.requiredTools());
            named.addAll(e.forbiddenTools());
            named.stream().filter(t -> !tools.contains(t)).forEach(t -> problems.add(at + "unknown tool " + t));
            e.requiredTools().stream().filter(e.forbiddenTools()::contains)
                    .forEach(t -> problems.add(at + "tool both required and forbidden: " + t));
            if (e.regulations() != null) {
                e.regulations().relevantChunkSets().stream().filter(s -> !chunkSets.containsKey(s))
                        .forEach(s -> problems.add(at + "unknown chunk set " + s));
            }
        }
        return problems;
    }

    private static void duration(String value, String where, List<String> problems) {
        if (value == null) {
            problems.add(where + ": time is required");
            return;
        }
        try {
            Duration.parse(value);
        } catch (DateTimeParseException ex) {
            problems.add(where + ": not an ISO-8601 duration: " + value);
        }
    }

    /** The agents' tool names, read from the tools class so a renamed tool fails here. */
    static Set<String> toolNames() {
        try {
            Class<?> tools = Class.forName("com.finsentinel.platform.investigation.InvestigationTools");
            Set<String> names = new HashSet<>();
            for (Method m : tools.getDeclaredMethods()) {
                if (m.isAnnotationPresent(org.springframework.ai.tool.annotation.Tool.class)) {
                    names.add(m.getName());
                }
            }
            return names;
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }
}
