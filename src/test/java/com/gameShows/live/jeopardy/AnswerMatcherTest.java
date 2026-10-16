package com.gameShows.live.jeopardy;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class AnswerMatcherTest {

    @Nested
    class OneResponse {

        @ParameterizedTest
        @ValueSource(strings = { "Cookies and Cream", "cookies & cream", "What is cookies and cream?",
                "Cookies an Cream" })
        void forgivesCaseThePhrasingAndATypo(String guess) {
            assertThat(AnswerMatcher.isCorrect(guess, "Cookies and Cream")).isTrue();
        }

        @Test
        void readsAnAmpersandInTheResponseAsAnd() {
            assertThat(AnswerMatcher.isCorrect("Simon and Garfunkel", "Simon & Garfunkel")).isTrue();
        }

        @Test
        void dropsAWhatsBeforeTheResponse() {
            assertThat(AnswerMatcher.isCorrect("What's the Alhambra?", "The Alhambra")).isTrue();
        }

        @Test
        void stillTurnsAwaySomethingElse() {
            assertThat(AnswerMatcher.isCorrect("Rocky Road", "Cookies and Cream")).isFalse();
            assertThat(AnswerMatcher.isCorrect("", "Cookies and Cream")).isFalse();
            assertThat(AnswerMatcher.isCorrect(null, "Cookies and Cream")).isFalse();
        }
    }

    @Nested
    class AnyOneOfSeveral {

        private final AnswerKey krone = AnswerKey.parse("{\"anyOf\": [\"Krone\", \"Krona\"]}");

        @ParameterizedTest
        @ValueSource(strings = { "Krone", "What is the krona?", "kroner" })
        void acceptsEitherResponse(String guess) {
            assertThat(AnswerMatcher.isCorrect(guess, krone)).isTrue();
        }

        @Test
        void doesNotAcceptHedgingBetweenThem() {
            assertThat(AnswerMatcher.isCorrect("Krone or Krona", krone)).isFalse();
            assertThat(AnswerMatcher.isCorrect("Krone / Krona", krone)).isFalse();
        }
    }

    @Nested
    class SeveralAllNeeded {

        private final AnswerKey ponyExpress = AnswerKey.parse(
                "{\"allOf\": [[\"St. Joseph\", \"Saint Joseph\"], \"Sacramento\"]}");

        @ParameterizedTest
        @ValueSource(strings = {
                "St. Joseph and Sacramento",
                "What are St. Joseph & Sacramento?",
                "Sacramento and Saint Joseph",
                "St Joseph, Sacramento",
                "sacramento,st. joseph",
                "St. Joseph / Sacramento",
                "St. Joseph，Sacramento",
                "St. Joseph、Sacramento",
                "St Joseph Sacramento",
                "St. Jospeh and Sacramneto" })
        void acceptsBothInAnyOrderHoweverTheyAreJoined(String guess) {
            assertThat(AnswerMatcher.isCorrect(guess, ponyExpress)).isTrue();
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "St. Joseph",
                "Sacramento",
                "St. Joseph and St. Joseph",
                "St. Joseph and Denver",
                "St. Joseph, Sacramento and Denver",
                "St. Joseph or Sacramento" })
        void rejectsAnythingButExactlyBoth(String guess) {
            assertThat(AnswerMatcher.isCorrect(guess, ponyExpress)).isFalse();
        }

        @Test
        void letsAPartContainAndItself() {
            AnswerKey plays = AnswerKey.parse("{\"allOf\": [\"Romeo and Juliet\", \"Hamlet\"]}");

            assertThat(AnswerMatcher.isCorrect("Hamlet and Romeo and Juliet", plays)).isTrue();
            assertThat(AnswerMatcher.isCorrect("Romeo & Juliet, Hamlet", plays)).isTrue();
            assertThat(AnswerMatcher.isCorrect("Romeo and Hamlet", plays)).isFalse();
        }

        @Test
        void dropsEachPartsArticle() {
            AnswerKey ranges = AnswerKey.parse("{\"allOf\": [\"The Alps\", \"The Andes\"]}");

            assertThat(AnswerMatcher.isCorrect("What are the Andes and the Alps?", ranges)).isTrue();
            assertThat(AnswerMatcher.isCorrect("the Alps, the Andes", ranges)).isTrue();
        }

        @Test
        void acceptsTheCanonicalResponseOfAnyKey() {
            assertThat(AnswerMatcher.isCorrect(ponyExpress.canonicalResponse(), ponyExpress)).isTrue();
        }

        @Test
        void dismissesAHugeGuessWithoutGrindingThroughIt() {
            String flood = "St. Joseph and Sacramento ".repeat(5_000);

            assertTimeoutPreemptively(Duration.ofSeconds(1),
                    () -> assertThat(AnswerMatcher.isCorrect(flood, ponyExpress)).isFalse());
        }
    }
}
