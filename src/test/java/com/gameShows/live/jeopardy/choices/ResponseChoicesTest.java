package com.gameShows.live.jeopardy.choices;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResponseChoicesTest {

    private final ResponseChoices choices = new ResponseChoices(
            List.of("Austria", "Australia", "Albania", "Andorra", "Armenia"), "Australia");

    @Test
    void onlyTheExactPickIsCorrect() {
        assertThat(choices.isCorrect("Australia")).isTrue();
        // Close enough for a typed answer, but here it's a different option entirely.
        assertThat(choices.isCorrect("Austria")).isFalse();
        assertThat(choices.isCorrect("Australi")).isFalse();
    }

    @Test
    void toleratesCaseAndStraySpacesFromTheClient() {
        assertThat(choices.isCorrect("  australia ")).isTrue();
        assertThat(choices.offers("ANDORRA")).isTrue();
    }

    @Test
    void knowsWhatWasNeverOnOffer() {
        assertThat(choices.offers("Germany")).isFalse();
        assertThat(choices.offers("")).isFalse();
        assertThat(choices.offers(null)).isFalse();
        assertThat(choices.isCorrect(null)).isFalse();
    }

    @Test
    void listsEveryOptionButTheRightOneAsWrong() {
        assertThat(choices.wrongOptions()).containsExactly("Austria", "Albania", "Andorra", "Armenia");
    }

    @Test
    void theRightAnswerMustBeAmongTheOptions() {
        assertThatThrownBy(() -> new ResponseChoices(List.of("Ohio", "Iowa"), "Utah"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
