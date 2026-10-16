package com.gameShows.live.session;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * How good the computer-controlled opponents are, chosen by the host when
 * starting a session and
 * applied by both game engines. Every dial is expressed as "how often does the
 * bot do the optimal
 * thing", so a single setting weakens an AI consistently across a whole game
 * rather than only in
 * one phase of it.
 *
 * <p>
 * {@link #HARD} is deliberately the old behaviour: perfect knowledge of every
 * clue and puzzle,
 * instant buzzers, everything wagered. {@link #MEDIUM} is the default for new
 * games.
 */
public enum AiDifficulty {

    /** Rarely knows an answer, seldom rings in, barely bets. */
    BEGINNER(0.10, 0.2, 0.1, 0.95, 4.0),
    /** Misses far more than it hits, slow on the buzzer, bets small. */
    EASY(0.35, 0.45, 0.25, 0.95, 2.5),
    /** A plausible casual player - wrong about as often as a real contestant is. */
    MEDIUM(0.60, 0.70, 0.50, 0.80, 1.6),
    /** Perfect knowledge, always races for the buzzer, always bets the max. */
    HARD(1.00, 1.00, 1.00, 0.60, 1.0),
    /** Perfect play, and goes for the solve with far less of the puzzle showing, twice as fast. */
    EXPERT(1.00, 1.00, 1.00, 0.30, 0.5),
    /** Perfect play with near-instant reactions - solves almost as soon as the board appears. */
    MASTER(1.00, 1.00, 1.00, 0.10, 0.1);

    public static final AiDifficulty DEFAULT = MEDIUM;

    private final double correctAnswerChance;
    private final double buzzInChance;
    private final double wagerFraction;
    private final double puzzleSolveThreshold;
    private final double reactionDelayMultiplier;

    AiDifficulty(double correctAnswerChance, double buzzInChance, double wagerFraction,
            double puzzleSolveThreshold, double reactionDelayMultiplier) {
        this.correctAnswerChance = correctAnswerChance;
        this.buzzInChance = buzzInChance;
        this.wagerFraction = wagerFraction;
        this.puzzleSolveThreshold = puzzleSolveThreshold;
        this.reactionDelayMultiplier = reactionDelayMultiplier;
    }

    /**
     * How often the bot actually knows the answer: a Jeopardy response, a Wheel
     * puzzle solution,
     * or which letters are really in the puzzle.
     */
    public double correctAnswerChance() {
        return correctAnswerChance;
    }

    /** How often the bot bothers to race a human for a buzzer or a toss-up. */
    public double buzzInChance() {
        return buzzInChance;
    }

    /**
     * The fraction of its available money the bot risks on a Daily Double or Final
     * Jeopardy.
     */
    public double wagerFraction() {
        return wagerFraction;
    }

    /**
     * Wheel of Fortune: how much of the puzzle has to be showing before the bot
     * tries to solve.
     */
    public double puzzleSolveThreshold() {
        return puzzleSolveThreshold;
    }

    /**
     * Stretches the bot's "thinking" pauses, so a weaker opponent is also a slower
     * one and a
     * human has a real chance to hit the buzzer first.
     */
    public Duration stretch(Duration delay) {
        return Duration.ofMillis(Math.round(delay.toMillis() * reactionDelayMultiplier));
    }

    /**
     * True {@code chance} of the time - the single coin flip every difficulty dial
     * is read through.
     */
    public boolean rolls(double chance) {
        if (chance >= 1.0) {
            return true;
        }
        return ThreadLocalRandom.current().nextDouble() < chance;
    }

    /** True when the bot knows this particular answer. */
    public boolean knowsAnswer() {
        return rolls(correctAnswerChance);
    }

    public static AiDifficulty orDefault(AiDifficulty difficulty) {
        return difficulty == null ? DEFAULT : difficulty;
    }
}
