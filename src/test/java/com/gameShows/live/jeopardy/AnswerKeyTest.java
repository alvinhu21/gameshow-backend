package com.gameShows.live.jeopardy;

import com.gameShows.models.jeopardy.Clue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnswerKeyTest {

    @Test
    void anyOfIsOneResponseWithSeveralSpellings() {
        AnswerKey key = AnswerKey.parse("{\"anyOf\": [\"Krone\", \" Krona \"]}");

        assertThat(key.parts()).containsExactly(List.of("Krone", "Krona"));
        assertThat(key.requiredCount()).isEqualTo(1);
        assertThat(key.isSingleResponse()).isFalse();
        assertThat(key.canonicalResponse()).isEqualTo("Krone");
    }

    @Test
    void allOfIsOneRequiredResponsePerEntryAndAnInnerListGivesThatResponsesSpellings() {
        AnswerKey key = AnswerKey.parse("{\"allOf\": [[\"St. Joseph\", \"Saint Joseph\"], \"Sacramento\"]}");

        assertThat(key.parts()).containsExactly(List.of("St. Joseph", "Saint Joseph"), List.of("Sacramento"));
        assertThat(key.requiredCount()).isEqualTo(2);
        assertThat(key.isSingleResponse()).isFalse();
        assertThat(key.canonicalResponse()).isEqualTo("St. Joseph and Sacramento");
    }

    @Test
    void aPlainResponseIsASingleResponse() {
        AnswerKey key = AnswerKey.of("Sacramento");

        assertThat(key.isSingleResponse()).isTrue();
        assertThat(key.requiredCount()).isEqualTo(1);
        assertThat(key.canonicalResponse()).isEqualTo("Sacramento");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "not json",
            "[\"Krone\", \"Krona\"]",
            "{}",
            "{\"oneOf\": [\"Krone\"]}",
            "{\"anyOf\": [\"Krone\"], \"allOf\": [\"Krona\"]}",
            "{\"anyOf\": []}",
            "{\"anyOf\": \"Krone\"}",
            "{\"anyOf\": [\"Krone\", \"  \"]}",
            "{\"anyOf\": [\"Krone\", 7]}",
            "{\"allOf\": [\"Sacramento\", []]}",
            "{\"allOf\": [\"Sacramento\", [\"St. Joseph\", null]]}",
            "{\"allOf\": [\"1\", \"2\", \"3\", \"4\", \"5\", \"6\", \"7\", \"8\", \"9\"]}" })
    void turnsAwayAnythingThatDoesntSayExactlyWhatItMeans(String json) {
        assertThatThrownBy(() -> AnswerKey.parse(json)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aClueWithoutAcceptedResponsesIsJudgedByItsResponse() {
        AnswerKey key = AnswerKey.forClue(clue("Sacramento", null));

        assertThat(key).isEqualTo(AnswerKey.of("Sacramento"));
    }

    @Test
    void aClueWithAcceptedResponsesIsJudgedByThem() {
        AnswerKey key = AnswerKey.forClue(clue("Krone / Krona", "{\"anyOf\": [\"Krone\", \"Krona\"]}"));

        assertThat(key.parts()).containsExactly(List.of("Krone", "Krona"));
    }

    @Test
    void aClueWithUnreadableAcceptedResponsesFallsBackToItsResponse() {
        AnswerKey key = AnswerKey.forClue(clue("St. Joseph and Sacramento", "{\"allOf\": [\"St. Joseph\""));

        assertThat(key).isEqualTo(AnswerKey.of("St. Joseph and Sacramento"));
    }

    private static Clue clue(String response, String acceptedResponses) {
        Clue clue = new Clue();
        clue.setId(42L);
        clue.setResponse(response);
        clue.setAcceptedResponses(acceptedResponses);
        return clue;
    }
}
