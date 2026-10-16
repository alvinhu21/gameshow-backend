package com.gameShows.live.jeopardy.choices;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The options dealt for a clue that's answered by picking from a list instead of typing - five
 * states, five countries, five presidents - exactly one of which is right.
 *
 * <p>Judging is an exact pick, never the lenient typed-answer matching: the distractors are chosen
 * to look alike, and a fuzzy match would happily accept "Austria" for "Australia".
 */
public record ResponseChoices(List<String> options, String correctOption) {

    public ResponseChoices {
        options = List.copyOf(options);
        Objects.requireNonNull(correctOption, "correctOption");
        if (!options.contains(correctOption)) {
            throw new IllegalArgumentException("The correct option must be one of the options: " + correctOption);
        }
    }

    /** Whether this answer is one of the options on offer - anything else was never a valid pick. */
    public boolean offers(String answer) {
        return find(answer).isPresent();
    }

    public boolean isCorrect(String answer) {
        return find(answer).map(correctOption::equals).orElse(false);
    }

    public List<String> wrongOptions() {
        return options.stream().filter(option -> !option.equals(correctOption)).toList();
    }

    private Optional<String> find(String answer) {
        if (answer == null) {
            return Optional.empty();
        }
        String picked = answer.strip();
        return options.stream().filter(option -> option.equalsIgnoreCase(picked)).findFirst();
    }
}
