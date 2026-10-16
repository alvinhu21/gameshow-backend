package com.gameShows.live.jeopardy.choices;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResponsePoolCatalogTest {

    /** The pools the game actually ships, read the same way the application reads them. */
    private static ResponsePoolCatalog shipped;

    @BeforeAll
    static void loadShippedPools() throws IOException {
        shipped = ResponsePoolCatalog.fromClasspath(new ObjectMapper());
    }

    @Test
    void shipsEveryStateCountryAndPresident() {
        assertThat(sizeOf("us-states")).isEqualTo(50);
        assertThat(sizeOf("countries")).isEqualTo(195);
        // 47 presidencies, 45 people: Cleveland and Trump each appear once.
        assertThat(sizeOf("us-presidents")).isEqualTo(45);
        assertThat(shipped.pools()).extracting(ResponsePool::id)
                .contains("world-capitals", "chemical-elements", "classical-composers");
    }

    @Test
    void everyShippedGroupCanFillAFullSetOfDistractorsOnItsOwn() {
        // So the four wrong options for any answer always come from its own region or era.
        for (ResponsePool pool : shipped.pools()) {
            Map<String, Long> groupSizes = pool.entries().stream()
                    .filter(entry -> entry.group() != null)
                    .collect(Collectors.groupingBy(ResponsePool.Entry::group, Collectors.counting()));
            assertThat(groupSizes).as("groups in %s", pool.id())
                    .allSatisfy((group, size) -> assertThat(size).as(group)
                            .isGreaterThanOrEqualTo(ResponsePoolCatalog.CHOICE_COUNT));
        }
    }

    @Test
    void recognisesAResponseHoweverTheClueSpellsIt() {
        assertMatch("Harry S Truman", "us-presidents", "Harry S. Truman");
        assertMatch("FDR", "us-presidents", "Franklin D. Roosevelt");
        assertMatch("george h.w. bush", "us-presidents", "George H. W. Bush");
        assertMatch("  new   york ", "us-states", "New York");
        assertMatch("Gambia", "countries", "The Gambia");
        assertMatch("Burma", "countries", "Myanmar");
        assertMatch("Cote d'Ivoire", "countries", "Ivory Coast");
        assertMatch("Brasilia", "world-capitals", "Brasília");
        assertMatch("Sana'a", "world-capitals", "Sanaa");
        assertMatch("Antonin Dvorak", "classical-composers", "Antonín Dvořák");
        assertMatch("Sulphur", "chemical-elements", "Sulfur");
    }

    @Test
    void leavesAResponseThatTwoPoolsClaimAsATypedAnswer() {
        // A state and a country - guessing which would show the wrong list half the time.
        assertThat(shipped.match("Georgia")).isEmpty();
        assertThat(shipped.entry(shipped.pool("us-states").orElseThrow(), "Georgia")).isPresent();
        assertThat(shipped.entry(shipped.pool("countries").orElseThrow(), "Georgia")).isPresent();
    }

    @Test
    void doesNotMatchAResponseThatIsNoPoolsMember() {
        assertThat(shipped.match("Vancouver Island")).isEmpty();
        assertThat(shipped.match("Korea")).isEmpty();
        assertThat(shipped.match("")).isEmpty();
        assertThat(shipped.match(null)).isEmpty();
    }

    @Test
    void refusesAPoolTooSmallToDealAFullSetOfChoices() {
        ResponsePool tiny = pool("tiny", entry("A"), entry("B"), entry("C"), entry("D"));

        assertThatThrownBy(() -> new ResponsePoolCatalog(List.of(tiny)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 5");
    }

    @Test
    void refusesTwoEntriesThatWouldReadAsTheSameResponse() {
        ResponsePool clash = pool("clash", entry("Ohio"), entry("Iowa", null, "OHIO"), entry("Utah"),
                entry("Idaho"), entry("Maine"));
        ResponsePool listedTwice = pool("twice", entry("Ohio"), entry("Ohio"), entry("Utah"),
                entry("Idaho"), entry("Maine"));

        assertThatThrownBy(() -> new ResponsePoolCatalog(List.of(clash))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new ResponsePoolCatalog(List.of(listedTwice)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refusesDuplicatePoolIdsAndTheIdReservedForTypedAnswers() {
        ResponsePool states = pool("states", entry("Ohio"), entry("Iowa"), entry("Utah"), entry("Idaho"),
                entry("Maine"));
        ResponsePool none = pool("none", entry("Ohio"), entry("Iowa"), entry("Utah"), entry("Idaho"),
                entry("Maine"));

        assertThatThrownBy(() -> new ResponsePoolCatalog(List.of(states, states)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new ResponsePoolCatalog(List.of(none))).isInstanceOf(IllegalStateException.class);
    }

    private static void assertMatch(String response, String poolId, String entryName) {
        ResponsePoolCatalog.Match match = shipped.match(response).orElseThrow(
                () -> new AssertionError("Expected '" + response + "' to match " + poolId));
        assertThat(match.pool().id()).isEqualTo(poolId);
        assertThat(match.entry().name()).isEqualTo(entryName);
    }

    private static int sizeOf(String poolId) {
        return shipped.pool(poolId).orElseThrow().entries().size();
    }

    private static ResponsePool pool(String id, ResponsePool.Entry... entries) {
        return new ResponsePool(id, null, List.of(entries));
    }

    private static ResponsePool.Entry entry(String name) {
        return entry(name, null);
    }

    private static ResponsePool.Entry entry(String name, String group, String... aliases) {
        return new ResponsePool.Entry(name, group, List.of(aliases));
    }
}
