package com.gameShows.live.session;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/**
 * A finished game's final standings, handed off to be recorded (see {@link CompletableGameEngine}).
 *
 * @param roundCount how many regular rounds the game was set up to play.
 * @param standings  every contestant - AI included, since they hold places too - best finish first.
 */
public record CompletedGame(GameType gameType, int roundCount, Instant completedAt, List<Standing> standings) {

    public CompletedGame {
        standings = List.copyOf(standings);
    }

    /**
     * One contestant's finish. Places use competition ranking: tied scores share a place and the
     * places they'd have taken are skipped (1, 1, 3), so a place always means "this many contestants
     * finished strictly ahead, plus one".
     */
    public record Standing(String displayName, boolean ai, int finalScore, int place, boolean tied) {
    }

    /** Ranks the players by their current score. */
    public static CompletedGame of(GameType gameType, int roundCount, Instant completedAt,
            List<SessionPlayer> players) {
        return of(gameType, roundCount, completedAt, players, null);
    }

    /**
     * Ranks the players by their current score - except that a tiebreaker's winner finishes ahead of
     * the contestants they were tied with, who still share the next place among themselves.
     *
     * @param tiebreakWinnerPlayerId who won a tiebreaker for the lead, or null if none was played.
     */
    public static CompletedGame of(GameType gameType, int roundCount, Instant completedAt,
            List<SessionPlayer> players, String tiebreakWinnerPlayerId) {
        BiPredicate<SessionPlayer, SessionPlayer> finishesAhead = (a, b) -> a.getScore() != b.getScore()
                ? a.getScore() > b.getScore()
                : a.getPlayerId().equals(tiebreakWinnerPlayerId) && !b.getPlayerId().equals(tiebreakWinnerPlayerId);
        List<Standing> standings = players.stream()
                .map(p -> new Standing(p.getDisplayName(), p.isAi(), p.getScore(),
                        1 + count(players, other -> finishesAhead.test(other, p)),
                        // Level with p: neither finishes ahead of the other (p itself included).
                        count(players, other -> !finishesAhead.test(other, p) && !finishesAhead.test(p, other)) > 1))
                .sorted(Comparator.comparingInt(Standing::place))
                .toList();
        return new CompletedGame(gameType, roundCount, completedAt, standings);
    }

    private static int count(List<SessionPlayer> players, Predicate<SessionPlayer> test) {
        return (int) players.stream().filter(test).count();
    }
}
