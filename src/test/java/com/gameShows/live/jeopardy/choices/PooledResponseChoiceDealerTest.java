package com.gameShows.live.jeopardy.choices;

import com.gameShows.models.jeopardy.Clue;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PooledResponseChoiceDealerTest {

    private static final Set<String> NEW_ENGLAND = Set.of("Maine", "Vermont", "New Hampshire", "Massachusetts",
            "Connecticut", "Rhode Island");

    private static final ResponsePool STATES = new ResponsePool("states", null, List.of(
            entry("Maine", "New England"),
            entry("Vermont", "New England"),
            entry("New Hampshire", "New England"),
            entry("Massachusetts", "New England", "Mass."),
            entry("Connecticut", "New England"),
            entry("Rhode Island", "New England"),
            entry("Ohio", "Midwest"),
            entry("Iowa", "Midwest"),
            entry("Kansas", "Midwest"),
            entry("Georgia", "South"),
            entry("Texas", "South")));

    private static final ResponsePool COUNTRIES = new ResponsePool("countries", null, List.of(
            entry("Georgia", "Caucasus"),
            entry("Armenia", "Caucasus"),
            entry("Azerbaijan", "Caucasus"),
            entry("France", "Western Europe"),
            entry("Spain", "Western Europe"),
            entry("Italy", "Western Europe")));

    private final ResponseChoiceDealer dealer =
            new PooledResponseChoiceDealer(new ResponsePoolCatalog(List.of(STATES, COUNTRIES)));

    @Test
    void dealsFiveDifferentOptionsOneOfThemTheRightAnswer() {
        ResponseChoices choices = deal(clue("Vermont"), 1);

        assertThat(choices.options()).hasSize(ResponsePoolCatalog.CHOICE_COUNT).doesNotHaveDuplicates()
                .contains("Vermont");
        assertThat(choices.correctOption()).isEqualTo("Vermont");
    }

    @Test
    void drawsTheWrongOptionsFromTheRightAnswersOwnRegion() {
        for (long seed = 0; seed < 25; seed++) {
            assertThat(deal(clue("Vermont"), seed).options()).allMatch(NEW_ENGLAND::contains);
        }
    }

    @Test
    void topsUpFromOtherRegionsWhenTheRightAnswersRegionRunsShort() {
        // Only two other Midwest states exist, so both appear and two more come from elsewhere.
        ResponseChoices choices = deal(clue("Ohio"), 7);

        assertThat(choices.options()).hasSize(ResponsePoolCatalog.CHOICE_COUNT).contains("Ohio", "Iowa", "Kansas");
    }

    @Test
    void writesTheRightAnswerTheWayThePoolDoesSoItDoesntStandOut() {
        ResponseChoices choices = deal(clue("mass."), 3);

        assertThat(choices.correctOption()).isEqualTo("Massachusetts");
        assertThat(choices.options()).contains("Massachusetts").doesNotContain("mass.");
    }

    @Test
    void shufflesTheRightAnswerIntoDifferentPositions() {
        Set<Integer> positions = new HashSet<>();
        for (long seed = 0; seed < 40; seed++) {
            positions.add(deal(clue("Vermont"), seed).options().indexOf("Vermont"));
        }
        assertThat(positions).hasSizeGreaterThan(1);
    }

    @Test
    void leavesAResponseInNoPoolAsATypedAnswer() {
        assertThat(dealer.deal(clue("Ottawa"), new Random(1))).isEmpty();
        assertThat(dealer.deal(clue(" "), new Random(1))).isEmpty();
        assertThat(dealer.deal(clue(null), new Random(1))).isEmpty();
    }

    @Test
    void aResponseTwoPoolsClaimIsTypedUnlessTheClueNamesItsPool() {
        assertThat(dealer.deal(clue("Georgia"), new Random(1))).isEmpty();

        assertThat(deal(clue("Georgia", "states"), 2).options()).contains("Georgia", "Texas");
        assertThat(deal(clue("Georgia", "countries"), 2).options()).contains("Georgia", "Armenia", "Azerbaijan");
    }

    @Test
    void aClueMarkedNoneStaysTypedEvenThoughItsResponseMatches() {
        assertThat(dealer.deal(clue("Vermont", "none"), new Random(1))).isEmpty();
        assertThat(dealer.deal(clue("Vermont", " NONE "), new Random(1))).isEmpty();
    }

    @Test
    void aForcedPoolOffersAResponseItDoesntListExactlyAsTheClueWroteIt() {
        ResponseChoices choices = deal(clue("The Green Mountain State", "states"), 4);

        assertThat(choices.correctOption()).isEqualTo("The Green Mountain State");
        assertThat(choices.options()).hasSize(ResponsePoolCatalog.CHOICE_COUNT).doesNotHaveDuplicates();
        assertThat(choices.wrongOptions()).allMatch(option -> STATES.entries().stream()
                .anyMatch(entry -> entry.name().equals(option)));
    }

    @Test
    void anUnknownPoolNameFallsBackToATypedAnswer() {
        assertThat(dealer.deal(clue("Vermont", "provinces"), new Random(1))).isEmpty();
    }

    private ResponseChoices deal(Clue clue, long seed) {
        return dealer.deal(clue, new Random(seed)).orElseThrow(
                () -> new AssertionError("Expected '" + clue.getResponse() + "' to be answered from a list"));
    }

    private static Clue clue(String response) {
        return clue(response, null);
    }

    private static Clue clue(String response, String responsePool) {
        Clue clue = new Clue();
        clue.setCategory("STATES OF MIND");
        clue.setClueText("A clue");
        clue.setResponse(response);
        clue.setResponsePool(responsePool);
        return clue;
    }

    private static ResponsePool.Entry entry(String name, String group, String... aliases) {
        return new ResponsePool.Entry(name, group, List.of(aliases));
    }
}
