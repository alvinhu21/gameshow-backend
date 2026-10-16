package com.gameShows.live.session;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class CompletedGameTest {

    private static SessionPlayer player(String name, int score) {
        SessionPlayer player = new SessionPlayer(name, name, false);
        player.setScore(score);
        return player;
    }

    @Test
    void ranksBestScoreFirst() {
        CompletedGame game = CompletedGame.of(GameType.JEOPARDY, 2, Instant.EPOCH,
                List.of(player("Low", 100), player("High", 900), player("Mid", 400)));

        assertThat(game.standings())
                .extracting(CompletedGame.Standing::displayName, CompletedGame.Standing::place,
                        CompletedGame.Standing::tied)
                .containsExactly(tuple("High", 1, false), tuple("Mid", 2, false), tuple("Low", 3, false));
    }

    @Test
    void tiedScoresShareAPlaceAndSkipTheNext() {
        CompletedGame game = CompletedGame.of(GameType.JEOPARDY, 2, Instant.EPOCH,
                List.of(player("A", 500), player("B", 500), player("C", 200), player("D", 200), player("E", 0)));

        assertThat(game.standings())
                .extracting(CompletedGame.Standing::displayName, CompletedGame.Standing::place,
                        CompletedGame.Standing::tied)
                .containsExactly(tuple("A", 1, true), tuple("B", 1, true), tuple("C", 3, true), tuple("D", 3, true),
                        tuple("E", 5, false));
    }

    @Test
    void aTiebreakersWinnerFinishesAheadOfThoseTheyWereTiedWithWhoStillShareTheNextPlace() {
        CompletedGame game = CompletedGame.of(GameType.JEOPARDY, 2, Instant.EPOCH,
                List.of(player("A", 1000), player("B", 1000), player("C", 1000), player("D", 500)), "B");

        assertThat(game.standings())
                .extracting(CompletedGame.Standing::displayName, CompletedGame.Standing::place,
                        CompletedGame.Standing::tied)
                .containsExactly(tuple("B", 1, false), tuple("A", 2, true), tuple("C", 2, true), tuple("D", 4, false));
    }

    @Test
    void negativeScoresRankBelowZero() {
        CompletedGame game = CompletedGame.of(GameType.JEOPARDY, 2, Instant.EPOCH,
                List.of(player("Red", -400), player("Even", 0)));

        assertThat(game.standings()).extracting(CompletedGame.Standing::displayName).containsExactly("Even", "Red");
        assertThat(game.standings().get(1).place()).isEqualTo(2);
    }

    @Test
    void aiOpponentsHoldPlacesToo() {
        SessionPlayer bot = SessionPlayer.ai("ai-1", "Bot");
        bot.setScore(1000);

        CompletedGame game = CompletedGame.of(GameType.JEOPARDY, 2, Instant.EPOCH,
                List.of(player("Person", 600), bot));

        assertThat(game.standings())
                .extracting(CompletedGame.Standing::displayName, CompletedGame.Standing::ai,
                        CompletedGame.Standing::place)
                .containsExactly(tuple("Bot", true, 1), tuple("Person", false, 2));
    }
}
