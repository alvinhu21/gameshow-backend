package com.gameShows.live.jeopardy;

/** Why a clue's correct response is on screen: it ended without anyone getting it right. */
public enum ClueRevealReason {
    /** The buzz-in window ran out - nobody rang in, or nobody else did after a miss. */
    TIME_EXPIRED,
    /** Every contestant rang in and missed. */
    ALL_INCORRECT,
    /** The Daily Double's finder answered it incorrectly. */
    DAILY_DOUBLE_INCORRECT
}
