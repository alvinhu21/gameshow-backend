package com.gameShows.live.jeopardy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gameShows.models.jeopardy.Clue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * What a clue accepts as right: one or more parts the player has to name - every one of them, in
 * any order - each with one or more interchangeable spellings. Nearly every clue is a single part
 * with a single spelling, its response. Two other shapes come up on the show:
 * <ul>
 *   <li>several acceptable responses, any one of which will do ("Krone" or "Krona") - one part with
 *       several spellings;</li>
 *   <li>several responses that are all needed ("these 2 cities": St. Joseph and Sacramento) - one
 *       part per response.</li>
 * </ul>
 *
 * <p>A clue opts into either through {@link Clue#getAcceptedResponses()}, a small JSON document:
 * <pre>
 *   {"anyOf": ["Krone", "Krona"]}
 *   {"allOf": ["St. Joseph", "Sacramento"]}
 *   {"allOf": [["St. Joseph", "Saint Joseph"], "Sacramento"]}   (an inner list is one part's spellings)
 * </pre>
 * The two cases get their own words rather than differing only by how deeply a list is nested, so
 * whoever writes the data can't ask for "both" while meaning "either".
 */
public record AnswerKey(List<List<String>> parts) {

    /** Bounds the matcher's work. No real clue asks for anywhere near this many responses. */
    public static final int MAX_PARTS = 8;

    static final String ANY_OF = "anyOf";
    static final String ALL_OF = "allOf";

    private static final Logger log = LoggerFactory.getLogger(AnswerKey.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    public AnswerKey {
        if (parts == null || parts.isEmpty()) {
            throw new IllegalArgumentException("an answer key needs at least one response");
        }
        if (parts.size() > MAX_PARTS) {
            throw new IllegalArgumentException("at most " + MAX_PARTS + " responses can be required, not "
                    + parts.size());
        }
        // List.copyOf also turns away a null part or spelling.
        parts = parts.stream().map(List::copyOf).toList();
        for (List<String> spellings : parts) {
            if (spellings.isEmpty()) {
                throw new IllegalArgumentException("every required response needs at least one spelling");
            }
        }
    }

    /** A clue judged against its one response - the shape almost every clue has. */
    public static AnswerKey of(String response) {
        return new AnswerKey(List.of(List.of(response == null ? "" : response)));
    }

    /**
     * The key a clue is judged by: its accepted responses when it lists them, otherwise its
     * response. Accepted responses that can't be read are logged and passed over in favour of the
     * response, so one mistyped row costs that clue its alternatives instead of ending the game.
     */
    public static AnswerKey forClue(Clue clue) {
        String accepted = clue.getAcceptedResponses();
        if (accepted == null || accepted.isBlank()) {
            return of(clue.getResponse());
        }
        try {
            return parse(accepted);
        } catch (IllegalArgumentException e) {
            log.warn("Clue {} has unusable acceptedResponses ({}) - judging it against its response '{}' instead.",
                    clue.getId(), e.getMessage(), clue.getResponse());
            return of(clue.getResponse());
        }
    }

    /**
     * Reads the JSON form described on this class. Strict on purpose: a hand-edited row that
     * doesn't say exactly what it means is rejected rather than guessed at.
     *
     * @throws IllegalArgumentException naming what's wrong with it.
     */
    public static AnswerKey parse(String json) {
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("not valid JSON", e);
        }
        if (root == null || !root.isObject() || root.size() != 1) {
            throw new IllegalArgumentException("expected an object holding just \"" + ANY_OF + "\" or \"" + ALL_OF + "\"");
        }
        if (root.has(ANY_OF)) {
            return new AnswerKey(List.of(spellings(root.get(ANY_OF), ANY_OF)));
        }
        if (root.has(ALL_OF)) {
            JsonNode items = requireNonEmptyArray(root.get(ALL_OF), ALL_OF);
            List<List<String>> parts = new ArrayList<>();
            for (JsonNode item : items) {
                parts.add(item.isArray() ? spellings(item, ALL_OF) : List.of(text(item, ALL_OF)));
            }
            return new AnswerKey(parts);
        }
        throw new IllegalArgumentException("expected \"" + ANY_OF + "\" or \"" + ALL_OF + "\", not \""
                + root.fieldNames().next() + "\"");
    }

    /** How many separate responses the player has to give - 1 for nearly every clue. */
    public int requiredCount() {
        return parts.size();
    }

    /**
     * Whether exactly one response is right. Only such a clue can be answered by picking from a
     * list: a single pick can't name two things, and an alternative could be dealt as a "wrong"
     * option.
     */
    public boolean isSingleResponse() {
        return parts.size() == 1 && parts.get(0).size() == 1;
    }

    /** A response naming every part, the way a contestant who knows it would say it. */
    public String canonicalResponse() {
        return parts.stream().map(spellings -> spellings.get(0)).collect(Collectors.joining(" and "));
    }

    private static List<String> spellings(JsonNode node, String field) {
        List<String> spellings = new ArrayList<>();
        for (JsonNode item : requireNonEmptyArray(node, field)) {
            spellings.add(text(item, field));
        }
        return spellings;
    }

    private static JsonNode requireNonEmptyArray(JsonNode node, String field) {
        if (node == null || !node.isArray() || node.isEmpty()) {
            throw new IllegalArgumentException("\"" + field + "\" must be a non-empty list");
        }
        return node;
    }

    private static String text(JsonNode node, String field) {
        if (!node.isTextual() || node.asText().isBlank()) {
            throw new IllegalArgumentException("\"" + field + "\" may only hold non-blank text");
        }
        return node.asText().strip();
    }
}
