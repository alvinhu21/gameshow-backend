package com.gameShows.live.jeopardy;

import java.util.List;

public record ActiveCellView(
        String cellId,
        String category,
        int value,
        String clueText,
        boolean isDailyDouble,
        boolean dailyDoubleInProgress,
        /** The options for a clue answered by picking from a list; null for a typed answer. Hidden
         *  exactly when clueText is, since the options alone give away what kind of answer it is. */
        List<String> responseChoices,
        /** How many separate responses the clue needs ("these 2 cities") - 1 for nearly every clue.
         *  Null exactly when clueText is. */
        Integer requiredResponseCount,
        /** The correct response - set only once the clue is over and nobody got it (clueSubPhase
         *  REVEALED), so it never reaches a client while the clue can still be answered. */
        String correctResponse,
        /** A {@link ClueRevealReason} name, set exactly when correctResponse is. */
        String revealReason) {
}
