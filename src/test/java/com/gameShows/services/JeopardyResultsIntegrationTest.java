package com.gameShows.services;

import com.gameShows.api.PageResponse;
import com.gameShows.api.PlayerResultView;
import com.gameShows.live.session.CompletedGame;
import com.gameShows.live.session.GameCompletedEvent;
import com.gameShows.live.session.GameType;
import com.gameShows.repositories.jeopardy.JeopardyGameResultRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Runs against real SQLite, in memory, so it exercises the actual dialect without touching jeopardy.db. */
@SpringBootTest(properties = {
        "spring.datasource.j.jdbc-url=jdbc:sqlite:file:resultsTestJeopardy?mode=memory&cache=shared",
        "spring.datasource.wof.jdbc-url=jdbc:sqlite:file:resultsTestWof?mode=memory&cache=shared" })
@AutoConfigureMockMvc
class JeopardyResultsIntegrationTest {

    private static final Instant PLAYED_AT = Instant.parse("2026-10-04T20:15:30Z");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JeopardyResultsService resultsService;
    @Autowired
    private JeopardyGameResultRepository gameResultRepository;
    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @BeforeEach
    void startEmpty() {
        gameResultRepository.deleteAll();
    }

    private static CompletedGame.Standing person(String name, int score, int place) {
        return new CompletedGame.Standing(name, false, score, place, false);
    }

    private static CompletedGame game(Instant completedAt, CompletedGame.Standing... standings) {
        return new CompletedGame(GameType.JEOPARDY, 2, completedAt, List.of(standings));
    }

    @Test
    void recordsOnlyThePeopleWithTheirPlacesAmongEveryone() {
        resultsService.recordGame("ABC123", game(PLAYED_AT,
                new CompletedGame.Standing("Bot", true, 1200, 1, false),
                person("Alice", 800, 2),
                person("Bob", -200, 3)));

        PageResponse<PlayerResultView> page = resultsService.getPlayerResults(0, 25);

        assertThat(page.totalItems()).isEqualTo(2);
        assertThat(page.items())
                .extracting(PlayerResultView::displayName, PlayerResultView::finalScore, PlayerResultView::place,
                        PlayerResultView::contestantCount, PlayerResultView::sessionCode, PlayerResultView::roundCount)
                .containsExactly(tuple("Alice", 800, 2, 3, "ABC123", 2), tuple("Bob", -200, 3, 3, "ABC123", 2));
        assertThat(page.items()).extracting(PlayerResultView::completedAt).containsOnly(PLAYED_AT);
        assertThat(page.items().get(0).gameId()).isEqualTo(page.items().get(1).gameId());
    }

    @Test
    void keepsTiesAndPlacesExactlyAsRanked() {
        resultsService.recordGame("TIE111", game(PLAYED_AT,
                new CompletedGame.Standing("Alice", false, 500, 1, true),
                new CompletedGame.Standing("Bot", true, 500, 1, true),
                person("Carol", 100, 3)));

        assertThat(resultsService.getPlayerResults(0, 25).items())
                .extracting(PlayerResultView::displayName, PlayerResultView::place, PlayerResultView::tied)
                .containsExactly(tuple("Alice", 1, true), tuple("Carol", 3, false));
    }

    @Test
    void listsTheNewestGameFirstAndPagesThroughEveryResult() {
        resultsService.recordGame("OLD111", game(PLAYED_AT, person("Old-1", 300, 1), person("Old-2", 100, 2)));
        resultsService.recordGame("NEW222", game(PLAYED_AT.plusSeconds(3600), person("New-2", 0, 2),
                person("New-1", 900, 1)));

        PageResponse<PlayerResultView> first = resultsService.getPlayerResults(0, 3);
        PageResponse<PlayerResultView> second = resultsService.getPlayerResults(1, 3);

        assertThat(first.items()).extracting(PlayerResultView::displayName)
                .containsExactly("New-1", "New-2", "Old-1");
        assertThat(second.items()).extracting(PlayerResultView::displayName).containsExactly("Old-2");
        assertThat(first.totalItems()).isEqualTo(4);
        assertThat(first.totalPages()).isEqualTo(2);
        assertThat(second.page()).isEqualTo(1);
    }

    @Test
    void anEmptyHistoryIsAnEmptyPage() {
        PageResponse<PlayerResultView> page = resultsService.getPlayerResults(0, 25);

        assertThat(page.items()).isEmpty();
        assertThat(page.totalItems()).isZero();
        assertThat(page.totalPages()).isZero();
    }

    @Test
    void aFinishedJeopardyGameIsRecordedWhenItsEventIsPublished() {
        eventPublisher.publishEvent(new GameCompletedEvent("EVT123", game(PLAYED_AT, person("Alice", 400, 1))));

        assertThat(resultsService.getPlayerResults(0, 25).items())
                .extracting(PlayerResultView::sessionCode, PlayerResultView::displayName)
                .containsExactly(tuple("EVT123", "Alice"));
    }

    @Test
    void otherGameTypesAreNotRecordedAsJeopardy() {
        eventPublisher.publishEvent(new GameCompletedEvent("WOF123",
                new CompletedGame(GameType.WOF, 3, PLAYED_AT, List.of(person("Alice", 400, 1)))));

        assertThat(resultsService.getPlayerResults(0, 25).items()).isEmpty();
    }

    @Test
    void servesResultsInThePagedEnvelope() throws Exception {
        resultsService.recordGame("ABC123", game(PLAYED_AT, person("Alice", 800, 1), person("Bob", 200, 2)));

        mockMvc.perform(get("/jeopardy/results").param("page", "0").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].displayName").value("Alice"))
                .andExpect(jsonPath("$.items[0].finalScore").value(800))
                .andExpect(jsonPath("$.items[0].place").value(1))
                .andExpect(jsonPath("$.items[0].contestantCount").value(2))
                .andExpect(jsonPath("$.items[0].sessionCode").value("ABC123"))
                .andExpect(jsonPath("$.items[0].completedAt").value("2026-10-04T20:15:30Z"))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.totalItems").value(2))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    void defaultsToTheFirstPage() throws Exception {
        mockMvc.perform(get("/jeopardy/results"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(JeopardyResultsService.DEFAULT_PAGE_SIZE));
    }

    @ParameterizedTest
    @CsvSource({ "page, -1", "size, 0", "size, 101", "page, abc" })
    void rejectsBadPagingAsABadRequest(String parameter, String value) throws Exception {
        mockMvc.perform(get("/jeopardy/results").param(parameter, value))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value(containsString(parameter)))
                .andExpect(jsonPath("$.path").value("/jeopardy/results"));
    }
}
