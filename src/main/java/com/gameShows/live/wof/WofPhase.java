package com.gameShows.live.wof;

public enum WofPhase {
    FORTUNE_FRENZY,
    /** A Fortune Frenzy puzzle just resolved (solved, or everyone locked out) - its full solution
     *  is on screen and the 1-minute Frenzy window is paused until a player clicks Continue. */
    FRENZY_PUZZLE_OVER,
    WAITING_FOR_TURN_ACTION,
    AWAITING_LETTER_GUESS,
    AWAITING_SOLVE,
    ROUND_OVER,
    TRIPLE_TOSS_UP,
    FINAL_SPIN,
    SPEEDUP_AWAITING_LETTER,
    SPEEDUP_AWAITING_SOLVE,
    BONUS_PICK_CATEGORY,
    BONUS_PICK_LETTERS,
    BONUS_SOLVE,
    GAME_OVER
}
