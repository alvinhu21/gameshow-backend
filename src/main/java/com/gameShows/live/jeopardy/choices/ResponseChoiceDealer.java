package com.gameShows.live.jeopardy.choices;

import com.gameShows.models.jeopardy.Clue;

import java.util.Optional;
import java.util.Random;

/**
 * Decides whether a clue is answered from a list and, if so, deals its options. Called once per
 * clue when a board (or Final Jeopardy) is set up, so every player - and every reconnect - sees the
 * same options in the same order.
 */
@FunctionalInterface
public interface ResponseChoiceDealer {

    /** Every clue is answered by typing. */
    ResponseChoiceDealer NONE = (clue, random) -> Optional.empty();

    /** @return the options to pick from, or empty when the clue is answered by typing. */
    Optional<ResponseChoices> deal(Clue clue, Random random);
}
