package com.gameShows.live.wof;

import com.gameShows.live.session.PlayerView;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record WofStateSnapshot(
        String type,
        String phase,
        int roundNumber,
        // Only meaningful through Round 3 (the fixed rounds before the Triple Toss-Up) - null once
        // rounds become open-ended (Round 4 onward), since there's no longer a fixed total.
        Integer totalRounds,
        String category,
        String puzzleTemplate,
        List<Character> guessedConsonants,
        List<Character> guessedVowels,
        // Whether the current puzzle still hides a consonant / a vowel - public on the show (the
        // host announces "only vowels remain" and "no more vowels"). No spin is allowed once the
        // consonants are gone (a spin always ends in a consonant call), and no vowel purchase once
        // the vowels are.
        boolean consonantsLeft,
        boolean vowelsLeft,
        String currentPlayerId,
        // Null while the wheel is still turning - the landed wedge is only revealed once it settles.
        // Stays as landed for the rest of the turn, even once a claimed Wild Card or Million Dollar
        // Wedge has come off the wheel - it's what the spin is worth.
        WedgeView lastSpinResult,
        // The wheel as it stands right now, special wedges included.
        List<WedgeView> wheel,
        // Index into `wheel` of the wedge under the landing slot.
        Integer lastSpinIndex,
        // The latest physical spin (landing slot + timing) that clients animate against - see
        // WheelSpinView. Null until the first spin of the game.
        WheelSpinView wheelSpin,
        List<PlayerView> players,
        // Money won this round (rounds 1-3, open-ended rounds 4+, and the final speed-up round)
        // that hasn't been folded into a player's permanent score yet - BANKRUPT wipes only this,
        // and it's added to the permanent total the moment the round ends. Frenzy/Triple-Toss-Up/
        // Bonus cash bypasses this and goes straight into the permanent score.
        Map<String, Integer> roundScores,
        String roundWinnerPlayerId,
        List<String> gameWinnerPlayerIds,
        // Special wedges - one of each per game, shown on the holder's score display. Bankrupt takes
        // either one away.
        String wildCardHolderId,
        // True when the player whose turn it is may play their Wild Card right now: only as the very
        // next move after a correct consonant on a spin, and never in the Speed-Up.
        boolean wildCardUsable,
        // True while the consonant being called is the Wild Card's extra letter.
        boolean wildCardInPlay,
        String millionWedgeHolderId,
        // False while the holder still has to solve the round they picked it up in; true once banked.
        boolean millionWedgeSecured,
        // Triple Toss-Up: players who've used their one attempt on the puzzle currently on screen.
        // (Fortune Frenzy has no lockouts - a single ring-in settles each puzzle.)
        List<String> puzzleLockedOutPlayerIds,
        // Every timed window below comes as a *StartedAt/*DeadlineAt (or *EndsAt) pair, so clients
        // can show how much of the window is left rather than only when it ends.
        //
        // Normal round: the 30s letter-box solve window, opened only by an explicit declare action.
        String solvingPlayerId,
        Instant solveStartedAt,
        Instant solveDeadlineAt,
        // Fortune Frenzy (pre-round-1, season 44+): 30 seconds of rapid-fire $1,000 toss-ups, all
        // from one category. The clock runs while a puzzle is on the board and stops while a
        // finished one's solution is shown (FRENZY_PUZZLE_OVER). frenzyStartedAt/frenzyDeadlineAt
        // are only set while it runs; frenzyStartedAt is the deadline minus the full 30 seconds, so
        // time spent stopped never counts against it.
        Instant frenzyStartedAt,
        Instant frenzyDeadlineAt,
        // True once the clock has run out. A contestant who rang in before then still gets to
        // answer; after that the Frenzy is over and Continue begins Round 1.
        boolean frenzyTimeUp,
        // What's left on the clock as of this snapshot - while it's stopped, this is the frozen value
        // to show. Null outside the Frenzy.
        Long frenzyRemainingMs,
        Long frenzyWindowMs,
        // 1-based count of Frenzy puzzles brought up so far.
        Integer frenzyPuzzleNumber,
        // FRENZY_PUZZLE_OVER only: how the puzzle on screen ended (a FrenzyOutcome name - SOLVED
        // flashes the frame green, MISSED/UNSOLVED red) and who rang in on it, if anyone.
        String frenzyOutcome,
        String frenzyOutcomePlayerId,
        // Who'd start Round 1 if the Frenzy ended now: whoever gave the last correct answer, or the
        // first player if nobody has solved one.
        String frenzyRoundOneStarterId,
        // Fortune Frenzy's buzz-in answer window: only one player may hold it at a time, and their
        // answer settles the puzzle.
        String frenzySolvingPlayerId,
        Instant frenzySolveStartedAt,
        Instant frenzySolveDeadlineAt,
        // Triple Toss-Up (before round 4): three same-category puzzles worth $2,000 each.
        String tripleTossUpCategory,
        Integer tripleTossUpIndex,
        List<String> tripleTossUpSolverIds,
        // Triple Toss-Up's own 30s buzz-in solve window, same rule as Frenzy's.
        String tripleTossUpSolvingPlayerId,
        Instant tripleTossUpSolveStartedAt,
        Instant tripleTossUpSolveDeadlineAt,
        // The overall game clock - once it runs out, the next "waiting for a turn" moment (Round 4
        // or later) goes to the Final Spin instead of another normal round.
        Instant gameClockStartedAt,
        Instant gameClockEndsAt,
        // Final round speed-up
        Integer speedupLetterValue,
        String speedupSolvingPlayerId,
        // Bonus round
        String bonusWinnerPlayerId,
        List<String> bonusCategoryOptions,
        String bonusCategory,
        String bonusPuzzleTemplate,
        // How many consonants the bonus contestant picks: 3, or 4 when they brought the Wild Card.
        Integer bonusConsonantCount,
        // The bonus contestant brought a banked Million Dollar Wedge: the $100,000 envelope on the
        // Bonus Wheel is now $1,000,000.
        boolean bonusMillionInPlay,
        Integer bonusPrizeAmount,
        Boolean bonusSolved) {
}
