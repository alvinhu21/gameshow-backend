package com.gameShows.live.jeopardy;

import com.gameShows.live.session.PlayerView;

import java.time.Instant;
import java.util.List;

public record JeopardyStateSnapshot(
        String type,
        String phase,
        int currentRound,
        /** How many regular rounds this game runs (2-4, host-chosen); Final Jeopardy is on top. */
        int roundCount,
        List<BoardCellView> board,
        ActiveCellView activeCell,
        String clueSubPhase,
        String activeAnswererId,
        List<String> lockedOutPlayerIds,
        String playerIdInControl,
        /** When the current timed window (buzz-in, answer, Final Jeopardy answers) opened - paired
         *  with phaseDeadlineAt so clients can show how much of it is left. Null whenever that is. */
        Instant phaseStartedAt,
        Instant phaseDeadlineAt,
        String finalCategory,
        /** The Final Jeopardy clue, and its options when it's answered from a list - both null until
         *  every wager is in, since wagers are made knowing only the category. */
        String finalClueText,
        List<String> finalResponseChoices,
        /** How many separate responses the Final Jeopardy clue needs - null exactly when finalClueText is. */
        Integer finalRequiredResponseCount,
        /** Who finished the last regular round with money and therefore gets to play Final
         *  Jeopardy - anyone at $0 or in the red is out. Null until Final Jeopardy is reached. */
        List<String> finalEligiblePlayerIds,
        List<String> finalWagerSubmittedPlayerIds,
        List<String> finalAnswerSubmittedPlayerIds,
        List<FinalResultEntry> finalResults,
        /** The contestants tied for the lead when ties aren't allowed - the only ones who play the
         *  tiebreaker. Null unless a tiebreaker was needed; kept once the game is over. */
        List<String> tiebreakerPlayerIds,
        /** Which tiebreaker clue is (or was last) in play: 1, then 2 if nobody got that one... Null
         *  unless a tiebreaker was needed. */
        Integer tiebreakerNumber,
        List<PlayerView> players,
        List<String> gameWinnerPlayerIds) {
}
