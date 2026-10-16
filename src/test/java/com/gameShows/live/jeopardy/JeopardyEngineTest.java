package com.gameShows.live.jeopardy;

import com.gameShows.live.error.InvalidActionException;
import com.gameShows.live.jeopardy.choices.ResponseChoiceDealer;
import com.gameShows.live.jeopardy.choices.ResponseChoices;
import com.gameShows.live.session.AiDifficulty;
import com.gameShows.live.session.AiMove;
import com.gameShows.live.session.CompletedGame;
import com.gameShows.live.session.GameSession;
import com.gameShows.live.session.GameType;
import com.gameShows.live.session.LiveActionOutcome;
import com.gameShows.live.session.SessionPlayer;
import com.gameShows.models.jeopardy.Clue;
import com.gameShows.services.JeopardyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JeopardyEngineTest {

    private static final int CATEGORIES_PER_ROUND_BOARD = 6;
    /** Two rounds' worth - the default game shape most of these tests use. */
    private static final int CATEGORIES = 2 * CATEGORIES_PER_ROUND_BOARD;
    private static final int CLUES_PER_CATEGORY = 5;

    private SessionPlayer p1;
    private SessionPlayer p2;
    private SessionPlayer p3;
    private JeopardyService jeopardyService;

    @BeforeEach
    void setUp() {
        p1 = new SessionPlayer("p1", "Alice", true);
        p2 = new SessionPlayer("p2", "Bob", false);
        p3 = new SessionPlayer("p3", "Carol", false);
        jeopardyService = mock(JeopardyService.class);
        when(jeopardyService.getAllClues()).thenReturn(allClues());
    }

    /** Sizes the clue pool to exactly {@code rounds} boards' worth of five-clue categories, so every
     *  one of them is consumed by the time Final Jeopardy picks its category and FINALCAT (the
     *  1-clue leftover) is the only thing left - the same shape the real jeopardy.db has, just
     *  trimmed so the final category is deterministic. */
    private void categoryPoolFor(int rounds) {
        when(jeopardyService.getAllClues()).thenReturn(allClues(rounds * CATEGORIES_PER_ROUND_BOARD));
    }

    private List<Clue> allClues() {
        return allClues(CATEGORIES);
    }

    private List<Clue> allClues(int categories) {
        List<Clue> clues = new ArrayList<>();
        for (int cat = 1; cat <= categories; cat++) {
            for (int row = 0; row < CLUES_PER_CATEGORY; row++) {
                clues.add(clue("CAT" + cat, "Clue " + cat + "-" + row, "response" + cat + "-" + row));
            }
        }
        clues.add(clue("FINALCAT", "The final clue", "final response"));
        return clues;
    }

    private Clue clue(String category, String clueText, String response) {
        Clue c = new Clue();
        c.setCategory(category);
        c.setClueText(clueText);
        c.setResponse(response);
        return c;
    }

    private JeopardyEngine engine() {
        return engine(List.of(p1, p2, p3));
    }

    /** Default two-round, $100-ladder game. AI is pinned to HARD so the bot-behaviour tests stay
     *  deterministic - the weaker difficulties are deliberately random and get their own tests. */
    private JeopardyEngine engine(List<SessionPlayer> players) {
        return new JeopardyEngine(players, jeopardyService, JeopardyEngine.DEFAULT_ROUNDS,
                JeopardyEngine.DEFAULT_CLUE_BASE_INCREMENT, AiDifficulty.HARD);
    }

    private BoardCell firstNonDailyDoubleCell(JeopardyEngine engine) {
        return engine.boardForTesting().stream().filter(c -> !c.isDailyDouble()).findFirst().orElseThrow();
    }

    private BoardCell dailyDoubleCell(JeopardyEngine engine) {
        return engine.boardForTesting().stream().filter(BoardCell::isDailyDouble).findFirst().orElseThrow();
    }

    @Test
    void buzzRaceFirstBuzzWinsAndOthersAreRejected() {
        JeopardyEngine engine = engine();
        BoardCell cell = firstNonDailyDoubleCell(engine);
        engine.selectClue("p1", cell.getCellId());

        engine.buzz("p2");

        assertThatThrownBy(() -> engine.buzz("p3")).isInstanceOf(InvalidActionException.class);
        assertThatThrownBy(() -> engine.buzz("p1")).isInstanceOf(InvalidActionException.class);
    }

    @Test
    void onlyPlayerInControlMaySelectAClue() {
        JeopardyEngine engine = engine();
        BoardCell cell = firstNonDailyDoubleCell(engine);

        assertThatThrownBy(() -> engine.selectClue("p2", cell.getCellId())).isInstanceOf(InvalidActionException.class);
    }

    @Test
    void wrongAnswerLosesPointsAndReopensBuzzThenCorrectAnswerAwardsControl() {
        JeopardyEngine engine = engine();
        BoardCell cell = firstNonDailyDoubleCell(engine);
        engine.selectClue("p1", cell.getCellId());

        engine.buzz("p2");
        LiveActionOutcome wrongOutcome = engine.submitAnswer("p2", "not even close");
        assertThat(p2.getScore()).isEqualTo(-cell.getValue());
        JeopardyStateSnapshot afterWrong = (JeopardyStateSnapshot) wrongOutcome.snapshot();
        assertThat(afterWrong.clueSubPhase()).isEqualTo("BUZZ_OPEN");

        engine.buzz("p3");
        LiveActionOutcome correctOutcome = engine.submitAnswer("p3", cell.getClue().getResponse());

        assertThat(p3.getScore()).isEqualTo(cell.getValue());
        JeopardyStateSnapshot afterCorrect = (JeopardyStateSnapshot) correctOutcome.snapshot();
        assertThat(afterCorrect.playerIdInControl()).isEqualTo("p3");
        assertThat(cell.getState()).isEqualTo(CellState.ANSWERED);
    }

    @Test
    void everyoneLockedOutRevealsAnswerWithoutAnyoneScoring() {
        JeopardyEngine engine = engine();
        BoardCell cell = firstNonDailyDoubleCell(engine);
        engine.selectClue("p1", cell.getCellId());

        engine.buzz("p1");
        engine.submitAnswer("p1", "wrong");
        engine.buzz("p2");
        engine.submitAnswer("p2", "also wrong");
        engine.buzz("p3");
        LiveActionOutcome finalWrong = engine.submitAnswer("p3", "still wrong");

        assertThat(cell.getState()).isEqualTo(CellState.ANSWERED);
        JeopardyStateSnapshot snap = (JeopardyStateSnapshot) finalWrong.snapshot();
        assertRevealing(snap, cell, ClueRevealReason.ALL_INCORRECT);
        assertThat(snap.activeAnswererId()).isNull();
        assertThat(snap.playerIdInControl()).isEqualTo("p1");

        JeopardyStateSnapshot board = (JeopardyStateSnapshot) engine.finishReveal(engine.getVersion()).snapshot();
        assertThat(board.phase()).isEqualTo("BOARD");
        assertThat(board.activeCell()).isNull();
        assertThat(board.playerIdInControl()).isEqualTo("p1");
    }

    @Test
    void dailyDoubleWagerHasFiveDollarFloorWhenBroke() {
        JeopardyEngine engine = engine();
        BoardCell dd = dailyDoubleCell(engine);
        p1.setScore(0);

        engine.selectClue("p1", dd.getCellId());

        assertThatThrownBy(() -> engine.submitDailyDoubleWager("p1", 4)).isInstanceOf(InvalidActionException.class);

        LiveActionOutcome outcome = engine.submitDailyDoubleWager("p1", 5);
        JeopardyStateSnapshot publicSnap = (JeopardyStateSnapshot) outcome.snapshot();
        assertThat(publicSnap.activeCell().clueText()).isNull();
        assertThat(publicSnap.activeCell().dailyDoubleInProgress()).isTrue();

        JeopardyStateSnapshot privateSnap = (JeopardyStateSnapshot) outcome.privateMessages().get("p1");
        assertThat(privateSnap.activeCell().clueText()).isEqualTo(dd.getClue().getClueText());
    }

    @Test
    void dailyDoubleAnswerAppliesWagerDeltaAndAllowsNegativeScore() {
        JeopardyEngine engine = engine();
        BoardCell dd = dailyDoubleCell(engine);
        p1.setScore(0);
        engine.selectClue("p1", dd.getCellId());
        engine.submitDailyDoubleWager("p1", 5);

        engine.submitDailyDoubleAnswer("p1", "definitely wrong");

        assertThat(p1.getScore()).isEqualTo(-5);
        assertThat(dd.getState()).isEqualTo(CellState.ANSWERED);
    }

    @Test
    void finalJeopardyWagerCannotExceedWhatThePlayerHas() {
        JeopardyEngine engine = clearAllRoundsWithEveryoneInTheBlack();

        assertThatThrownBy(() -> engine.submitFinalWager("p2", playerScore(engine, "p2") + 1))
                .isInstanceOf(InvalidActionException.class);

        engine.submitFinalWager("p2", 0);
    }

    @Test
    void contestantsWhoDontFinishInTheBlackAreLockedOutOfFinalJeopardy() {
        // Only p1 ever answers correctly, so p2 and p3 end the last round on $0.
        JeopardyEngine engine = clearAllRounds(engine(), new String[] { "p1" });
        assertThat(engine.getPhase()).isEqualTo(JeopardyPhase.FINAL_WAGER);

        JeopardyStateSnapshot snap = engine.toSnapshot(null);
        assertThat(snap.finalEligiblePlayerIds()).containsExactly("p1");
        assertThatThrownBy(() -> engine.submitFinalWager("p2", 0)).isInstanceOf(InvalidActionException.class);
        assertThatThrownBy(() -> engine.submitFinalWager("p3", 0)).isInstanceOf(InvalidActionException.class);

        // The one qualifying player is enough to move the round along on their own.
        engine.submitFinalWager("p1", 100);
        assertThat(engine.getPhase()).isEqualTo(JeopardyPhase.FINAL_ANSWER_COLLECTION);
        LiveActionOutcome reveal = engine.submitFinalAnswer("p1", "final response");

        JeopardyStateSnapshot finalSnap = (JeopardyStateSnapshot) reveal.snapshot();
        assertThat(finalSnap.phase()).isEqualTo("GAME_OVER");
        assertThat(finalSnap.finalResults()).extracting(FinalResultEntry::playerId).containsExactly("p1");
    }

    @Test
    void aPlayerInTheRedIsLockedOutOfFinalJeopardyToo() {
        JeopardyEngine engine = engine();
        // p2 buzzes in wrong on the very first clue and never recovers, finishing in the red.
        BoardCell first = firstNonDailyDoubleCell(engine);
        engine.selectClue("p1", first.getCellId());
        engine.buzz("p2");
        engine.submitAnswer("p2", "wrong");
        engine.buzz("p1");
        engine.submitAnswer("p1", first.getClue().getResponse());
        clearAllRounds(engine, new String[] { "p1", "p3" });

        assertThat(p2.getScore()).isNegative();
        assertThat(engine.toSnapshot(null).finalEligiblePlayerIds()).doesNotContain("p2");
    }

    @Test
    void gameEndsWithoutAFinalRoundWhenNobodyFinishesInTheBlack() {
        JeopardyEngine engine = engine();
        // Nobody ever rings in: every clue times out unanswered, so all three finish on $0.
        while (engine.getPhase() == JeopardyPhase.BOARD) {
            BoardCell next = engine.boardForTesting().stream()
                    .filter(c -> c.getState() == CellState.HIDDEN)
                    .findFirst()
                    .orElse(null);
            if (next == null) {
                break;
            }
            String controller = engine.toSnapshot(null).playerIdInControl();
            engine.selectClue(controller, next.getCellId());
            if (next.isDailyDouble()) {
                engine.submitDailyDoubleWager(controller, 5);
                engine.submitDailyDoubleAnswer(controller, "wrong");
            } else {
                engine.handleBuzzTimeout(engine.getVersion());
            }
            engine.finishReveal(engine.getVersion());
        }

        JeopardyStateSnapshot snap = engine.toSnapshot(null);
        assertThat(snap.phase()).isEqualTo("GAME_OVER");
        assertThat(snap.finalEligiblePlayerIds()).isEmpty();
        assertThat(snap.finalResults()).isNull();
        assertThat(snap.gameWinnerPlayerIds()).isNotEmpty();
        assertThat(engine.claimCompletedGame()).as("a game with no Final is still recorded").isPresent();
    }

    @Test
    void aFinishedGameIsHandedOffForRecordingExactlyOnce() {
        JeopardyEngine engine = clearAllRoundsWithEveryoneInTheBlack();
        assertThat(engine.claimCompletedGame()).as("Final Jeopardy is still to come").isEmpty();
        for (String playerId : List.of("p1", "p2", "p3")) {
            engine.submitFinalWager(playerId, 0);
        }
        for (String playerId : List.of("p1", "p2", "p3")) {
            engine.submitFinalAnswer(playerId, "final response");
        }
        assertThat(engine.getPhase()).isEqualTo(JeopardyPhase.GAME_OVER);

        CompletedGame game = engine.claimCompletedGame().orElseThrow();

        assertThat(game.gameType()).isEqualTo(GameType.JEOPARDY);
        assertThat(game.roundCount()).isEqualTo(JeopardyEngine.DEFAULT_ROUNDS);
        assertThat(game.standings()).extracting(CompletedGame.Standing::displayName)
                .containsExactlyInAnyOrder("Alice", "Bob", "Carol");
        assertThat(game.standings()).extracting(CompletedGame.Standing::finalScore)
                .containsExactlyInAnyOrder(p1.getScore(), p2.getScore(), p3.getScore())
                .isSortedAccordingTo(Comparator.reverseOrder());
        assertThat(engine.claimCompletedGame()).as("only ever handed off once").isEmpty();
    }

    @Test
    void clueNobodyRingsInOnShowsItsCorrectResponseThenTheBoardComesBack() {
        JeopardyEngine engine = engine();
        BoardCell cell = firstNonDailyDoubleCell(engine);
        engine.selectClue("p1", cell.getCellId());

        LiveActionOutcome outcome = engine.handleBuzzTimeout(engine.getVersion());

        assertThat(cell.getState()).isEqualTo(CellState.ANSWERED);
        JeopardyStateSnapshot snap = (JeopardyStateSnapshot) outcome.snapshot();
        assertRevealing(snap, cell, ClueRevealReason.TIME_EXPIRED);
        assertThat(snap.activeCell().clueText()).isEqualTo(cell.getClue().getClueText());
        assertThat(snap.phaseDeadlineAt()).as("no countdown while the response is up").isNull();
        assertThat(p1.getScore()).isZero();
        assertThat(outcome.announceText()).contains(cell.getClue().getResponse());

        LiveActionOutcome backToBoard = engine.finishReveal(engine.getVersion());

        JeopardyStateSnapshot board = (JeopardyStateSnapshot) backToBoard.snapshot();
        assertThat(board.phase()).isEqualTo("BOARD");
        assertThat(board.clueSubPhase()).isEqualTo("NONE");
        assertThat(board.activeCell()).isNull();
        assertThat(backToBoard.announceText()).as("nothing new to announce").isNull();
    }

    @Test
    void aClueThatTimesOutAfterAMissStillShowsItsResponse() {
        JeopardyEngine engine = engine();
        BoardCell cell = firstNonDailyDoubleCell(engine);
        engine.selectClue("p1", cell.getCellId());
        engine.buzz("p2");
        engine.submitAnswer("p2", "wrong");

        JeopardyStateSnapshot snap = (JeopardyStateSnapshot) engine.handleBuzzTimeout(engine.getVersion()).snapshot();

        assertRevealing(snap, cell, ClueRevealReason.TIME_EXPIRED);
        assertThat(snap.lockedOutPlayerIds()).containsExactly("p2");
    }

    @Test
    void theCorrectResponseNeverReachesAnyoneWhileTheClueCanStillBeAnswered() {
        JeopardyEngine engine = engine();
        BoardCell cell = firstNonDailyDoubleCell(engine);

        JeopardyStateSnapshot open = (JeopardyStateSnapshot) engine.selectClue("p1", cell.getCellId()).snapshot();
        JeopardyStateSnapshot answering = (JeopardyStateSnapshot) engine.buzz("p2").snapshot();
        JeopardyStateSnapshot reopened = (JeopardyStateSnapshot) engine.submitAnswer("p2", "wrong").snapshot();

        for (JeopardyStateSnapshot snap : List.of(open, answering, reopened, engine.toSnapshot("p3"))) {
            assertThat(snap.activeCell().correctResponse()).isNull();
            assertThat(snap.activeCell().revealReason()).isNull();
        }
    }

    @Test
    void aDailyDoublesResponseStaysHiddenFromItsFinderUntilTheyHaveAnswered() {
        JeopardyEngine engine = engine();
        BoardCell dd = dailyDoubleCell(engine);
        p1.setScore(500);

        engine.selectClue("p1", dd.getCellId());
        LiveActionOutcome wagered = engine.submitDailyDoubleWager("p1", 100);

        JeopardyStateSnapshot findersView = (JeopardyStateSnapshot) wagered.privateMessages().get("p1");
        assertThat(findersView.activeCell().clueText()).isEqualTo(dd.getClue().getClueText());
        assertThat(findersView.activeCell().correctResponse()).isNull();
        assertThat(((JeopardyStateSnapshot) wagered.snapshot()).activeCell().correctResponse()).isNull();
    }

    @Test
    void aMissedDailyDoubleShowsEveryoneTheClueAndItsCorrectResponse() {
        JeopardyEngine engine = engine();
        BoardCell dd = dailyDoubleCell(engine);
        p1.setScore(500);
        engine.selectClue("p1", dd.getCellId());
        engine.submitDailyDoubleWager("p1", 300);

        LiveActionOutcome missed = engine.submitDailyDoubleAnswer("p1", "definitely wrong");

        assertThat(p1.getScore()).isEqualTo(200);
        JeopardyStateSnapshot snap = (JeopardyStateSnapshot) missed.snapshot();
        assertRevealing(snap, dd, ClueRevealReason.DAILY_DOUBLE_INCORRECT);
        assertThat(snap.activeCell().clueText()).as("everyone finally sees the clue").isEqualTo(dd.getClue().getClueText());
        assertThat(snap.activeCell().dailyDoubleInProgress()).isFalse();
        assertThat(snap.playerIdInControl()).as("the finder keeps the board").isEqualTo("p1");
        assertThat(missed.announceText()).contains(dd.getClue().getResponse());

        JeopardyStateSnapshot board = (JeopardyStateSnapshot) engine.finishReveal(engine.getVersion()).snapshot();
        assertThat(board.phase()).isEqualTo("BOARD");
        assertThat(board.playerIdInControl()).isEqualTo("p1");
    }

    @Test
    void aDailyDoubleAnsweredCorrectlyGoesStraightBackToTheBoard() {
        JeopardyEngine engine = engine();
        BoardCell dd = dailyDoubleCell(engine);
        p1.setScore(500);
        engine.selectClue("p1", dd.getCellId());
        engine.submitDailyDoubleWager("p1", 300);

        JeopardyStateSnapshot snap = (JeopardyStateSnapshot) engine
                .submitDailyDoubleAnswer("p1", dd.getClue().getResponse()).snapshot();

        assertThat(p1.getScore()).isEqualTo(800);
        assertThat(snap.phase()).isEqualTo("BOARD");
        assertThat(snap.activeCell()).isNull();
        assertThat(engine.pendingAiMoves()).isEmpty();
    }

    @Test
    void aRevealedResponseTakesTheBoardBackOnItsOwnAfterTheRevealWindow() {
        JeopardyEngine engine = engine();
        GameSession session = new GameSession("REVEAL", GameType.JEOPARDY, p1.getPlayerId());
        session.setEngine(engine);
        BoardCell cell = firstNonDailyDoubleCell(engine);
        engine.selectClue("p1", cell.getCellId());
        engine.handleBuzzTimeout(engine.getVersion());

        List<AiMove> moves = engine.pendingAiMoves();

        assertThat(moves).extracting(AiMove::delay).containsExactly(JeopardyEngine.REVEAL_WINDOW);
        assertThat(engine.pendingAiMoves()).as("armed once per reveal, however often it's asked").isEmpty();
        moves.get(0).invoke().apply(session);
        assertThat(engine.getPhase()).isEqualTo(JeopardyPhase.BOARD);
    }

    @Test
    void nobodyCanActWhileAResponseIsRevealed() {
        JeopardyEngine engine = engine();
        BoardCell cell = firstNonDailyDoubleCell(engine);
        BoardCell another = engine.boardForTesting().stream()
                .filter(c -> c != cell && !c.isDailyDouble()).findFirst().orElseThrow();
        engine.selectClue("p1", cell.getCellId());
        engine.handleBuzzTimeout(engine.getVersion());
        long revealVersion = engine.getVersion();

        assertThatThrownBy(() -> engine.buzz("p2")).isInstanceOf(InvalidActionException.class);
        assertThatThrownBy(() -> engine.submitAnswer("p2", cell.getClue().getResponse()))
                .isInstanceOf(InvalidActionException.class);
        assertThatThrownBy(() -> engine.selectClue("p1", another.getCellId()))
                .isInstanceOf(InvalidActionException.class);
        assertThat(engine.handleBuzzTimeout(revealVersion)).isNull();

        // Nothing moved, so the scheduled end of the reveal still lands.
        assertThat(engine.getVersion()).isEqualTo(revealVersion);
        assertThat(engine.finishReveal(revealVersion)).isNotNull();
    }

    @Test
    void aStaleEndOfRevealIsANoOp() {
        JeopardyEngine engine = engine();
        BoardCell cell = firstNonDailyDoubleCell(engine);
        engine.selectClue("p1", cell.getCellId());
        engine.handleBuzzTimeout(engine.getVersion());
        long revealVersion = engine.getVersion();
        engine.finishReveal(revealVersion);

        assertThat(engine.finishReveal(revealVersion)).isNull();
        assertThat(engine.finishReveal(engine.getVersion())).as("not revealing anything").isNull();
    }

    @Test
    void theNextRoundOnlyStartsOnceTheLastCluesResponseHasBeenShown() {
        JeopardyEngine engine = engine();
        // Sweep every clue but one, so the round ends on a clue nobody rings in on.
        while (engine.boardForTesting().stream().filter(c -> c.getState() == CellState.HIDDEN).count() > 1) {
            BoardCell next = engine.boardForTesting().stream()
                    .filter(c -> c.getState() == CellState.HIDDEN).findFirst().orElseThrow();
            String controller = engine.toSnapshot(null).playerIdInControl();
            engine.selectClue(controller, next.getCellId());
            if (next.isDailyDouble()) {
                engine.submitDailyDoubleWager(controller, 5);
                engine.submitDailyDoubleAnswer(controller, next.getClue().getResponse());
            } else {
                engine.buzz(controller);
                engine.submitAnswer(controller, next.getClue().getResponse());
            }
        }
        BoardCell last = engine.boardForTesting().stream()
                .filter(c -> c.getState() == CellState.HIDDEN).findFirst().orElseThrow();
        String controller = engine.toSnapshot(null).playerIdInControl();
        engine.selectClue(controller, last.getCellId());
        if (last.isDailyDouble()) {
            engine.submitDailyDoubleWager(controller, 5);
            engine.submitDailyDoubleAnswer(controller, "wrong");
        } else {
            engine.handleBuzzTimeout(engine.getVersion());
        }

        assertThat(engine.toSnapshot(null).currentRound()).as("still showing round 1's last clue").isEqualTo(1);

        LiveActionOutcome next = engine.finishReveal(engine.getVersion());

        assertThat(engine.toSnapshot(null).currentRound()).isEqualTo(2);
        assertThat(engine.getPhase()).isEqualTo(JeopardyPhase.BOARD);
        assertThat(next.announceText()).isEqualTo("The Jeopardy Round complete! Double Jeopardy begins.");
    }

    /** The clue is still up, now showing its correct response and why it's being shown. */
    private static void assertRevealing(JeopardyStateSnapshot snap, BoardCell cell, ClueRevealReason reason) {
        assertThat(snap.phase()).isEqualTo("CLUE_OPEN");
        assertThat(snap.clueSubPhase()).isEqualTo("REVEALED");
        assertThat(snap.activeCell().cellId()).isEqualTo(cell.getCellId());
        assertThat(snap.activeCell().correctResponse()).isEqualTo(cell.getClue().getResponse());
        assertThat(snap.activeCell().revealReason()).isEqualTo(reason.name());
    }

    @Test
    void buzzTimeoutIsANoOpOnceSomeoneHasRungIn() {
        JeopardyEngine engine = engine();
        BoardCell cell = firstNonDailyDoubleCell(engine);
        engine.selectClue("p1", cell.getCellId());
        long versionAtOpen = engine.getVersion();
        engine.buzz("p2");

        assertThat(engine.handleBuzzTimeout(versionAtOpen)).isNull();
        assertThat(engine.getActiveAnswererId()).isEqualTo("p2");
    }

    @Test
    void timedWindowsReportWhenTheyOpenedAndClearOnceTheClueCloses() {
        JeopardyEngine engine = engine();
        BoardCell cell = firstNonDailyDoubleCell(engine);

        JeopardyStateSnapshot buzzOpen = (JeopardyStateSnapshot) engine.selectClue("p1", cell.getCellId()).snapshot();
        assertThat(Duration.between(buzzOpen.phaseStartedAt(), buzzOpen.phaseDeadlineAt()))
                .isEqualTo(JeopardyEngine.BUZZ_WINDOW);

        JeopardyStateSnapshot answering = (JeopardyStateSnapshot) engine.buzz("p2").snapshot();
        assertThat(Duration.between(answering.phaseStartedAt(), answering.phaseDeadlineAt()))
                .isEqualTo(JeopardyEngine.ANSWER_WINDOW);

        JeopardyStateSnapshot backToBoard = (JeopardyStateSnapshot) engine
                .submitAnswer("p2", cell.getClue().getResponse()).snapshot();
        assertThat(backToBoard.phaseStartedAt()).isNull();
        assertThat(backToBoard.phaseDeadlineAt()).isNull();
    }

    @Test
    void finalJeopardyHidesSubmissionsUntilEveryoneHasAnsweredThenRevealsAllAtOnce() {
        JeopardyEngine engine = clearAllRoundsWithEveryoneInTheBlack();
        assertThat(engine.getPhase()).isEqualTo(JeopardyPhase.FINAL_WAGER);
        assertThat(engine.toSnapshot(null).finalEligiblePlayerIds())
                .containsExactlyInAnyOrder("p1", "p2", "p3");

        engine.submitFinalWager("p1", 100);
        LiveActionOutcome afterTwoWagers = engine.submitFinalWager("p2", 0);
        JeopardyStateSnapshot snapTwo = (JeopardyStateSnapshot) afterTwoWagers.snapshot();
        assertThat(snapTwo.finalResults()).isNull();
        assertThat(snapTwo.finalWagerSubmittedPlayerIds()).hasSize(2);
        assertThat(snapTwo.finalClueText()).isNull(); // wagers are made knowing only the category

        LiveActionOutcome afterAllWagers = engine.submitFinalWager("p3", 0);
        assertThat(engine.getPhase()).isEqualTo(JeopardyPhase.FINAL_ANSWER_COLLECTION);
        JeopardyStateSnapshot snapAllWagers = (JeopardyStateSnapshot) afterAllWagers.snapshot();
        assertThat(snapAllWagers.activeCell()).isNull(); // Final Jeopardy has its own fields, not a board cell
        assertThat(snapAllWagers.finalCategory()).isEqualTo("FINALCAT");
        assertThat(snapAllWagers.finalClueText()).isEqualTo("The final clue");
        assertThat(snapAllWagers.finalResponseChoices()).isNull(); // a typed answer

        engine.submitFinalAnswer("p1", "final response");
        LiveActionOutcome afterTwoAnswers = engine.submitFinalAnswer("p2", "final response");
        JeopardyStateSnapshot snapTwoAnswers = (JeopardyStateSnapshot) afterTwoAnswers.snapshot();
        assertThat(snapTwoAnswers.finalResults()).isNull();

        int p1Before = p1.getScore();
        int p3Before = p3.getScore();
        LiveActionOutcome revealOutcome = engine.submitFinalAnswer("p3", "not even close");

        JeopardyStateSnapshot reveal = (JeopardyStateSnapshot) revealOutcome.snapshot();
        assertThat(reveal.phase()).isEqualTo("GAME_OVER");
        assertThat(reveal.finalResults()).hasSize(3);
        assertThat(reveal.gameWinnerPlayerIds()).isNotEmpty();
        assertThat(p1.getScore()).isEqualTo(p1Before + 100); // wagered 100 and got it right
        assertThat(p3.getScore()).isEqualTo(p3Before); // wagered 0, so wrong costs nothing
    }

    @Test
    void aiInControlSelectsTheHighestValueHiddenCell() {
        SessionPlayer aiPlayer = SessionPlayer.ai("ai1", "Watson (AI)");
        JeopardyEngine engine = engine(List.of(aiPlayer, p2, p3));
        GameSession session = new GameSession("TESTJ1", GameType.JEOPARDY, aiPlayer.getPlayerId());
        session.setEngine(engine);

        List<AiMove> moves = engine.pendingAiMoves();
        assertThat(moves).hasSize(1);
        moves.get(0).invoke().apply(session);

        BoardCell selected = engine.boardForTesting().stream()
                .filter(c -> c.getState() != CellState.HIDDEN)
                .findFirst().orElseThrow();
        int topValue = engine.boardForTesting().stream().mapToInt(BoardCell::getValue).max().orElseThrow();
        assertThat(selected.getValue()).isEqualTo(topValue);
    }

    @Test
    void aiBuzzesInAndAnswersCorrectlyWhenNoOneElseDoes() {
        SessionPlayer aiPlayer = SessionPlayer.ai("ai1", "Watson (AI)");
        JeopardyEngine engine = engine(List.of(p1, aiPlayer, p3));
        GameSession session = new GameSession("TESTJ2", GameType.JEOPARDY, p1.getPlayerId());
        session.setEngine(engine);

        BoardCell cell = firstNonDailyDoubleCell(engine);
        engine.selectClue("p1", cell.getCellId());

        // The AI's buzz plus the slower "nobody rang in" safety net, which no-ops once it has.
        List<AiMove> buzzMoves = engine.pendingAiMoves();
        assertThat(buzzMoves).hasSize(2);
        invokeInDelayOrder(buzzMoves, session);
        assertThat(engine.getActiveAnswererId()).isEqualTo(aiPlayer.getPlayerId());

        // Two moves are now pending: the AI's own (fast) answer, and a slower safety-net timeout
        // that becomes a harmless no-op once the AI has already answered - invoking both in delay
        // order mirrors what the real scheduler would do.
        List<AiMove> answerMoves = engine.pendingAiMoves();
        assertThat(answerMoves).hasSize(2);
        invokeInDelayOrder(answerMoves, session);

        assertThat(aiPlayer.getScore()).isEqualTo(cell.getValue());
    }

    @Test
    void aiWagersEverythingAndAnswersCorrectlyOnADailyDouble() {
        SessionPlayer aiPlayer = SessionPlayer.ai("ai1", "Watson (AI)");
        JeopardyEngine engine = engine(List.of(aiPlayer, p2, p3));
        GameSession session = new GameSession("TESTJ3", GameType.JEOPARDY, aiPlayer.getPlayerId());
        session.setEngine(engine);
        aiPlayer.setScore(300);

        BoardCell dd = dailyDoubleCell(engine);
        engine.selectClue(aiPlayer.getPlayerId(), dd.getCellId());

        List<AiMove> wagerMoves = engine.pendingAiMoves();
        assertThat(wagerMoves).hasSize(1);
        wagerMoves.get(0).invoke().apply(session);

        List<AiMove> answerMoves = engine.pendingAiMoves();
        assertThat(answerMoves).hasSize(1);
        answerMoves.get(0).invoke().apply(session);

        assertThat(aiPlayer.getScore()).isEqualTo(600); // bet everything (300) and answered correctly
    }

    @Test
    void aiWagersEverythingInFinalJeopardyAndAnswersCorrectly() {
        SessionPlayer aiPlayer = SessionPlayer.ai("ai1", "Watson (AI)");
        JeopardyEngine engine = engine(List.of(p1, aiPlayer, p3));
        GameSession session = new GameSession("TESTJ4", GameType.JEOPARDY, p1.getPlayerId());
        session.setEngine(engine);
        // Banked before the last round ends, which is when Final Jeopardy eligibility is decided.
        aiPlayer.setScore(500);

        while (engine.getPhase() == JeopardyPhase.BOARD) {
            BoardCell next = engine.boardForTesting().stream()
                    .filter(c -> c.getState() == CellState.HIDDEN)
                    .findFirst()
                    .orElse(null);
            if (next == null) {
                break;
            }
            engine.selectClue("p1", next.getCellId());
            if (next.isDailyDouble()) {
                int wager = Math.min(100, Math.max(5, p1.getScore()));
                engine.submitDailyDoubleWager("p1", wager);
                engine.submitDailyDoubleAnswer("p1", next.getClue().getResponse());
            } else {
                engine.buzz("p1");
                engine.submitAnswer("p1", next.getClue().getResponse());
            }
        }
        assertThat(engine.getPhase()).isEqualTo(JeopardyPhase.FINAL_WAGER);
        // p3 never answered anything, so they finished on $0 and don't get a final round.
        assertThat(engine.toSnapshot(null).finalEligiblePlayerIds()).containsExactlyInAnyOrder("p1", "ai1");

        List<AiMove> aiWagerMoves = engine.pendingAiMoves();
        assertThat(aiWagerMoves).hasSize(1); // p1 is human, only the AI is auto-pending
        aiWagerMoves.get(0).invoke().apply(session);

        engine.submitFinalWager("p1", 0);
        assertThat(engine.getPhase()).isEqualTo(JeopardyPhase.FINAL_ANSWER_COLLECTION);

        // Two moves are now pending: the AI's own final answer, and a slower safety-net timeout
        // that becomes a harmless no-op once the AI has already answered.
        List<AiMove> aiAnswerMoves = engine.pendingAiMoves();
        assertThat(aiAnswerMoves).hasSize(2);
        invokeInDelayOrder(aiAnswerMoves, session);

        LiveActionOutcome reveal = engine.submitFinalAnswer("p1", "wrong");

        JeopardyStateSnapshot snap = (JeopardyStateSnapshot) reveal.snapshot();
        assertThat(snap.phase()).isEqualTo("GAME_OVER");
        assertThat(aiPlayer.getScore()).isEqualTo(1000); // wagered everything (500) and got it right
    }

    @Test
    void clueValuesFollowTheHostsBaseIncrementAndScaleWithTheRound() {
        categoryPoolFor(3);
        JeopardyEngine engine = new JeopardyEngine(List.of(p1, p2, p3), jeopardyService, 3, 200, AiDifficulty.HARD);

        assertThat(engine.boardForTesting()).extracting(BoardCell::getValue)
                .containsOnly(200, 400, 600, 800, 1000);

        clearAllRounds(engine, new String[] { "p1", "p2", "p3" });
        // Round 2 doubled the ladder and round 3 tripled it before Final Jeopardy arrived.
        assertThat(engine.toSnapshot(null).phase()).isEqualTo("FINAL_WAGER");
        assertThat(engine.toSnapshot(null).currentRound()).isEqualTo(3);
        assertThat(engine.toSnapshot(null).roundCount()).isEqualTo(3);
    }

    @Test
    void roundTwoUsesDoubleTheRoundOneValues() {
        JeopardyEngine engine = new JeopardyEngine(List.of(p1, p2, p3), jeopardyService, 2, 200, AiDifficulty.HARD);
        clearOneRound(engine);

        assertThat(engine.toSnapshot(null).currentRound()).isEqualTo(2);
        assertThat(engine.boardForTesting()).extracting(BoardCell::getValue)
                .containsOnly(400, 800, 1200, 1600, 2000);
    }

    @Test
    void roundCountIsClampedToTheSupportedRange() {
        assertThat(new JeopardyEngine(List.of(p1, p2, p3), jeopardyService, 1, 100, AiDifficulty.HARD)
                .toSnapshot(null).roundCount()).isEqualTo(JeopardyEngine.MIN_ROUNDS);
        assertThat(new JeopardyEngine(List.of(p1, p2, p3), jeopardyService, 9, 100, AiDifficulty.HARD)
                .toSnapshot(null).roundCount()).isEqualTo(JeopardyEngine.MAX_ROUNDS);
    }

    @Test
    void anEasierAiGetsClaimsWrongAndDoesntAlwaysRingIn() {
        // Both dials are probabilistic, so this samples repeatedly: at EASY's 35% accuracy and 45%
        // buzz rate, "wrong at least once in 60 clues" and "passed at least once" are certainties
        // in any practical sense (p < 1e-27), while HARD must never do either.
        assertThat(sampleAiWrongAnswers(AiDifficulty.EASY, 60)).isGreaterThan(0);
        assertThat(sampleAiPassedBuzzes(AiDifficulty.EASY, 60)).isGreaterThan(0);

        assertThat(sampleAiWrongAnswers(AiDifficulty.HARD, 60)).isZero();
        assertThat(sampleAiPassedBuzzes(AiDifficulty.HARD, 60)).isZero();
    }

    /** How many of {@code trials} fresh clues the AI answers incorrectly. */
    private int sampleAiWrongAnswers(AiDifficulty difficulty, int trials) {
        int wrong = 0;
        for (int i = 0; i < trials; i++) {
            SessionPlayer ai = SessionPlayer.ai("ai1", "Bot");
            JeopardyEngine engine = new JeopardyEngine(List.of(p1, ai, p3), jeopardyService, 2, 100, difficulty);
            GameSession session = new GameSession("SAMPLE" + i, GameType.JEOPARDY, p1.getPlayerId());
            session.setEngine(engine);
            BoardCell cell = firstNonDailyDoubleCell(engine);
            engine.selectClue("p1", cell.getCellId());
            engine.buzz(ai.getPlayerId());
            invokeInDelayOrder(engine.pendingAiMoves(), session);
            if (ai.getScore() < 0) {
                wrong++;
            }
        }
        return wrong;
    }

    /** How many of {@code trials} open buzzers the AI declines to ring in on. */
    private int sampleAiPassedBuzzes(AiDifficulty difficulty, int trials) {
        int passed = 0;
        for (int i = 0; i < trials; i++) {
            SessionPlayer ai = SessionPlayer.ai("ai1", "Bot");
            JeopardyEngine engine = new JeopardyEngine(List.of(p1, ai, p3), jeopardyService, 2, 100, difficulty);
            BoardCell cell = firstNonDailyDoubleCell(engine);
            engine.selectClue("p1", cell.getCellId());
            // Only the safety-net timeout is pending when the bot decides to sit this one out.
            if (engine.pendingAiMoves().size() == 1) {
                passed++;
            }
        }
        return passed;
    }

    @Test
    void aiOnlyGameStillReachesGameOverOnEveryDifficulty() {
        categoryPoolFor(3);
        for (AiDifficulty difficulty : AiDifficulty.values()) {
            SessionPlayer ai1 = SessionPlayer.ai("ai1", "Bot1");
            SessionPlayer ai2 = SessionPlayer.ai("ai2", "Bot2");
            SessionPlayer ai3 = SessionPlayer.ai("ai3", "Bot3");
            JeopardyEngine engine = new JeopardyEngine(List.of(ai1, ai2, ai3), jeopardyService, 3, 200, difficulty);
            GameSession session = new GameSession("AIJ" + difficulty, GameType.JEOPARDY, ai1.getPlayerId());
            session.setEngine(engine);

            int steps = 0;
            while (engine.getPhase() != JeopardyPhase.GAME_OVER && steps < 5000) {
                List<AiMove> moves = engine.pendingAiMoves();
                if (moves.isEmpty()) {
                    break;
                }
                invokeInDelayOrder(moves, session);
                steps++;
            }

            assertThat(engine.getPhase())
                    .as("%s stalled after %d steps in phase %s", difficulty, steps, engine.getPhase())
                    .isEqualTo(JeopardyPhase.GAME_OVER);
        }
    }

    // ---- Answering from a list ------------------------------------------------------------------

    /** Every clue is answered from a list: its own response, a near miss, and three decoys. */
    private static final ResponseChoiceDealer LIST_EVERY_CLUE = (clue, random) -> Optional.of(new ResponseChoices(
            List.of(nearMiss(clue), "Decoy A", clue.getResponse(), "Decoy B", "Decoy C"), clue.getResponse()));

    /** One letter off the response - close enough that a typed answer would be accepted. */
    private static String nearMiss(Clue clue) {
        return clue.getResponse() + "x";
    }

    private JeopardyEngine listEngine() {
        return listEngine(List.of(p1, p2, p3), AiDifficulty.HARD);
    }

    private JeopardyEngine listEngine(List<SessionPlayer> players, AiDifficulty difficulty) {
        return new JeopardyEngine(players, jeopardyService, JeopardyEngine.DEFAULT_ROUNDS,
                JeopardyEngine.DEFAULT_CLUE_BASE_INCREMENT, difficulty, LIST_EVERY_CLUE);
    }

    @Test
    void aListClueShowsEveryoneItsOptionsWhileItIsOpen() {
        JeopardyEngine engine = listEngine();
        BoardCell cell = firstNonDailyDoubleCell(engine);

        JeopardyStateSnapshot snap = (JeopardyStateSnapshot) engine.selectClue("p1", cell.getCellId()).snapshot();

        assertThat(snap.activeCell().responseChoices())
                .containsExactlyElementsOf(cell.getResponseChoices().options())
                .contains(cell.getClue().getResponse());
    }

    @Test
    void aTypedClueOffersNoOptions() {
        JeopardyEngine engine = engine();
        BoardCell cell = firstNonDailyDoubleCell(engine);

        JeopardyStateSnapshot snap = (JeopardyStateSnapshot) engine.selectClue("p1", cell.getCellId()).snapshot();

        assertThat(snap.activeCell().responseChoices()).isNull();
    }

    @Test
    void aListClueIsJudgedByTheExactPickNotByCloseSpelling() {
        JeopardyEngine engine = listEngine();
        BoardCell cell = firstNonDailyDoubleCell(engine);
        String nearMiss = nearMiss(cell.getClue());
        assertThat(AnswerMatcher.isCorrect(nearMiss, cell.getClue().getResponse())).as("accepted if typed").isTrue();
        engine.selectClue("p1", cell.getCellId());

        engine.buzz("p2");
        engine.submitAnswer("p2", nearMiss);
        assertThat(p2.getScore()).isEqualTo(-cell.getValue());

        engine.buzz("p3");
        engine.submitAnswer("p3", cell.getClue().getResponse());
        assertThat(p3.getScore()).isEqualTo(cell.getValue());
    }

    @Test
    void anAnswerThatWasNeverOnOfferIsTurnedAwayWithoutCostingTheTurn() {
        JeopardyEngine engine = listEngine();
        BoardCell cell = firstNonDailyDoubleCell(engine);
        engine.selectClue("p1", cell.getCellId());
        engine.buzz("p2");
        long versionWhileAnswering = engine.getVersion();

        assertThatThrownBy(() -> engine.submitAnswer("p2", "something typed"))
                .isInstanceOf(InvalidActionException.class);

        // Nothing moved - not even the version the answer clock's timeout is keyed to.
        assertThat(engine.getVersion()).isEqualTo(versionWhileAnswering);
        assertThat(engine.getClueSubPhase()).isEqualTo(ClueSubPhase.ANSWERING);
        assertThat(engine.getActiveAnswererId()).isEqualTo("p2");
        assertThat(p2.getScore()).isZero();

        engine.submitAnswer("p2", cell.getClue().getResponse());
        assertThat(p2.getScore()).isEqualTo(cell.getValue());
    }

    @Test
    void aDailyDoublesOptionsAreHiddenFromEveryoneButItsFinderUntilTheyHaveWagered() {
        JeopardyEngine engine = listEngine();
        BoardCell dd = dailyDoubleCell(engine);
        p1.setScore(500);

        JeopardyStateSnapshot wagering = (JeopardyStateSnapshot) engine.selectClue("p1", dd.getCellId()).snapshot();
        assertThat(wagering.activeCell().responseChoices()).isNull();
        assertThat(engine.toSnapshot("p1").activeCell().responseChoices()).isNull();

        LiveActionOutcome wagered = engine.submitDailyDoubleWager("p1", 500);
        assertThat(((JeopardyStateSnapshot) wagered.snapshot()).activeCell().responseChoices()).isNull();
        JeopardyStateSnapshot findersView = (JeopardyStateSnapshot) wagered.privateMessages().get("p1");
        assertThat(findersView.activeCell().responseChoices()).containsExactlyElementsOf(dd.getResponseChoices().options());

        assertThatThrownBy(() -> engine.submitDailyDoubleAnswer("p1", "something typed"))
                .isInstanceOf(InvalidActionException.class);
        engine.submitDailyDoubleAnswer("p1", nearMiss(dd.getClue()));
        assertThat(p1.getScore()).isZero();
    }

    @Test
    void finalJeopardyRevealsItsClueAndOptionsOnceTheWagersAreInAndJudgesTheExactPick() {
        JeopardyEngine engine = clearAllRounds(listEngine(), new String[] { "p1", "p2", "p3" });
        assertThat(engine.getPhase()).isEqualTo(JeopardyPhase.FINAL_WAGER);
        assertThat(engine.toSnapshot(null).finalResponseChoices()).isNull();

        engine.submitFinalWager("p1", 100);
        engine.submitFinalWager("p2", 100);
        JeopardyStateSnapshot answering = (JeopardyStateSnapshot) engine.submitFinalWager("p3", 100).snapshot();
        assertThat(answering.finalClueText()).isEqualTo("The final clue");
        assertThat(answering.finalResponseChoices()).hasSize(5).contains("final response");

        assertThatThrownBy(() -> engine.submitFinalAnswer("p1", "something typed"))
                .isInstanceOf(InvalidActionException.class);
        int p1Before = p1.getScore();
        int p2Before = p2.getScore();
        engine.submitFinalAnswer("p1", "final response");
        engine.submitFinalAnswer("p2", "final responsex");
        JeopardyStateSnapshot reveal = (JeopardyStateSnapshot) engine.submitFinalAnswer("p3", "Decoy A").snapshot();

        assertThat(reveal.phase()).isEqualTo("GAME_OVER");
        assertThat(p1.getScore()).isEqualTo(p1Before + 100);
        assertThat(p2.getScore()).isEqualTo(p2Before - 100);
        assertThat(reveal.finalResults()).filteredOn(FinalResultEntry::correct)
                .extracting(FinalResultEntry::playerId).containsExactly("p1");
    }

    @Test
    void anAiThatKnowsTheAnswerPicksTheRightOption() {
        SessionPlayer ai = SessionPlayer.ai("ai1", "Watson (AI)");
        JeopardyEngine engine = listEngine(List.of(p1, ai, p3), AiDifficulty.HARD);
        GameSession session = new GameSession("LISTAI", GameType.JEOPARDY, p1.getPlayerId());
        session.setEngine(engine);
        BoardCell cell = firstNonDailyDoubleCell(engine);
        engine.selectClue("p1", cell.getCellId());
        engine.buzz(ai.getPlayerId());

        soonest(engine.pendingAiMoves()).invoke().apply(session);

        assertThat(ai.getScore()).isEqualTo(cell.getValue());
    }

    @Test
    void anAiThatDoesntKnowStillPicksFromTheList() {
        // Were a wrong answer ever off the list it would be turned away (the apply() below would
        // throw) and the bot would silently run out the clock instead of answering.
        int wrong = 0;
        for (int i = 0; i < 30; i++) {
            SessionPlayer ai = SessionPlayer.ai("ai1", "Bot");
            JeopardyEngine engine = listEngine(List.of(p1, ai, p3), AiDifficulty.EASY);
            GameSession session = new GameSession("LIST" + i, GameType.JEOPARDY, p1.getPlayerId());
            session.setEngine(engine);
            BoardCell cell = firstNonDailyDoubleCell(engine);
            engine.selectClue("p1", cell.getCellId());
            engine.buzz(ai.getPlayerId());

            soonest(engine.pendingAiMoves()).invoke().apply(session);

            assertThat(engine.getClueSubPhase()).isNotEqualTo(ClueSubPhase.ANSWERING);
            if (ai.getScore() < 0) {
                wrong++;
            }
        }
        // EASY knows 35% of answers, so 30 straight right ones would be a ~1e-14 fluke.
        assertThat(wrong).isGreaterThan(0);
    }

    // ---- Clues with several responses ----------------------------------------------------------

    private static final String PONY_EXPRESS = "{\"allOf\": [[\"St. Joseph\", \"Saint Joseph\"], \"Sacramento\"]}";
    private static final String KRONE = "{\"anyOf\": [\"Krone\", \"Krona\"]}";

    /** Every clue on every board - Final Jeopardy's too - shows this response and is judged by this key. */
    private void everyClueIs(String response, String acceptedResponses) {
        List<Clue> clues = allClues();
        for (Clue c : clues) {
            c.setResponse(response);
            c.setAcceptedResponses(acceptedResponses);
        }
        when(jeopardyService.getAllClues()).thenReturn(clues);
    }

    @Test
    void aClueNeedingTwoResponsesIsOnlyRightWithBothInAnyOrder() {
        everyClueIs("St. Joseph and Sacramento", PONY_EXPRESS);
        JeopardyEngine engine = engine();
        BoardCell cell = firstNonDailyDoubleCell(engine);
        engine.selectClue("p1", cell.getCellId());

        engine.buzz("p2");
        engine.submitAnswer("p2", "What is St. Joseph?");
        assertThat(p2.getScore()).isEqualTo(-cell.getValue());

        engine.buzz("p3");
        engine.submitAnswer("p3", "What are Sacramento & Saint Joseph?");
        assertThat(p3.getScore()).isEqualTo(cell.getValue());
    }

    @Test
    void anOpenClueTellsPlayersHowManyResponsesItNeeds() {
        JeopardyEngine ordinary = engine();
        JeopardyStateSnapshot oneResponse = (JeopardyStateSnapshot) ordinary
                .selectClue("p1", firstNonDailyDoubleCell(ordinary).getCellId()).snapshot();
        assertThat(oneResponse.activeCell().requiredResponseCount()).isEqualTo(1);

        everyClueIs("St. Joseph and Sacramento", PONY_EXPRESS);
        JeopardyEngine engine = engine();
        JeopardyStateSnapshot twoResponses = (JeopardyStateSnapshot) engine
                .selectClue("p1", firstNonDailyDoubleCell(engine).getCellId()).snapshot();
        assertThat(twoResponses.activeCell().requiredResponseCount()).isEqualTo(2);
    }

    @Test
    void aDailyDoubleKeepsItsResponseCountAsHiddenAsItsText() {
        everyClueIs("St. Joseph and Sacramento", PONY_EXPRESS);
        JeopardyEngine engine = engine();
        BoardCell dd = dailyDoubleCell(engine);
        p1.setScore(500);

        JeopardyStateSnapshot wagering = (JeopardyStateSnapshot) engine.selectClue("p1", dd.getCellId()).snapshot();
        assertThat(wagering.activeCell().requiredResponseCount()).isNull();

        LiveActionOutcome wagered = engine.submitDailyDoubleWager("p1", 500);
        assertThat(((JeopardyStateSnapshot) wagered.snapshot()).activeCell().requiredResponseCount()).isNull();
        JeopardyStateSnapshot findersView = (JeopardyStateSnapshot) wagered.privateMessages().get("p1");
        assertThat(findersView.activeCell().requiredResponseCount()).isEqualTo(2);

        engine.submitDailyDoubleAnswer("p1", "Saint Joseph and Sacramento");
        assertThat(p1.getScore()).isEqualTo(1000);
    }

    @Test
    void aClueWithMoreThanOneRightResponseIsAlwaysTyped() {
        // The dealer would happily list it - but one pick can't name two things, and the other
        // acceptable response could be dealt as a "wrong" option.
        everyClueIs("Krone / Krona", KRONE);
        JeopardyEngine engine = listEngine();
        assertThat(engine.boardForTesting()).allSatisfy(cell -> assertThat(cell.getResponseChoices()).isNull());
        BoardCell cell = firstNonDailyDoubleCell(engine);
        engine.selectClue("p1", cell.getCellId());

        engine.buzz("p2");
        engine.submitAnswer("p2", "the krona");

        assertThat(p2.getScore()).isEqualTo(cell.getValue());
    }

    @Test
    void anAiThatKnowsATwoPartAnswerNamesBothParts() {
        everyClueIs("St. Joseph and Sacramento", PONY_EXPRESS);

        assertAnAiThatKnowsTheAnswerGetsItRight();
    }

    @Test
    void anAiThatKnowsAnAnswerWithAlternativesGivesOneOfThemRatherThanTheDisplayedPair() {
        everyClueIs("Krone / Krona", KRONE);

        assertAnAiThatKnowsTheAnswerGetsItRight();
    }

    private void assertAnAiThatKnowsTheAnswerGetsItRight() {
        SessionPlayer ai = SessionPlayer.ai("ai1", "Watson (AI)");
        JeopardyEngine engine = engine(List.of(p1, ai, p3));
        GameSession session = new GameSession("MULTIAI", GameType.JEOPARDY, p1.getPlayerId());
        session.setEngine(engine);
        BoardCell cell = firstNonDailyDoubleCell(engine);
        engine.selectClue("p1", cell.getCellId());
        engine.buzz(ai.getPlayerId());

        soonest(engine.pendingAiMoves()).invoke().apply(session);

        assertThat(ai.getScore()).isEqualTo(cell.getValue());
    }

    @Test
    void finalJeopardyRevealsHowManyResponsesItNeedsWithItsClueAndWantsThemAll() {
        everyClueIs("St. Joseph and Sacramento", PONY_EXPRESS);
        JeopardyEngine engine = clearAllRoundsWithEveryoneInTheBlack();
        assertThat(engine.getPhase()).isEqualTo(JeopardyPhase.FINAL_WAGER);
        assertThat(engine.toSnapshot(null).finalRequiredResponseCount()).isNull();

        engine.submitFinalWager("p1", 100);
        engine.submitFinalWager("p2", 100);
        JeopardyStateSnapshot answering = (JeopardyStateSnapshot) engine.submitFinalWager("p3", 100).snapshot();
        assertThat(answering.finalRequiredResponseCount()).isEqualTo(2);

        engine.submitFinalAnswer("p1", "Sacramento and St. Joseph");
        engine.submitFinalAnswer("p2", "Sacramento");
        JeopardyStateSnapshot reveal = (JeopardyStateSnapshot) engine
                .submitFinalAnswer("p3", "St. Joseph, Sacramento and Denver").snapshot();

        assertThat(reveal.finalResults()).filteredOn(FinalResultEntry::correct)
                .extracting(FinalResultEntry::playerId).containsExactly("p1");
    }

    /** The move the scheduler would fire first - while an AI is answering, its answer beats the timeout. */
    private static AiMove soonest(List<AiMove> moves) {
        return moves.stream().min(Comparator.comparing(AiMove::delay)).orElseThrow();
    }

    /** Plays out every clue of the current round (p1 sweeps it) so the engine moves to the next. */
    private void clearOneRound(JeopardyEngine engine) {
        int startingRound = engine.toSnapshot(null).currentRound();
        while (engine.getPhase() == JeopardyPhase.BOARD
                && engine.toSnapshot(null).currentRound() == startingRound) {
            BoardCell next = engine.boardForTesting().stream()
                    .filter(c -> c.getState() == CellState.HIDDEN)
                    .findFirst()
                    .orElse(null);
            if (next == null) {
                break;
            }
            String controller = engine.toSnapshot(null).playerIdInControl();
            engine.selectClue(controller, next.getCellId());
            if (next.isDailyDouble()) {
                engine.submitDailyDoubleWager(controller, 5);
                engine.submitDailyDoubleAnswer(controller, next.getClue().getResponse());
            } else {
                engine.buzz(controller);
                engine.submitAnswer(controller, next.getClue().getResponse());
            }
        }
    }

    @Test
    void aiOnlyGamePlaysThroughBothRoundsAndFinalJeopardyWithoutStalling() {
        SessionPlayer ai1 = SessionPlayer.ai("ai1", "Bot1");
        SessionPlayer ai2 = SessionPlayer.ai("ai2", "Bot2");
        SessionPlayer ai3 = SessionPlayer.ai("ai3", "Bot3");
        JeopardyEngine engine = engine(List.of(ai1, ai2, ai3));
        GameSession session = new GameSession("AIJTEST", GameType.JEOPARDY, ai1.getPlayerId());
        session.setEngine(engine);

        int steps = 0;
        int maxSteps = 5000;
        while (engine.getPhase() != JeopardyPhase.GAME_OVER && steps < maxSteps) {
            List<AiMove> moves = engine.pendingAiMoves();
            if (moves.isEmpty()) {
                break;
            }
            invokeInDelayOrder(moves, session);
            steps++;
        }

        assertThat(engine.getPhase())
                .as("stalled after %d steps in phase %s", steps, engine.getPhase())
                .isEqualTo(JeopardyPhase.GAME_OVER);
    }

    // ---- Ties and the tiebreaker ---------------------------------------------------------------

    private JeopardyEngine engineWithoutTies(List<SessionPlayer> players) {
        return new JeopardyEngine(players, jeopardyService, JeopardyEngine.DEFAULT_ROUNDS,
                JeopardyEngine.DEFAULT_CLUE_BASE_INCREMENT, AiDifficulty.HARD, ResponseChoiceDealer.NONE, false);
    }

    /** Plays the whole game, then has Final Jeopardy leave the scores at exactly these - everyone
     *  wagers nothing and misses. Returns the snapshot once the last response is in. */
    private JeopardyStateSnapshot finishWithScores(JeopardyEngine engine, List<SessionPlayer> players,
            int... scores) {
        String[] ids = players.stream().map(SessionPlayer::getPlayerId).toArray(String[]::new);
        clearAllRounds(engine, ids);
        assertThat(engine.getPhase()).isEqualTo(JeopardyPhase.FINAL_WAGER);
        for (int i = 0; i < players.size(); i++) {
            players.get(i).setScore(scores[i]);
        }
        for (String id : ids) {
            engine.submitFinalWager(id, 0);
        }
        LiveActionOutcome last = null;
        for (String id : ids) {
            last = engine.submitFinalAnswer(id, "no idea");
        }
        return (JeopardyStateSnapshot) last.snapshot();
    }

    /** The tiebreaker clue's own response - right by definition. */
    private static String tiebreakerResponse(JeopardyEngine engine) {
        return engine.tiebreakerCellForTesting().getClue().getResponse();
    }

    @Test
    void tiesStandByDefault() {
        JeopardyStateSnapshot over = finishWithScores(engine(), List.of(p1, p2, p3), 1000, 1000, 400);

        assertThat(over.phase()).isEqualTo("GAME_OVER");
        assertThat(over.gameWinnerPlayerIds()).containsExactly("p1", "p2");
        assertThat(over.tiebreakerPlayerIds()).isNull();
    }

    @Test
    void withTiesOffAnOutrightLeaderSimplyWins() {
        JeopardyStateSnapshot over = finishWithScores(engineWithoutTies(List.of(p1, p2, p3)), List.of(p1, p2, p3),
                1000, 900, 400);

        assertThat(over.phase()).isEqualTo("GAME_OVER");
        assertThat(over.gameWinnerPlayerIds()).containsExactly("p1");
    }

    @Test
    void withTiesOffATiedLeadIsSettledByATiebreakerOnlyTheTiedPlay() {
        JeopardyEngine engine = engineWithoutTies(List.of(p1, p2, p3));
        JeopardyStateSnapshot intro = finishWithScores(engine, List.of(p1, p2, p3), 1000, 1000, 400);

        // First just the category, so the room takes in that there's a tiebreaker at all.
        assertThat(intro.phase()).isEqualTo("TIEBREAKER");
        assertThat(intro.tiebreakerPlayerIds()).containsExactly("p1", "p2");
        assertThat(intro.tiebreakerNumber()).isEqualTo(1);
        assertThat(intro.gameWinnerPlayerIds()).isNull();
        assertThat(intro.clueSubPhase()).isEqualTo("NONE");
        assertThat(intro.activeCell().category()).isNotBlank();
        assertThat(intro.activeCell().clueText()).isNull();
        assertThat(intro.phaseDeadlineAt()).isNotNull();
        assertThatThrownBy(() -> engine.buzz("p1")).isInstanceOf(InvalidActionException.class);

        JeopardyStateSnapshot open = (JeopardyStateSnapshot) engine.openTiebreakerClue(engine.getVersion()).snapshot();
        assertThat(open.clueSubPhase()).isEqualTo("BUZZ_OPEN");
        assertThat(open.activeCell().clueText()).isNotBlank();
        assertThatThrownBy(() -> engine.buzz("p3")).isInstanceOf(InvalidActionException.class);

        engine.buzz("p2");
        JeopardyStateSnapshot over = (JeopardyStateSnapshot) engine
                .submitAnswer("p2", tiebreakerResponse(engine)).snapshot();

        assertThat(over.phase()).isEqualTo("GAME_OVER");
        assertThat(over.gameWinnerPlayerIds()).containsExactly("p2");
        // It settles who won, not by how much.
        assertThat(List.of(p1.getScore(), p2.getScore(), p3.getScore())).containsExactly(1000, 1000, 400);
        assertThat(engine.claimCompletedGame().orElseThrow().standings())
                .extracting(CompletedGame.Standing::displayName, CompletedGame.Standing::place,
                        CompletedGame.Standing::tied)
                .containsExactly(tuple("Bob", 1, false), tuple("Alice", 2, false), tuple("Carol", 3, false));
    }

    @Test
    void aMissLetsTheOtherTiedContestantInAndIfNobodyRingsInAnotherTiebreakerFollows() {
        JeopardyEngine engine = engineWithoutTies(List.of(p1, p2, p3));
        finishWithScores(engine, List.of(p1, p2, p3), 1000, 1000, 400);
        engine.openTiebreakerClue(engine.getVersion());

        engine.buzz("p1");
        JeopardyStateSnapshot missed = (JeopardyStateSnapshot) engine.submitAnswer("p1", "not even close").snapshot();
        assertThat(missed.clueSubPhase()).isEqualTo("BUZZ_OPEN");
        assertThat(missed.lockedOutPlayerIds()).containsExactly("p1");
        assertThat(p1.getScore()).isEqualTo(1000);
        assertThatThrownBy(() -> engine.buzz("p1")).isInstanceOf(InvalidActionException.class);

        JeopardyStateSnapshot timedOut = (JeopardyStateSnapshot) engine.handleBuzzTimeout(engine.getVersion()).snapshot();
        assertThat(timedOut.phase()).isEqualTo("TIEBREAKER");
        assertThat(timedOut.clueSubPhase()).isEqualTo("REVEALED");
        assertThat(timedOut.activeCell().correctResponse()).isEqualTo(tiebreakerResponse(engine));

        JeopardyStateSnapshot second = (JeopardyStateSnapshot) engine.finishReveal(engine.getVersion()).snapshot();
        assertThat(second.phase()).isEqualTo("TIEBREAKER");
        assertThat(second.tiebreakerNumber()).isEqualTo(2);
        assertThat(second.clueSubPhase()).isEqualTo("NONE");
        assertThat(second.lockedOutPlayerIds()).isEmpty();

        engine.openTiebreakerClue(engine.getVersion());
        engine.buzz("p1");
        engine.submitAnswer("p1", tiebreakerResponse(engine));
        assertThat(engine.toSnapshot(null).gameWinnerPlayerIds()).containsExactly("p1");
    }

    @Test
    void whenEveryTiedContestantMissesTheResponseIsShownBeforeTheNextTiebreaker() {
        JeopardyEngine engine = engineWithoutTies(List.of(p1, p2, p3));
        finishWithScores(engine, List.of(p1, p2, p3), 1000, 1000, 400);
        engine.openTiebreakerClue(engine.getVersion());

        engine.buzz("p1");
        engine.submitAnswer("p1", "not even close");
        engine.buzz("p2");
        JeopardyStateSnapshot bothMissed = (JeopardyStateSnapshot) engine.submitAnswer("p2", "nope").snapshot();

        // Carol isn't tied, so she was never going to get a turn - nobody's left to try.
        assertThat(bothMissed.clueSubPhase()).isEqualTo("REVEALED");
        assertThat(bothMissed.activeCell().revealReason()).isEqualTo("ALL_INCORRECT");

        engine.finishReveal(engine.getVersion());
        assertThat(engine.toSnapshot(null).tiebreakerNumber()).isEqualTo(2);
    }

    @Test
    void withTiesOffContestantsTiedWithNoFinalJeopardyStillPlayATiebreaker() {
        JeopardyEngine engine = engineWithoutTies(List.of(p1, p2, p3));
        // Everything but one last (non-Daily-Double) clue...
        while (true) {
            List<BoardCell> hidden = engine.boardForTesting().stream()
                    .filter(c -> c.getState() == CellState.HIDDEN)
                    .sorted(Comparator.comparing(c -> !c.isDailyDouble()))
                    .toList();
            boolean lastRound = engine.toSnapshot(null).currentRound() == JeopardyEngine.DEFAULT_ROUNDS;
            if (lastRound && hidden.size() == 1) {
                break;
            }
            BoardCell next = hidden.get(0);
            engine.selectClue("p1", next.getCellId());
            if (next.isDailyDouble()) {
                engine.submitDailyDoubleWager("p1", 5);
                engine.submitDailyDoubleAnswer("p1", next.getClue().getResponse());
            } else {
                engine.buzz("p1");
                engine.submitAnswer("p1", next.getClue().getResponse());
            }
        }
        // ...then nobody has any money, and the last clue goes by unanswered.
        p1.setScore(0);
        p2.setScore(0);
        p3.setScore(-200);
        BoardCell last = engine.boardForTesting().stream().filter(c -> c.getState() == CellState.HIDDEN)
                .findFirst().orElseThrow();
        engine.selectClue("p1", last.getCellId());
        engine.handleBuzzTimeout(engine.getVersion());
        LiveActionOutcome outcome = engine.finishReveal(engine.getVersion());

        JeopardyStateSnapshot snap = (JeopardyStateSnapshot) outcome.snapshot();
        assertThat(outcome.announceText()).contains("no Final Jeopardy");
        assertThat(snap.phase()).isEqualTo("TIEBREAKER");
        assertThat(snap.tiebreakerPlayerIds()).containsExactly("p1", "p2");
    }

    @Test
    void aTieNobodyLeftCanBreakStands() {
        JeopardyEngine engine = engineWithoutTies(List.of(p1, p2, p3));
        finishWithScores(engine, List.of(p1, p2, p3), 1000, 1000, 400);
        engine.openTiebreakerClue(engine.getVersion());
        p1.setConnected(false);
        p2.setConnected(false);

        engine.handleBuzzTimeout(engine.getVersion());
        JeopardyStateSnapshot over = (JeopardyStateSnapshot) engine.finishReveal(engine.getVersion()).snapshot();

        assertThat(over.phase()).isEqualTo("GAME_OVER");
        assertThat(over.gameWinnerPlayerIds()).containsExactly("p1", "p2");
    }

    @Test
    void aiContestantsPlayATiebreakerThroughToAWinner() {
        SessionPlayer bot1 = SessionPlayer.ai("ai1", "Bot1");
        SessionPlayer bot2 = SessionPlayer.ai("ai2", "Bot2");
        List<SessionPlayer> players = List.of(bot1, bot2, p3);
        JeopardyEngine engine = engineWithoutTies(players);
        GameSession session = new GameSession("TIEAI", GameType.JEOPARDY, p3.getPlayerId());
        session.setEngine(engine);
        finishWithScores(engine, players, 1000, 1000, 400);
        assertThat(engine.getPhase()).isEqualTo(JeopardyPhase.TIEBREAKER);

        int steps = 0;
        while (engine.getPhase() == JeopardyPhase.TIEBREAKER && steps < 500) {
            invokeInDelayOrder(engine.pendingAiMoves(), session);
            steps++;
        }

        assertThat(engine.getPhase()).as("stalled after %d steps", steps).isEqualTo(JeopardyPhase.GAME_OVER);
        assertThat(engine.toSnapshot(null).gameWinnerPlayerIds()).hasSize(1).isSubsetOf("ai1", "ai2");
    }

    /** Fast-forwards a human-only game through every regular round, landing at Final Jeopardy.
     *  Correct answers are shared out round-robin so all three players finish in the black and are
     *  therefore all eligible for the final round. */
    private JeopardyEngine clearAllRoundsWithEveryoneInTheBlack() {
        return clearAllRounds(engine(), new String[] { "p1", "p2", "p3" });
    }

    /** As above, but only the named players ever answer correctly - everyone else finishes on $0. */
    private JeopardyEngine clearAllRounds(JeopardyEngine engine, String[] winners) {
        int i = 0;
        while (engine.getPhase() == JeopardyPhase.BOARD) {
            BoardCell next = engine.boardForTesting().stream()
                    .filter(c -> c.getState() == CellState.HIDDEN)
                    .findFirst()
                    .orElse(null);
            if (next == null) {
                break;
            }
            String controller = engine.toSnapshot(null).playerIdInControl();
            engine.selectClue(controller, next.getCellId());
            if (next.isDailyDouble()) {
                // Only the finder may play a Daily Double, so it always pays whoever's in control.
                int wager = Math.min(100, Math.max(5, playerScore(engine, controller)));
                engine.submitDailyDoubleWager(controller, wager);
                engine.submitDailyDoubleAnswer(controller, next.getClue().getResponse());
            } else {
                String winner = winners[i++ % winners.length];
                engine.buzz(winner);
                engine.submitAnswer(winner, next.getClue().getResponse());
            }
        }
        return engine;
    }

    /** Read from the engine itself, so it works for whoever is seated - bots included. */
    private static int playerScore(JeopardyEngine engine, String playerId) {
        return engine.toSnapshot(null).players().stream()
                .filter(p -> p.playerId().equals(playerId))
                .findFirst().orElseThrow().score();
    }

    /** Mirrors real scheduling order: the soonest-firing move runs first, so a later stale timeout
     *  (or a losing buzz-race attempt) correctly becomes a no-op once state has moved on - exactly
     *  like production, where LiveGameOrchestrator.scheduleDelayed swallows exactly this. */
    private void invokeInDelayOrder(List<AiMove> moves, GameSession session) {
        moves.stream()
                .sorted((a, b) -> a.delay().compareTo(b.delay()))
                .forEach(m -> {
                    try {
                        m.invoke().apply(session);
                    } catch (RuntimeException ignored) {
                        // Stale by the time it "fired" - same as the real scheduler discarding it.
                    }
                });
    }
}
