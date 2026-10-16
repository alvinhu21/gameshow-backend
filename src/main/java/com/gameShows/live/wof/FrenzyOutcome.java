package com.gameShows.live.wof;

/** How a Fortune Frenzy puzzle ended - clients flash the board frame green for a solve, red otherwise. */
public enum FrenzyOutcome {
    /** A contestant rang in and solved it. */
    SOLVED,
    /** A contestant rang in and gave a wrong answer, or no answer in time - the puzzle is thrown out. */
    MISSED,
    /** Nobody rang in before the board filled in or the Frenzy clock ran out. */
    UNSOLVED
}
