package com.gameShows.live.jeopardy;

/**
 * Round-agnostic: which round a BOARD/CLUE_OPEN/DAILY_DOUBLE phase belongs to is carried by the
 * engine's (and snapshot's) currentRound, since a game now runs anywhere from 2 to 4 rounds.
 */
public enum JeopardyPhase {
    BOARD,
    CLUE_OPEN,
    DAILY_DOUBLE_WAGER,
    DAILY_DOUBLE_ANSWER,
    FINAL_WAGER,
    FINAL_ANSWER_COLLECTION,
    FINAL_REVEAL,
    /** Ties aren't allowed and two or more contestants share the lead: only they play, one clue at
     *  a time, until one of them gets one right. Runs the same clue sub-phases as CLUE_OPEN, plus
     *  NONE first, while the category is up and the clue itself is still hidden. */
    TIEBREAKER,
    GAME_OVER
}
