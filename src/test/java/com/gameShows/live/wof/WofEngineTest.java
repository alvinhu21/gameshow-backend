package com.gameShows.live.wof;

import com.gameShows.live.error.InvalidActionException;
import com.gameShows.live.session.AiDifficulty;
import com.gameShows.live.session.AiMove;
import com.gameShows.live.session.GameSession;
import com.gameShows.live.session.GameType;
import com.gameShows.live.session.LiveActionOutcome;
import com.gameShows.live.session.SessionPlayer;
import com.gameShows.models.wof.Puzzle;
import com.gameShows.models.wof.Wedge;
import com.gameShows.models.wof.WheelLayout;
import com.gameShows.services.WheelOfFortuneService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WofEngineTest {

    /** Mirrors WofEngine's own private VOWEL_COST - kept in sync manually since it isn't exposed. */
    private static final int VOWEL_COST = 250;

    private SessionPlayer alice;
    private SessionPlayer bob;
    private SessionPlayer carol;
    private WheelOfFortuneService wofService;

    @BeforeEach
    void setUp() {
        alice = new SessionPlayer("p1", "Alice", true);
        bob = new SessionPlayer("p2", "Bob", false);
        carol = new SessionPlayer("p3", "Carol", false);
        wofService = mock(WheelOfFortuneService.class);
        // A single puzzle keeps pickPuzzle() deterministic across every round (the "no unused
        // puzzles left" fallback always reselects this same one), so tests never have to guess
        // which puzzle a random pool produced. It also means the Triple Toss-Up's "three unused
        // puzzles sharing a category" search always falls back to pickPuzzle() x3, still landing on
        // this same puzzle/category every time.
        when(wofService.getAllPuzzles()).thenReturn(List.of(puzzle(1L, "PLACE", "HAWAII BEACH")));
        when(wofService.getAllCategories()).thenReturn(Set.of("PLACE"));
        when(wofService.getPuzzlesByCategory("PLACE")).thenReturn(List.of(puzzle(1L, "PLACE", "HAWAII BEACH")));
    }

    private Puzzle puzzle(long id, String category, String text) {
        Puzzle p = new Puzzle();
        p.setId(id);
        p.setCategory(category);
        p.setPuzzle(text);
        return p;
    }

    private WofEngine engineForcing() {
        return engineForcing(w -> true);
    }

    /** Spins and brings the wheel straight to rest - what the orchestrator's scheduled settle does
     *  once the animation has had time to play out. */
    private static LiveActionOutcome spinAndSettle(WofEngine engine, String playerId) {
        engine.spin(playerId);
        return engine.settleSpin(engine.getVersion());
    }

    /** Ends the player's turn with a wrong solve - the one way to pass once only vowels remain. */
    private static void missSolve(WofEngine engine, String playerId) {
        engine.beginSolve(playerId);
        engine.attemptSolve(playerId, "not the answer");
    }

    private static LiveActionOutcome finalSpinAndSettle(WofEngine engine, String playerId) {
        engine.finalSpin(playerId);
        return engine.settleSpin(engine.getVersion());
    }

    /** Plays Fortune Frenzy out under its real rules, the quickest way that leaves {@code winnerId}
     *  starting Round 1 with exactly one $1,000 solve: they solve the first puzzle, and the clock
     *  runs out on the next one with nobody ringing in. */
    private static void playOutFrenzy(WofEngine engine, String winnerId) {
        engine.beginFrenzySolve(winnerId);
        engine.attemptFrenzyAnswer(winnerId, "hawaii beach"); // -> FRENZY_PUZZLE_OVER (clock stopped, solution up)
        engine.continueFrenzy(winnerId); // skips the pause: a fresh puzzle, with time still on the clock
        engine.handleFrenzyTimeout(engine.getVersion()); // time's up with nobody answering - the Frenzy is over
        assertThat(engine.getPhase()).isEqualTo(WofPhase.FRENZY_PUZZLE_OVER);
        engine.continueFrenzy(winnerId); // -> Round 1, started by the most recent solver
    }

    /** Runs the reveal tick until the current Frenzy puzzle is fully filled in and pauses. */
    private static void fillInFrenzyBoard(WofEngine engine) {
        for (int tick = 0; tick < 100 && engine.getPhase() == WofPhase.FORTUNE_FRENZY; tick++) {
            engine.revealNextFrenzyLetter(engine.getVersion());
        }
        assertThat(engine.getPhase()).isEqualTo(WofPhase.FRENZY_PUZZLE_OVER);
    }

    /** Forces the spin to land on the first wedge in the REAL wheel matching {@code matcher},
     *  rather than a synthetic wedge - keeps chooseWedgeIndex's contract (an index into the real
     *  wheel) intact while still giving tests a deterministic outcome. Also plays Fortune Frenzy
     *  out in favor of "p1"/alice (see {@link #playOutFrenzy}; the mocked puzzle pool is a single
     *  fixed puzzle) so every existing test can assume it starts already in round 1,
     *  as before the Frenzy/triple-toss-up/final-spin/bonus-round features were added.
     *
     *  <p>Built with a zero-length game clock, already expired the instant the Triple Toss-Up ends,
     *  so tests that want to reach the speed-up/bonus round don't have to simulate rounds 4, 5, 6...
     *  playing out for real - the very first turn boundary after the toss-up goes straight to the
     *  Final Spin. {@link #roundFourBeginsNormallyWhenTheGameClockHasNotRunOut()} covers the "clock
     *  hasn't run out yet" case with its own long-duration engine. */
    private WofEngine engineForcing(Predicate<Wedge> matcher) {
        List<SessionPlayer> players = List.of(alice, bob, carol);
        // HARD keeps bot play deterministic (perfect letters and solves); the weaker difficulties
        // are deliberately random and are covered by their own tests.
        WofEngine engine = new WofEngine(players, wofService, Duration.ZERO, AiDifficulty.HARD) {
            @Override
            protected int chooseWedgeIndex(List<Wedge> wheel) {
                return firstMatching(wheel, matcher);
            }
        };
        playOutFrenzy(engine, "p1");
        return engine;
    }

    /** Like {@link #engineForcing(Predicate)}, but scripted spin by spin: each spin lands on the
     *  first wedge matching the next predicate in {@code spins}, and the last one repeats. */
    @SafeVarargs
    private WofEngine engineSpinning(Predicate<Wedge>... spins) {
        int[] spinCount = {0};
        WofEngine engine = new WofEngine(List.of(alice, bob, carol), wofService, Duration.ZERO, AiDifficulty.HARD) {
            @Override
            protected int chooseWedgeIndex(List<Wedge> wheel) {
                return firstMatching(wheel, spins[Math.min(spinCount[0]++, spins.length - 1)]);
            }
        };
        playOutFrenzy(engine, "p1");
        return engine;
    }

    private static int firstMatching(List<Wedge> wheel, Predicate<Wedge> matcher) {
        for (int i = 0; i < wheel.size(); i++) {
            if (matcher.test(wheel.get(i))) {
                return i;
            }
        }
        throw new IllegalStateException("No wedge in the wheel matched the test predicate.");
    }

    @Test
    void spinBankruptZeroesRoundScoreButKeepsCumulativeScoreAndAdvancesTurn() {
        // First spin lands on a dollar wedge (earns round score), second lands on BANKRUPT.
        List<SessionPlayer> playersList = List.of(alice, bob, carol);
        int[] spinCount = {0};
        WofEngine engine = new WofEngine(playersList, wofService, Duration.ZERO) {
            @Override
            protected int chooseWedgeIndex(List<Wedge> wheel) {
                spinCount[0]++;
                Predicate<Wedge> matcher = spinCount[0] == 1 ? (w -> w.getValue() != null) : Wedge::isBankrupt;
                for (int i = 0; i < wheel.size(); i++) {
                    if (matcher.test(wheel.get(i))) {
                        return i;
                    }
                }
                throw new IllegalStateException("No wedge in the wheel matched the test predicate.");
            }
        };
        playOutFrenzy(engine, "p1");
        int cumulativeAfterFrenzy = alice.getScore(); // 1000, unaffected by anything below

        spinAndSettle(engine, "p1");
        engine.guessConsonant("p1", 'H'); // guaranteed hit - earns round score, keeps turn
        int roundScoreBeforeBankrupt = engine.toSnapshot(null).roundScores().get("p1");
        assertThat(roundScoreBeforeBankrupt).isGreaterThan(0);
        assertThat(alice.getScore()).isEqualTo(cumulativeAfterFrenzy); // not banked yet

        WofStateSnapshot midSpin = (WofStateSnapshot) engine.spin("p1").snapshot(); // BANKRUPT this time
        // Nothing is lost until the wheel actually stops on it.
        assertThat(midSpin.roundScores().get("p1")).isEqualTo(roundScoreBeforeBankrupt);
        assertThat(midSpin.currentPlayerId()).isEqualTo("p1");

        LiveActionOutcome outcome = engine.settleSpin(engine.getVersion());

        assertThat(alice.getScore()).isEqualTo(cumulativeAfterFrenzy); // cumulative untouched
        WofStateSnapshot snap = (WofStateSnapshot) outcome.snapshot();
        assertThat(snap.roundScores().get("p1")).isZero(); // only the round tally was wiped
        assertThat(snap.currentPlayerId()).isEqualTo("p2");
        assertThat(snap.phase()).isEqualTo("WAITING_FOR_TURN_ACTION");
    }

    @Test
    void spinLoseTurnJustAdvancesTurn() {
        WofEngine engine = engineForcing(Wedge::isLoseTurn);
        alice.setScore(300);

        LiveActionOutcome outcome = spinAndSettle(engine, "p1");

        assertThat(alice.getScore()).isEqualTo(300);
        assertThat(((WofStateSnapshot) outcome.snapshot()).currentPlayerId()).isEqualTo("p2");
    }

    @Test
    void correctConsonantGuessAwardsRoundScoreAndKeepsTurn() {
        WofEngine engine = engineForcing(w -> w.getValue() != null);
        spinAndSettle(engine, "p1");
        int wedgeValue = engine.toSnapshot(null).lastSpinResult().value();
        int cumulativeBefore = alice.getScore();

        LiveActionOutcome outcome = engine.guessConsonant("p1", 'H');

        WofStateSnapshot snap = (WofStateSnapshot) outcome.snapshot();
        assertThat(snap.currentPlayerId()).isEqualTo("p1");
        // Two 'H's in HAWAII BEACH - held as round score, not yet folded into the cumulative total.
        assertThat(snap.roundScores().get("p1")).isEqualTo(wedgeValue * 2);
        assertThat(alice.getScore()).isEqualTo(cumulativeBefore);
    }

    @Test
    void wrongConsonantGuessPassesTurn() {
        WofEngine engine = engineForcing(w -> w.getValue() != null);
        spinAndSettle(engine, "p1");
        int before = alice.getScore();

        LiveActionOutcome outcome = engine.guessConsonant("p1", 'Z');

        assertThat(((WofStateSnapshot) outcome.snapshot()).currentPlayerId()).isEqualTo("p2");
        assertThat(alice.getScore()).isEqualTo(before);
    }

    @Test
    void vowelCannotBeGuessedAsAConsonant() {
        WofEngine engine = engineForcing(w -> w.getValue() != null);
        spinAndSettle(engine, "p1");

        assertThatThrownBy(() -> engine.guessConsonant("p1", 'A')).isInstanceOf(InvalidActionException.class);
    }

    @Test
    void buyVowelRequiresEnoughMoney() {
        WofEngine engine = engineForcing();
        alice.setScore(0); // Frenzy win pays $1000; zero it back out to test the funds check

        assertThatThrownBy(() -> engine.buyVowel("p1", 'A')).isInstanceOf(InvalidActionException.class);
    }

    @Test
    void buyVowelDeductsFlatCostRegardlessOfHit() {
        WofEngine engine = engineForcing();
        alice.setScore(1000);

        engine.buyVowel("p1", 'A');

        assertThat(alice.getScore()).isEqualTo(750);
    }

    @Test
    void actingOutOfTurnIsRejected() {
        WofEngine engine = engineForcing();

        assertThatThrownBy(() -> engine.spin("p2")).isInstanceOf(InvalidActionException.class);
    }

    // ---- The physical spin: in motion -> settled ----------------------------------------------

    @Test
    void spinSetsTheWheelInMotionWithoutRevealingOrApplyingTheResult() {
        WofEngine engine = engineForcing(w -> w.getValue() != null);

        WofStateSnapshot snap = (WofStateSnapshot) engine.spin("p1").snapshot();

        assertThat(snap.phase()).isEqualTo("WAITING_FOR_TURN_ACTION");
        assertThat(snap.currentPlayerId()).isEqualTo("p1");
        assertThat(snap.lastSpinResult()).isNull();
        assertThat(snap.lastSpinIndex()).isNull();
        WheelSpinView spin = snap.wheelSpin();
        assertThat(spin.inMotion()).isTrue();
        assertThat(spin.durationMs()).isBetween(5200L, 6800L);
        assertThat(spin.settlesAt()).isAfter(Instant.now().plusMillis(spin.durationMs() - 1000));
        // The forced wedge is the wheel's first dollar wedge - index 0, which covers slots 0-2.
        assertThat(spin.landingSlot()).isBetween(0, 2);
    }

    @Test
    void settlingRevealsTheWedgeUnderTheLandingSlot() {
        WofEngine engine = engineForcing(w -> w.getValue() != null);
        engine.spin("p1");
        int landingSlot = engine.toSnapshot(null).wheelSpin().landingSlot();

        WofStateSnapshot snap = (WofStateSnapshot) engine.settleSpin(engine.getVersion()).snapshot();

        assertThat(snap.phase()).isEqualTo("AWAITING_LETTER_GUESS");
        assertThat(snap.wheelSpin().inMotion()).isFalse();
        assertThat(snap.wheelSpin().landingSlot()).isEqualTo(landingSlot);
        WedgeView landed = snap.lastSpinResult();
        assertThat(landingSlot).isBetween(landed.startSlot(), landed.startSlot() + landed.slotCount() - 1);
        assertThat(snap.wheel().get(snap.lastSpinIndex())).isEqualTo(landed);
    }

    @Test
    void noTurnActionIsAcceptedWhileTheWheelIsTurning() {
        WofEngine engine = engineForcing(w -> w.getValue() != null);
        alice.setScore(1000); // plenty for a vowel, so only the moving wheel can be what blocks it
        engine.spin("p1");

        assertThatThrownBy(() -> engine.spin("p1")).isInstanceOf(InvalidActionException.class)
                .hasMessageContaining("wheel");
        assertThatThrownBy(() -> engine.buyVowel("p1", 'A')).isInstanceOf(InvalidActionException.class);
        assertThatThrownBy(() -> engine.beginSolve("p1")).isInstanceOf(InvalidActionException.class);
        assertThatThrownBy(() -> engine.guessConsonant("p1", 'H')).isInstanceOf(InvalidActionException.class);
    }

    @Test
    void aStaleSettleCallbackIsIgnored() {
        WofEngine engine = engineForcing(w -> w.getValue() != null);
        engine.spin("p1");
        long spinVersion = engine.getVersion();
        engine.settleSpin(spinVersion);

        assertThat(engine.settleSpin(spinVersion)).isNull(); // that spin already settled
        assertThat(engine.settleSpin(engine.getVersion())).isNull(); // and nothing is in motion now
    }

    @Test
    void theOnlyPendingMoveMidSpinIsTheWheelSettlingOnSchedule() {
        WofEngine engine = engineForcing(w -> w.getValue() != null);
        GameSession session = new GameSession("SETTLE", GameType.WOF, "p1");
        session.setEngine(engine);
        engine.spin("p1");

        List<AiMove> moves = engine.pendingAiMoves();

        assertThat(moves).hasSize(1);
        // Fires once the animation (5.2-6.8s) plus the short reveal grace has elapsed.
        assertThat(moves.get(0).delay().toMillis()).isBetween(5000L, 7150L);
        moves.get(0).invoke().apply(session);
        assertThat(engine.toSnapshot(null).wheelSpin().inMotion()).isFalse();
        assertThat(engine.getPhase()).isEqualTo(WofPhase.AWAITING_LETTER_GUESS);
    }

    @Test
    void everySpinGetsTheNextSpinId() {
        WofEngine engine = engineForcing(Wedge::isLoseTurn); // passes the turn, so each player spins once

        long first = ((WofStateSnapshot) spinAndSettle(engine, "p1").snapshot()).wheelSpin().spinId();
        long second = ((WofStateSnapshot) spinAndSettle(engine, "p2").snapshot()).wheelSpin().spinId();

        assertThat(second).isEqualTo(first + 1);
    }

    @Test
    void theWheelStaysWhereItStoppedIntoTheNextRound() {
        WofEngine engine = engineForcing(w -> w.getValue() != null);
        spinAndSettle(engine, "p1");
        WheelSpinView rested = engine.toSnapshot(null).wheelSpin();
        engine.beginSolve("p1");
        engine.attemptSolve("p1", "hawaii beach");
        engine.continueToNextRound("p1");

        WofStateSnapshot round2 = engine.toSnapshot(null);

        assertThat(round2.lastSpinResult()).isNull(); // nothing spun yet this round...
        assertThat(round2.wheelSpin()).isEqualTo(rested); // ...but the wheel hasn't moved
    }

    @Test
    void defaultOddsCoverEveryRimSlotEvenly() {
        WofEngine engine = new WofEngine(List.of(alice, bob, carol), wofService);
        List<Wedge> wheel = Wedge.buildSyndicatedWheel(1);
        WheelLayout layout = WheelLayout.of(wheel);
        int[] hits = new int[WheelLayout.TOTAL_SLOTS];

        for (int i = 0; i < 20_000; i++) {
            int wedgeIndex = engine.chooseWedgeIndex(wheel);
            int slot = engine.chooseLandingSlot(layout, wedgeIndex);
            assertThat(layout.wedgeIndexAtSlot(slot)).isEqualTo(wedgeIndex);
            hits[slot]++;
        }

        // Uniform is ~278 hits per slot (sd ~17); this band only fails for a missing or skewed slot.
        assertThat(Arrays.stream(hits).min().orElseThrow()).isGreaterThan(150);
        assertThat(Arrays.stream(hits).max().orElseThrow()).isLessThan(420);
    }

    @Test
    void theGameClockNeverCutsASpinShort() throws InterruptedException {
        WofEngine engine = engineInRoundFour(Duration.ofMillis(500), w -> w.getValue() != null);
        GameSession session = new GameSession("SPINCLOCK", GameType.WOF, "p1");
        session.setEngine(engine);
        WofStateSnapshot round4 = engine.toSnapshot(null);
        engine.spin(round4.currentPlayerId());

        // Let the clock run out mid-spin.
        Thread.sleep(Math.max(0, Duration.between(Instant.now(), round4.gameClockEndsAt()).toMillis()) + 20);

        assertThat(engine.handleGameClockExpiry(engine.getVersion())).isNull();
        assertThat(engine.pendingAiMoves()).hasSize(1); // just the settle - no clock cut queued
        engine.settleSpin(engine.getVersion());
        assertThat(engine.getPhase()).isEqualTo(WofPhase.AWAITING_LETTER_GUESS); // the spin counted
        // Only now does the cut get scheduled, and it takes effect straight away.
        List<AiMove> afterSettle = engine.pendingAiMoves();
        assertThat(afterSettle).hasSize(1);
        afterSettle.get(0).invoke().apply(session);
        assertThat(engine.getPhase()).isEqualTo(WofPhase.FINAL_SPIN);
    }

    @Test
    void beginSolveRejectedWhenNotYourTurn() {
        WofEngine engine = engineForcing();

        assertThatThrownBy(() -> engine.beginSolve("p2")).isInstanceOf(InvalidActionException.class);
    }

    @Test
    void beginSolveRejectedInWrongPhase() {
        WofEngine engine = engineForcing();
        engine.beginSolve("p1");

        // Already mid-solve (AWAITING_SOLVE) - can't declare again from that phase.
        assertThatThrownBy(() -> engine.beginSolve("p1")).isInstanceOf(InvalidActionException.class);
    }

    @Test
    void beginSolveEntersAwaitingSolvePhaseWithDeadline() {
        WofEngine engine = engineForcing();

        LiveActionOutcome outcome = engine.beginSolve("p1");

        WofStateSnapshot snap = (WofStateSnapshot) outcome.snapshot();
        assertThat(snap.phase()).isEqualTo("AWAITING_SOLVE");
        assertThat(snap.solvingPlayerId()).isEqualTo("p1");
        assertThat(snap.solveDeadlineAt()).isNotNull();
    }

    @Test
    void attemptSolveRejectedWhenSomeoneElseIsSolving() {
        WofEngine engine = engineForcing();
        engine.beginSolve("p1");

        assertThatThrownBy(() -> engine.attemptSolve("p2", "hawaii beach"))
                .isInstanceOf(InvalidActionException.class);
    }

    @Test
    void solvingCorrectlyEndsRoundWithoutExtraCash() {
        WofEngine engine = engineForcing();
        int before = alice.getScore();
        engine.beginSolve("p1");

        LiveActionOutcome outcome = engine.attemptSolve("p1", "hawaii beach");

        WofStateSnapshot snap = (WofStateSnapshot) outcome.snapshot();
        assertThat(snap.phase()).isEqualTo("ROUND_OVER");
        assertThat(snap.roundWinnerPlayerId()).isEqualTo("p1");
        assertThat(snap.solvingPlayerId()).isNull();
        assertThat(alice.getScore()).isEqualTo(before);
        // The whole board is revealed once the round's over, even if not every letter was
        // individually called before the correct solve.
        assertThat(snap.puzzleTemplate()).isEqualTo("HAWAII BEACH");
    }

    @Test
    void roundScoreIsBankedIntoCumulativeWhenTheNextRoundBegins() {
        WofEngine engine = engineForcing(w -> w.getValue() != null);
        spinAndSettle(engine, "p1");
        engine.guessConsonant("p1", 'H'); // guaranteed hit, keeps turn, earns round score
        int roundScore = engine.toSnapshot(null).roundScores().get("p1");
        assertThat(roundScore).isGreaterThan(0);
        int cumulativeBeforeRoundEnds = alice.getScore();

        engine.beginSolve("p1");
        engine.attemptSolve("p1", "hawaii beach"); // solves -> ROUND_OVER
        assertThat(engine.toSnapshot(null).roundScores().get("p1")).isEqualTo(roundScore); // not banked yet
        assertThat(alice.getScore()).isEqualTo(cumulativeBeforeRoundEnds); // not banked yet

        engine.continueToNextRound("p1"); // round 2 begins - banking happens here

        assertThat(alice.getScore()).isEqualTo(cumulativeBeforeRoundEnds + roundScore);
        assertThat(engine.toSnapshot(null).roundScores().getOrDefault("p1", 0)).isZero();
    }

    @Test
    void buyingAVowelSpendsRoundScoreFirstAndLocksItIn() {
        WofEngine engine = engineForcing(w -> w.getValue() != null);
        alice.setScore(0); // zero out the Frenzy win so only round score can fund the vowel
        spinAndSettle(engine, "p1");
        engine.guessConsonant("p1", 'H'); // guaranteed hit - two H's - earns round score, keeps turn
        int roundScoreBeforeVowel = engine.toSnapshot(null).roundScores().get("p1");
        assertThat(roundScoreBeforeVowel).isGreaterThanOrEqualTo(VOWEL_COST);

        engine.buyVowel("p1", 'A'); // funded entirely by this round's not-yet-banked winnings

        // Buying the vowel banks the round score first, then deducts the cost from cumulative -
        // net effect: cumulative gains (roundScoreBeforeVowel - cost), round score resets to 0.
        assertThat(alice.getScore()).isEqualTo(roundScoreBeforeVowel - VOWEL_COST);
        assertThat(engine.toSnapshot(null).roundScores().getOrDefault("p1", 0)).isZero();
    }

    @Test
    void buyVowelStillRejectedWhenTotalFundsAreInsufficient() {
        WofEngine engine = engineForcing(w -> w.getValue() != null);
        alice.setScore(0);
        // No letters guessed yet this round - both cumulative and round score are 0.

        assertThatThrownBy(() -> engine.buyVowel("p1", 'A')).isInstanceOf(InvalidActionException.class);
    }

    @Test
    void incorrectSolveAttemptPassesTurn() {
        WofEngine engine = engineForcing();
        engine.beginSolve("p1");

        LiveActionOutcome outcome = engine.attemptSolve("p1", "wrong guess");

        WofStateSnapshot snap = (WofStateSnapshot) outcome.snapshot();
        assertThat(snap.currentPlayerId()).isEqualTo("p2");
        assertThat(snap.phase()).isEqualTo("WAITING_FOR_TURN_ACTION");
    }

    @Test
    void handleSolveTimeoutAutoFailsAndAdvancesTurn() {
        WofEngine engine = engineForcing();
        engine.beginSolve("p1");

        LiveActionOutcome outcome = engine.handleSolveTimeout(engine.getVersion());

        WofStateSnapshot snap = (WofStateSnapshot) outcome.snapshot();
        assertThat(snap.phase()).isEqualTo("WAITING_FOR_TURN_ACTION");
        assertThat(snap.currentPlayerId()).isEqualTo("p2");
        assertThat(snap.solvingPlayerId()).isNull();
    }

    @Test
    void handleSolveTimeoutIsNoOpWhenVersionIsStale() {
        WofEngine engine = engineForcing();
        engine.beginSolve("p1");
        long staleVersion = engine.getVersion() - 1;

        LiveActionOutcome result = engine.handleSolveTimeout(staleVersion);

        assertThat(result).isNull();
    }

    @Test
    void continueToNextRoundRejectedWhenRoundIsNotOver() {
        WofEngine engine = engineForcing(); // still WAITING_FOR_TURN_ACTION, not ROUND_OVER

        assertThatThrownBy(() -> engine.continueToNextRound("p1")).isInstanceOf(InvalidActionException.class);
    }

    @Test
    void continueToNextRoundRejectedAfterSomeoneElseAlreadyClickedIt() {
        WofEngine engine = engineForcing();
        engine.beginSolve("p1");
        engine.attemptSolve("p1", "hawaii beach"); // -> ROUND_OVER
        engine.continueToNextRound("p1"); // first click wins, round 2 begins

        assertThatThrownBy(() -> engine.continueToNextRound("p2")).isInstanceOf(InvalidActionException.class);
    }

    @Test
    void continueToNextRoundAcceptsAnyConnectedPlayerNotJustTheRoundWinner() {
        WofEngine engine = engineForcing();
        engine.beginSolve("p1");
        engine.attemptSolve("p1", "hawaii beach"); // p1 won the round

        // p2 (a spectator to that round) may still click Continue - it isn't restricted to whoever
        // just won.
        LiveActionOutcome outcome = engine.continueToNextRound("p2");

        WofStateSnapshot snap = (WofStateSnapshot) outcome.snapshot();
        assertThat(snap.roundNumber()).isEqualTo(2);
        assertThat(snap.phase()).isEqualTo("WAITING_FOR_TURN_ACTION");
    }

    @Test
    void continueToNextRoundMovesToNextRoundWithNewPuzzle() {
        WofEngine engine = engineForcing();
        engine.beginSolve("p1");
        engine.attemptSolve("p1", "hawaii beach");

        LiveActionOutcome outcome = engine.continueToNextRound("p1");

        WofStateSnapshot snap = (WofStateSnapshot) outcome.snapshot();
        assertThat(snap.roundNumber()).isEqualTo(2);
        assertThat(snap.phase()).isEqualTo("WAITING_FOR_TURN_ACTION");
    }

    @Test
    void pendingAiMovesOffersEveryAiPlayerAContinueClickWhenRoundIsOver() {
        SessionPlayer aiOpponent = SessionPlayer.ai("ai1", "Watson (AI)");
        WofEngine engine = engineForcingAi(aiOpponent, w -> w.getValue() != null);
        // engineForcingAi() starts round 1 with the AI as current player - force it straight to
        // ROUND_OVER without depending on real gameplay.
        engine.beginSolve(aiOpponent.getPlayerId());
        engine.attemptSolve(aiOpponent.getPlayerId(), "hawaii beach");
        assertThat(engine.getPhase()).isEqualTo(WofPhase.ROUND_OVER);

        List<AiMove> moves = engine.pendingAiMoves();

        // Only "ai1" is AI here (bob/carol are human) - exactly one Continue-click move offered.
        assertThat(moves).hasSize(1);
    }

    @Test
    void thirdRoundSolveBeginsTheTripleTossUpInsteadOfRound4() {
        WofEngine engine = engineForcing();
        engine.beginSolve("p1");
        engine.attemptSolve("p1", "hawaii beach");
        engine.continueToNextRound("p1");
        WofStateSnapshot snap2 = engine.toSnapshot(null);
        engine.beginSolve(snap2.currentPlayerId());
        engine.attemptSolve(snap2.currentPlayerId(), "hawaii beach");
        engine.continueToNextRound("p1");
        WofStateSnapshot snap3 = engine.toSnapshot(null);

        engine.beginSolve(snap3.currentPlayerId());
        LiveActionOutcome outcome = engine.attemptSolve(snap3.currentPlayerId(), "hawaii beach");
        LiveActionOutcome afterRound3 = engine.continueToNextRound("p1");

        WofStateSnapshot snap = (WofStateSnapshot) afterRound3.snapshot();
        assertThat(snap.phase()).isEqualTo("TRIPLE_TOSS_UP");
        assertThat(snap.roundNumber()).isEqualTo(4);
        assertThat(snap.tripleTossUpIndex()).isZero();
        assertThat(snap.tripleTossUpCategory()).isEqualTo("PLACE");
        assertThat(((WofStateSnapshot) outcome.snapshot()).phase()).isEqualTo("ROUND_OVER");
    }

    @Test
    void tripleTossUpAwardsPerPuzzleCashAndSweepBonusThenBeginsRound4() {
        WofEngine engine = engineAtTripleTossUp();
        alice.setScore(0);

        engine.beginTripleTossUpSolve("p1");
        engine.attemptTripleTossUpAnswer("p1", "hawaii beach");
        assertThat(alice.getScore()).isEqualTo(2000);
        engine.beginTripleTossUpSolve("p1");
        engine.attemptTripleTossUpAnswer("p1", "hawaii beach");
        assertThat(alice.getScore()).isEqualTo(4000);
        engine.beginTripleTossUpSolve("p1");
        LiveActionOutcome outcome = engine.attemptTripleTossUpAnswer("p1", "hawaii beach");

        // Third puzzle: $2,000 for the solve, plus the $4,000 "swept all three" bonus.
        assertThat(alice.getScore()).isEqualTo(4000 + 2000 + 4000);
        // engineForcing()'s zero-length game clock is already expired, so Round 4 goes straight to
        // the Final Spin instead of playing out as a normal round.
        WofStateSnapshot snap = (WofStateSnapshot) outcome.snapshot();
        assertThat(snap.phase()).isEqualTo("FINAL_SPIN");
        assertThat(snap.currentPlayerId()).isEqualTo("p1");
    }

    @Test
    void tripleTossUpWrongGuessLocksOutOnlyThatPlayerForThatPuzzle() {
        WofEngine engine = engineAtTripleTossUp();

        engine.beginTripleTossUpSolve("p1");
        engine.attemptTripleTossUpAnswer("p1", "wrong guess");
        assertThatThrownBy(() -> engine.beginTripleTossUpSolve("p1"))
                .isInstanceOf(InvalidActionException.class);

        engine.beginTripleTossUpSolve("p2");
        LiveActionOutcome outcome = engine.attemptTripleTossUpAnswer("p2", "hawaii beach");
        WofStateSnapshot snap = (WofStateSnapshot) outcome.snapshot();
        assertThat(snap.phase()).isEqualTo("TRIPLE_TOSS_UP");
        assertThat(snap.tripleTossUpIndex()).isEqualTo(1);
        assertThat(bob.getScore()).isEqualTo(2000);

        // p1 was only locked out of the puzzle that just ended, not the whole sequence.
        engine.beginTripleTossUpSolve("p1");
        LiveActionOutcome secondPuzzle = engine.attemptTripleTossUpAnswer("p1", "hawaii beach");
        assertThat(alice.getScore()).isEqualTo(3000); // $1,000 Frenzy win (engineAtTripleTossUp) + $2,000 here
        assertThat(((WofStateSnapshot) secondPuzzle.snapshot()).tripleTossUpIndex()).isEqualTo(2);
    }

    @Test
    void tripleTossUpThirdPuzzleUnsolvedGivesRound4ToTheFirstPlayer() {
        WofEngine engine = engineAtTripleTossUp();
        engine.beginTripleTossUpSolve("p1");
        engine.attemptTripleTossUpAnswer("p1", "hawaii beach");
        engine.beginTripleTossUpSolve("p2");
        engine.attemptTripleTossUpAnswer("p2", "hawaii beach");

        engine.beginTripleTossUpSolve("p1");
        engine.attemptTripleTossUpAnswer("p1", "wrong guess");
        engine.beginTripleTossUpSolve("p2");
        engine.attemptTripleTossUpAnswer("p2", "wrong guess");
        engine.beginTripleTossUpSolve("p3");
        LiveActionOutcome outcome3 = engine.attemptTripleTossUpAnswer("p3", "wrong guess");

        WofStateSnapshot snap = (WofStateSnapshot) outcome3.snapshot();
        assertThat(snap.phase()).isEqualTo("FINAL_SPIN");
        assertThat(snap.currentPlayerId()).isEqualTo("p1"); // nobody solved puzzle 3 - falls back to the first player
    }

    @Test
    void beginTripleTossUpSolveRejectedWhenAnotherPlayerIsAnswering() {
        WofEngine engine = engineAtTripleTossUp();
        engine.beginTripleTossUpSolve("p1");

        assertThatThrownBy(() -> engine.beginTripleTossUpSolve("p2"))
                .isInstanceOf(InvalidActionException.class);
    }

    @Test
    void tripleTossUpBuzzerReopensAfterWrongAnswer() {
        WofEngine engine = engineAtTripleTossUp();
        engine.beginTripleTossUpSolve("p1");
        engine.attemptTripleTossUpAnswer("p1", "wrong guess");

        // Buzzer is free again - p2 can now declare.
        LiveActionOutcome outcome = engine.beginTripleTossUpSolve("p2");

        assertThat(((WofStateSnapshot) outcome.snapshot()).tripleTossUpSolvingPlayerId()).isEqualTo("p2");
    }

    @Test
    void tripleTossUpSolveTimeoutLocksOutPlayerAndReopensBuzzer() {
        WofEngine engine = engineAtTripleTossUp();
        engine.beginTripleTossUpSolve("p1");

        LiveActionOutcome outcome = engine.handleTripleTossUpSolveTimeout(engine.getVersion());

        WofStateSnapshot snap = (WofStateSnapshot) outcome.snapshot();
        assertThat(snap.phase()).isEqualTo("TRIPLE_TOSS_UP");
        assertThat(snap.tripleTossUpSolvingPlayerId()).isNull();
        assertThat(snap.puzzleLockedOutPlayerIds()).contains("p1");

        // p1 is locked out of this puzzle now, but p2 can still declare.
        assertThatThrownBy(() -> engine.beginTripleTossUpSolve("p1")).isInstanceOf(InvalidActionException.class);
        LiveActionOutcome reopened = engine.beginTripleTossUpSolve("p2");
        assertThat(((WofStateSnapshot) reopened.snapshot()).tripleTossUpSolvingPlayerId()).isEqualTo("p2");
    }

    @Test
    void tripleTossUpBoardStopsFillingInWhileAPlayerIsAnsweringThenResumesOnceTheyMiss() {
        WofEngine engine = engineAtTripleTossUp();
        engine.beginTripleTossUpSolve("p1");
        WofStateSnapshot before = engine.toSnapshot(null);

        // A reveal tick is a complete no-op mid-answer - so none can fill in the last letter and
        // end the puzzle before the contestant has submitted.
        assertThat(engine.revealNextTripleTossUpLetter(engine.getVersion())).isNull();

        WofStateSnapshot after = engine.toSnapshot(null);
        assertThat(after.tripleTossUpSolvingPlayerId()).isEqualTo("p1");
        assertThat(after.tripleTossUpSolveDeadlineAt()).isEqualTo(before.tripleTossUpSolveDeadlineAt());
        assertThat(after.puzzleTemplate()).isEqualTo(before.puzzleTemplate());

        engine.attemptTripleTossUpAnswer("p1", "wrong guess");
        assertThat(engine.revealNextTripleTossUpLetter(engine.getVersion())).isNotNull();
    }

    @Test
    void finalSpinLandingOnADollarValueBeginsTheSpeedupRound() {
        WofEngine engine = engineAtTripleTossUp();
        engine.beginTripleTossUpSolve("p1");
        engine.attemptTripleTossUpAnswer("p1", "hawaii beach");
        engine.beginTripleTossUpSolve("p1");
        engine.attemptTripleTossUpAnswer("p1", "hawaii beach");
        engine.beginTripleTossUpSolve("p1");
        LiveActionOutcome outcome = engine.attemptTripleTossUpAnswer("p1", "hawaii beach");
        String spinnerId = ((WofStateSnapshot) outcome.snapshot()).currentPlayerId();

        // engineForcing()'s wedge-matcher (w -> true) always lands on the wheel's first wedge, a
        // dollar amount - never Bankrupt/Lose a Turn - so this resolves in one spin.
        LiveActionOutcome spun = finalSpinAndSettle(engine, spinnerId);

        WofStateSnapshot snap = (WofStateSnapshot) spun.snapshot();
        assertThat(snap.phase()).isEqualTo("SPEEDUP_AWAITING_LETTER");
        assertThat(snap.currentPlayerId()).isEqualTo(spinnerId);
        assertThat(snap.speedupLetterValue()).isNotNull();
    }

    @Test
    void finalSpinBankruptOrLoseATurnPassesToTheNextPlayerToSpinAgain() {
        List<SessionPlayer> players = List.of(alice, bob, carol);
        WofEngine engine = new WofEngine(players, wofService, Duration.ZERO) {
            @Override
            protected int chooseWedgeIndex(List<Wedge> wheel) {
                for (int i = 0; i < wheel.size(); i++) {
                    if (wheel.get(i).isBankrupt()) {
                        return i;
                    }
                }
                throw new IllegalStateException("No BANKRUPT wedge in the wheel.");
            }
        };
        playOutFrenzy(engine, "p1");
        engine.beginSolve("p1");
        engine.attemptSolve("p1", "hawaii beach");
        engine.continueToNextRound("p1");
        String round2Player = engine.toSnapshot(null).currentPlayerId();
        engine.beginSolve(round2Player);
        engine.attemptSolve(round2Player, "hawaii beach");
        engine.continueToNextRound("p1");
        String round3Player = engine.toSnapshot(null).currentPlayerId();
        engine.beginSolve(round3Player);
        engine.attemptSolve(round3Player, "hawaii beach");
        engine.continueToNextRound("p1");
        assertThat(engine.getPhase()).isEqualTo(WofPhase.TRIPLE_TOSS_UP);
        engine.beginTripleTossUpSolve("p1");
        engine.attemptTripleTossUpAnswer("p1", "hawaii beach");
        engine.beginTripleTossUpSolve("p1");
        engine.attemptTripleTossUpAnswer("p1", "hawaii beach");
        engine.beginTripleTossUpSolve("p1");
        LiveActionOutcome tripleTossUpOutcome = engine.attemptTripleTossUpAnswer("p1", "hawaii beach");
        String spinnerId = ((WofStateSnapshot) tripleTossUpOutcome.snapshot()).currentPlayerId();
        int spinnerScoreBefore = playerById(spinnerId).getScore();

        LiveActionOutcome spun = finalSpinAndSettle(engine, spinnerId);

        WofStateSnapshot snap = (WofStateSnapshot) spun.snapshot();
        assertThat(snap.phase()).isEqualTo("FINAL_SPIN"); // still spinning - Bankrupt doesn't decide the round
        assertThat(snap.currentPlayerId()).isNotEqualTo(spinnerId); // passed to the next player
        assertThat(playerById(spinnerId).getScore()).isEqualTo(spinnerScoreBefore); // no score penalty
    }

    @Test
    void roundFourBeginsNormallyWhenTheGameClockHasNotRunOut() {
        List<SessionPlayer> players = List.of(alice, bob, carol);
        WofEngine engine = new WofEngine(players, wofService, Duration.ofMinutes(20));
        playOutFrenzy(engine, "p1");
        assertThat(engine.toSnapshot(null).totalRounds()).isEqualTo(3); // fixed rounds still reported

        engine.beginSolve("p1");
        engine.attemptSolve("p1", "hawaii beach");
        engine.continueToNextRound("p1");
        String round2Player = engine.toSnapshot(null).currentPlayerId();
        engine.beginSolve(round2Player);
        engine.attemptSolve(round2Player, "hawaii beach");
        engine.continueToNextRound("p1");
        String round3Player = engine.toSnapshot(null).currentPlayerId();
        engine.beginSolve(round3Player);
        engine.attemptSolve(round3Player, "hawaii beach");
        engine.continueToNextRound("p1");
        assertThat(engine.getPhase()).isEqualTo(WofPhase.TRIPLE_TOSS_UP);

        // Resolve all three Triple Toss-Up puzzles unsolved - it always plays out in full regardless
        // of the clock, and with 20 minutes left, Round 4 should be a normal round, not Final Spin.
        for (int i = 0; i < 3; i++) {
            engine.beginTripleTossUpSolve("p1");
            engine.attemptTripleTossUpAnswer("p1", "wrong guess");
            engine.beginTripleTossUpSolve("p2");
            engine.attemptTripleTossUpAnswer("p2", "wrong guess");
            engine.beginTripleTossUpSolve("p3");
            engine.attemptTripleTossUpAnswer("p3", "wrong guess");
        }

        WofStateSnapshot snap = engine.toSnapshot(null);
        assertThat(snap.phase()).isEqualTo("WAITING_FOR_TURN_ACTION");
        assertThat(snap.roundNumber()).isEqualTo(4);
        assertThat(snap.currentPlayerId()).isEqualTo("p1"); // nobody solved puzzle 3 - falls back to the first player
        assertThat(snap.totalRounds()).isNull(); // open-ended from here on
    }

    @Test
    void midRoundClockExpiryCutsTheRoundShortIntoTheFinalSpin() throws InterruptedException {
        List<SessionPlayer> players = List.of(alice, bob, carol);
        WofEngine engine = new WofEngine(players, wofService, Duration.ofMillis(60)) {
            @Override
            protected int chooseWedgeIndex(List<Wedge> wheel) {
                for (int i = 0; i < wheel.size(); i++) {
                    if (wheel.get(i).getValue() != null) {
                        return i;
                    }
                }
                throw new IllegalStateException("No dollar wedge in the wheel.");
            }
        };
        playOutFrenzy(engine, "p1");
        engine.beginSolve("p1");
        engine.attemptSolve("p1", "hawaii beach");
        engine.continueToNextRound("p1");
        String round2Player = engine.toSnapshot(null).currentPlayerId();
        engine.beginSolve(round2Player);
        engine.attemptSolve(round2Player, "hawaii beach");
        engine.continueToNextRound("p1");
        String round3Player = engine.toSnapshot(null).currentPlayerId();
        engine.beginSolve(round3Player);
        engine.attemptSolve(round3Player, "hawaii beach");
        engine.continueToNextRound("p1");
        for (int i = 0; i < 3; i++) {
            engine.beginTripleTossUpSolve("p1");
            engine.attemptTripleTossUpAnswer("p1", "wrong guess");
            engine.beginTripleTossUpSolve("p2");
            engine.attemptTripleTossUpAnswer("p2", "wrong guess");
            engine.beginTripleTossUpSolve("p3");
            engine.attemptTripleTossUpAnswer("p3", "wrong guess");
        }
        WofStateSnapshot round4 = engine.toSnapshot(null);
        assertThat(round4.phase()).isEqualTo("WAITING_FOR_TURN_ACTION"); // the 60ms clock hasn't run out yet

        Thread.sleep(70); // let the game clock expire mid-round-4
        spinAndSettle(engine, round4.currentPlayerId()); // guaranteed dollar wedge, not Bankrupt/Lose a Turn

        LiveActionOutcome outcome = engine.guessConsonant(round4.currentPlayerId(), 'Z'); // not in HAWAII BEACH

        WofStateSnapshot snap = (WofStateSnapshot) outcome.snapshot();
        assertThat(snap.phase()).isEqualTo("FINAL_SPIN"); // cut short mid-round instead of waiting for a turn
    }

    @Test
    void gameClockExpiryIsProactivelyScheduledEvenWhenNobodyActs() throws InterruptedException {
        // Reactive checks (spin()/guessConsonant()/etc.) only cut the round the next time SOMEONE
        // acts - this proves the server also proactively schedules the cut for the exact moment the
        // clock runs out, regardless of whose turn it is or whether they ever act again.
        List<SessionPlayer> players = List.of(alice, bob, carol);
        WofEngine engine = new WofEngine(players, wofService, Duration.ofMillis(60)) {
            @Override
            protected int chooseWedgeIndex(List<Wedge> wheel) {
                for (int i = 0; i < wheel.size(); i++) {
                    if (wheel.get(i).getValue() != null) {
                        return i;
                    }
                }
                throw new IllegalStateException("No dollar wedge in the wheel.");
            }
        };
        GameSession session = new GameSession("CLOCKTEST", GameType.WOF, "p1");
        session.setEngine(engine);
        playOutFrenzy(engine, "p1");
        engine.beginSolve("p1");
        engine.attemptSolve("p1", "hawaii beach");
        engine.continueToNextRound("p1");
        String round2Player = engine.toSnapshot(null).currentPlayerId();
        engine.beginSolve(round2Player);
        engine.attemptSolve(round2Player, "hawaii beach");
        engine.continueToNextRound("p1");
        String round3Player = engine.toSnapshot(null).currentPlayerId();
        engine.beginSolve(round3Player);
        engine.attemptSolve(round3Player, "hawaii beach");
        engine.continueToNextRound("p1");
        for (int i = 0; i < 3; i++) {
            engine.beginTripleTossUpSolve("p1");
            engine.attemptTripleTossUpAnswer("p1", "wrong guess");
            engine.beginTripleTossUpSolve("p2");
            engine.attemptTripleTossUpAnswer("p2", "wrong guess");
            engine.beginTripleTossUpSolve("p3");
            engine.attemptTripleTossUpAnswer("p3", "wrong guess");
        }
        assertThat(engine.getPhase()).isEqualTo(WofPhase.WAITING_FOR_TURN_ACTION); // 60ms hasn't run out yet

        Thread.sleep(70);
        // Simulate exactly what LiveGameOrchestrator does after every action: ask the engine what's
        // pending and fire it - except here NOBODY has acted since the clock expired.
        List<AiMove> moves = engine.pendingAiMoves();
        assertThat(moves).hasSize(1);
        LiveActionOutcome outcome = moves.get(0).invoke().apply(session);

        assertThat(outcome).isNotNull();
        assertThat(engine.getPhase()).isEqualTo(WofPhase.FINAL_SPIN);
    }

    @Test
    void gameEndsAfterFinalRoundSolve() {
        WofEngine engine = engineAtSpeedupRound();

        WofStateSnapshot speedupSnap = engine.toSnapshot(null);
        assertThat(speedupSnap.phase()).isEqualTo("SPEEDUP_AWAITING_LETTER");
        String speedupPlayerId = speedupSnap.currentPlayerId();

        LiveActionOutcome letterOutcome = engine.speedupGuessLetter(speedupPlayerId, 'H'); // guaranteed hit
        assertThat(((WofStateSnapshot) letterOutcome.snapshot()).phase()).isEqualTo("SPEEDUP_AWAITING_SOLVE");

        LiveActionOutcome speedupSolveOutcome = engine.speedupAttemptSolve(speedupPlayerId, "hawaii beach");
        WofStateSnapshot afterSpeedup = (WofStateSnapshot) speedupSolveOutcome.snapshot();
        assertThat(afterSpeedup.phase()).isEqualTo("BONUS_PICK_CATEGORY");
        String bonusWinnerId = afterSpeedup.bonusWinnerPlayerId();
        assertThat(bonusWinnerId).isNotNull();

        LiveActionOutcome categoryOutcome = engine.pickBonusCategory(bonusWinnerId, afterSpeedup.bonusCategoryOptions().get(0));
        assertThat(((WofStateSnapshot) categoryOutcome.snapshot()).phase()).isEqualTo("BONUS_PICK_LETTERS");

        LiveActionOutcome lettersOutcome = engine.submitBonusLetters(bonusWinnerId, List.of('H', 'W', 'B'), 'A');
        assertThat(((WofStateSnapshot) lettersOutcome.snapshot()).phase()).isEqualTo("BONUS_SOLVE");

        LiveActionOutcome bonusSolveOutcome = engine.attemptBonusSolve(bonusWinnerId, "hawaii beach");
        WofStateSnapshot snapFinal = (WofStateSnapshot) bonusSolveOutcome.snapshot();

        assertThat(snapFinal.phase()).isEqualTo("GAME_OVER");
        assertThat(snapFinal.gameWinnerPlayerIds()).isNotEmpty();
        assertThat(snapFinal.bonusSolved()).isTrue();
        assertThat(snapFinal.bonusPrizeAmount()).isNotNull();
    }

    @Test
    void pendingAiMovesIsEmptyForAHumanTurn() {
        WofEngine engine = engineForcing();

        assertThat(engine.pendingAiMoves()).isEmpty();
    }

    @Test
    void aiSpinsThenGuessesARealLetterAutomatically() {
        SessionPlayer aiPlayer = SessionPlayer.ai("ai1", "Watson (AI)");
        WofEngine engine = engineForcingAi(aiPlayer, w -> w.getValue() != null);
        aiPlayer.setScore(0); // zero out the Frenzy win so the AI can't just buy a vowel instead of spinning
        GameSession session = new GameSession("TESTCODE", GameType.WOF, aiPlayer.getPlayerId());
        session.setEngine(engine);

        List<AiMove> spinMoves = engine.pendingAiMoves();
        assertThat(spinMoves).hasSize(1);
        LiveActionOutcome spinOutcome = spinMoves.get(0).invoke().apply(session);
        assertThat(spinOutcome).isNotNull();
        assertThat(((WofStateSnapshot) spinOutcome.snapshot()).wheelSpin().inMotion()).isTrue();

        // Mid-spin the only pending move is the wheel settling - the AI can't guess yet.
        List<AiMove> settleMoves = engine.pendingAiMoves();
        assertThat(settleMoves).hasSize(1);
        settleMoves.get(0).invoke().apply(session);
        assertThat(engine.getPhase()).isEqualTo(WofPhase.AWAITING_LETTER_GUESS);

        List<AiMove> guessMoves = engine.pendingAiMoves();
        assertThat(guessMoves).hasSize(1);
        LiveActionOutcome guessOutcome = guessMoves.get(0).invoke().apply(session);

        assertThat(guessOutcome).isNotNull();
        WofStateSnapshot snap = (WofStateSnapshot) guessOutcome.snapshot();
        // 'H' is in HAWAII BEACH - guaranteed hit, held as round score until the round ends.
        assertThat(snap.roundScores().getOrDefault(aiPlayer.getPlayerId(), 0)).isGreaterThan(0);
    }

    @Test
    void aiOpensItsTurnWithASpinEvenWhenItCouldBuyAVowel() {
        SessionPlayer aiPlayer = SessionPlayer.ai("ai1", "Watson (AI)");
        WofEngine engine = engineForcingAi(aiPlayer, w -> w.getValue() != null);
        GameSession session = new GameSession("SPINFIRST", GameType.WOF, aiPlayer.getPlayerId());
        session.setEngine(engine);
        assertThat(aiPlayer.getScore()).isGreaterThanOrEqualTo(VOWEL_COST); // its Frenzy win

        List<AiMove> moves = engine.pendingAiMoves();

        assertThat(moves).hasSize(1);
        LiveActionOutcome outcome = moves.get(0).invoke().apply(session);
        WofStateSnapshot snap = (WofStateSnapshot) outcome.snapshot();
        assertThat(snap.wheelSpin().inMotion()).isTrue();
        assertThat(snap.guessedVowels()).isEmpty();
    }

    @Test
    void aiBuysVowelsOnceItHasSpunThisTurn() {
        SessionPlayer aiPlayer = SessionPlayer.ai("ai1", "Watson (AI)");
        WofEngine engine = engineForcingAi(aiPlayer, w -> w.getValue() != null);
        GameSession session = new GameSession("SPUNTHEN", GameType.WOF, aiPlayer.getPlayerId());
        session.setEngine(engine);
        engine.pendingAiMoves().get(0).invoke().apply(session); // spins
        engine.pendingAiMoves().get(0).invoke().apply(session); // the wheel settles on a dollar value
        engine.pendingAiMoves().get(0).invoke().apply(session); // calls a real consonant, keeping its turn
        long spinId = engine.toSnapshot(null).wheelSpin().spinId();

        List<AiMove> moves = engine.pendingAiMoves();

        assertThat(moves).hasSize(1);
        WofStateSnapshot snap = (WofStateSnapshot) moves.get(0).invoke().apply(session).snapshot();
        assertThat(snap.guessedVowels()).containsExactly('A');
        assertThat(snap.wheelSpin().spinId()).isEqualTo(spinId);
    }

    @Test
    void aiSpinsFirstAgainOnItsNextTurn() {
        SessionPlayer aiPlayer = SessionPlayer.ai("ai1", "Watson (AI)");
        WofEngine engine = engineForcingAi(aiPlayer, w -> w.getValue() != null);
        GameSession session = new GameSession("NEXTTURN", GameType.WOF, aiPlayer.getPlayerId());
        session.setEngine(engine);
        spinAndSettle(engine, aiPlayer.getPlayerId());
        engine.guessConsonant(aiPlayer.getPlayerId(), 'Z'); // a miss - the turn moves on
        spinAndSettle(engine, "p2");
        engine.guessConsonant("p2", 'Q');
        spinAndSettle(engine, "p3");
        engine.guessConsonant("p3", 'X');

        List<AiMove> moves = engine.pendingAiMoves();

        assertThat(moves).hasSize(1);
        WofStateSnapshot snap = (WofStateSnapshot) moves.get(0).invoke().apply(session).snapshot();
        assertThat(snap.wheelSpin().inMotion()).isTrue();
    }

    @Test
    void aiSkipsTheOpeningSpinWhenOnlyVowelsRemain() {
        SessionPlayer aiPlayer = SessionPlayer.ai("ai1", "Watson (AI)");
        WofEngine engine = engineForcingAi(aiPlayer, w -> w.getValue() != null);
        GameSession session = new GameSession("VOWELSONLY", GameType.WOF, aiPlayer.getPlayerId());
        session.setEngine(engine);
        for (char consonant : new char[] { 'H', 'W', 'B', 'C' }) { // every consonant in HAWAII BEACH
            spinAndSettle(engine, aiPlayer.getPlayerId());
            engine.guessConsonant(aiPlayer.getPlayerId(), consonant);
        }
        missSolve(engine, aiPlayer.getPlayerId()); // the turn moves on
        missSolve(engine, "p2");
        missSolve(engine, "p3");
        long spinId = engine.toSnapshot(null).wheelSpin().spinId();

        List<AiMove> moves = engine.pendingAiMoves();

        // A spin could only lead to calling a consonant, and none are left to find.
        assertThat(moves).hasSize(1);
        WofStateSnapshot snap = (WofStateSnapshot) moves.get(0).invoke().apply(session).snapshot();
        assertThat(snap.guessedVowels()).containsExactly('A');
        assertThat(snap.wheelSpin().spinId()).isEqualTo(spinId);
    }

    @Test
    void aiNeverBuysAVowelOnceThePuzzleIsOutOfThem() {
        // EASY is still far from trying to solve with this much showing (HARD would just solve).
        // Whether any vowels are left is public knowledge, so the rule is the same at every level.
        SessionPlayer aiPlayer = SessionPlayer.ai("ai1", "Watson (AI)");
        WofEngine engine = engineForcingAi(aiPlayer, w -> w.getValue() != null, AiDifficulty.EASY);
        GameSession session = new GameSession("NOVOWELS", GameType.WOF, aiPlayer.getPlayerId());
        session.setEngine(engine);
        spinAndSettle(engine, aiPlayer.getPlayerId());
        engine.guessConsonant(aiPlayer.getPlayerId(), 'H'); // a hit - it keeps its turn
        for (char vowel : new char[] { 'A', 'I', 'E' }) { // every vowel in HAWAII BEACH
            engine.buyVowel(aiPlayer.getPlayerId(), vowel);
        }
        assertThat(aiPlayer.getScore()).isGreaterThanOrEqualTo(VOWEL_COST); // it could afford another

        List<AiMove> moves = engine.pendingAiMoves();

        assertThat(moves).hasSize(1);
        WofStateSnapshot snap = (WofStateSnapshot) moves.get(0).invoke().apply(session).snapshot();
        assertThat(snap.guessedVowels()).containsExactly('A', 'I', 'E');
        assertThat(snap.wheelSpin().inMotion()).isTrue();
    }

    @Test
    void noSpinIsAllowedOnceOnlyVowelsRemain() {
        WofEngine engine = engineForcing(w -> w.getValue() != null);
        for (char consonant : new char[] { 'H', 'W', 'B', 'C' }) { // every consonant in HAWAII BEACH
            spinAndSettle(engine, "p1");
            engine.guessConsonant("p1", consonant);
        }

        WofStateSnapshot snap = engine.toSnapshot(null);
        assertThat(snap.consonantsLeft()).isFalse();
        assertThat(snap.vowelsLeft()).isTrue();
        assertThatThrownBy(() -> engine.spin("p1"))
                .isInstanceOf(InvalidActionException.class)
                .hasMessageContaining("Only vowels remain");
    }

    @Test
    void noVowelCanBeBoughtOnceTheVowelsAreGone() {
        WofEngine engine = engineForcing(w -> w.getValue() != null);
        for (char vowel : new char[] { 'A', 'I', 'E' }) { // every vowel in HAWAII BEACH
            engine.buyVowel("p1", vowel);
        }
        assertThat(alice.getScore()).isGreaterThanOrEqualTo(VOWEL_COST); // money isn't what stops it

        WofStateSnapshot snap = engine.toSnapshot(null);
        assertThat(snap.vowelsLeft()).isFalse();
        assertThat(snap.consonantsLeft()).isTrue();
        assertThatThrownBy(() -> engine.buyVowel("p1", 'O'))
                .isInstanceOf(InvalidActionException.class)
                .hasMessageContaining("no more vowels");
    }

    @Test
    void aiSolvesWhenOnlyVowelsRemainAndItCannotAffordOne() {
        SessionPlayer aiPlayer = SessionPlayer.ai("ai1", "Watson (AI)");
        WofEngine engine = engineForcingAi(aiPlayer, w -> w.getValue() != null);
        GameSession session = new GameSession("SOLVELEFT", GameType.WOF, aiPlayer.getPlayerId());
        session.setEngine(engine);
        missSolve(engine, aiPlayer.getPlayerId());
        for (char consonant : new char[] { 'H', 'W', 'B', 'C' }) { // Bob reveals every consonant
            spinAndSettle(engine, "p2");
            engine.guessConsonant("p2", consonant);
        }
        missSolve(engine, "p2");
        missSolve(engine, "p3");
        aiPlayer.setScore(0); // no Frenzy money to spend on a vowel

        List<AiMove> moves = engine.pendingAiMoves();

        // No spin is allowed and it can't buy a vowel, so a solve is its only legal move.
        assertThat(moves).hasSize(1);
        moves.get(0).invoke().apply(session);
        assertThat(engine.getPhase()).isEqualTo(WofPhase.AWAITING_SOLVE);
    }

    @Test
    void aiBanksSolveAttemptOnceEnoughOfThePuzzleIsRevealed() {
        SessionPlayer aiPlayer = SessionPlayer.ai("ai1", "Watson (AI)");
        WofEngine engine = engineForcingAi(aiPlayer, w -> w.getValue() != null);
        GameSession session = new GameSession("TESTCODE2", GameType.WOF, aiPlayer.getPlayerId());
        session.setEngine(engine);

        // Reveal most of "HAWAII BEACH" by guessing its real consonants/vowels one at a time.
        for (char letter : new char[] { 'H', 'B', 'C', 'A', 'I', 'E' }) {
            boolean isVowel = "AEIOU".indexOf(letter) >= 0;
            if (engine.getPhase() == WofPhase.GAME_OVER || engine.getPhase() == WofPhase.ROUND_OVER) {
                break;
            }
            if (isVowel) {
                aiPlayer.setScore(1000);
                engine.buyVowel(aiPlayer.getPlayerId(), letter);
            } else if (engine.getPhase() == WofPhase.WAITING_FOR_TURN_ACTION) {
                spinAndSettle(engine, aiPlayer.getPlayerId());
                if (engine.getPhase() == WofPhase.AWAITING_LETTER_GUESS) {
                    engine.guessConsonant(aiPlayer.getPlayerId(), letter);
                }
            }
        }

        List<AiMove> moves = engine.pendingAiMoves();
        if (engine.getPhase() == WofPhase.WAITING_FOR_TURN_ACTION) {
            // Enough of the puzzle was revealed that the AI should go for the solve - which is now a
            // two-tick declare-then-submit sequence, like Speed-up's own AI path.
            assertThat(moves).hasSize(1);
            LiveActionOutcome beginOutcome = moves.get(0).invoke().apply(session);
            assertThat(beginOutcome).isNotNull();
            assertThat(engine.getPhase()).isEqualTo(WofPhase.AWAITING_SOLVE);

            LiveActionOutcome outcome = engine.attemptSolve(aiPlayer.getPlayerId(), "hawaii beach");
            assertThat(outcome).isNotNull();
            WofStateSnapshot snap = (WofStateSnapshot) outcome.snapshot();
            assertThat(snap.phase()).isIn("ROUND_OVER", "GAME_OVER");
        }
    }

    @Test
    void weakerAiSometimesCallsALetterThatIsntInThePuzzle() {
        // The bot's letter choice is a coin flip against its difficulty, so this samples repeatedly:
        // at EASY's 35% accuracy a miss in 40 tries is a certainty in any practical sense, while
        // HARD must always find a real letter.
        assertThat(sampleAiLetterMisses(AiDifficulty.EASY, 40)).isGreaterThan(0);
        assertThat(sampleAiLetterMisses(AiDifficulty.HARD, 40)).isZero();
    }

    /** How many of {@code trials} AI consonant calls aren't in "HAWAII BEACH" at all. */
    private int sampleAiLetterMisses(AiDifficulty difficulty, int trials) {
        int misses = 0;
        for (int i = 0; i < trials; i++) {
            SessionPlayer aiPlayer = SessionPlayer.ai("ai1", "Bot");
            List<SessionPlayer> playersList = List.of(aiPlayer, bob, carol);
            WofEngine engine = new WofEngine(playersList, wofService, Duration.ZERO, difficulty) {
                @Override
                protected int chooseWedgeIndex(List<Wedge> wheel) {
                    for (int w = 0; w < wheel.size(); w++) {
                        if (wheel.get(w).getValue() != null) {
                            return w;
                        }
                    }
                    throw new IllegalStateException("No dollar wedge in the wheel.");
                }
            };
            GameSession session = new GameSession("MISS" + i, GameType.WOF, aiPlayer.getPlayerId());
            session.setEngine(engine);
            playOutFrenzy(engine, aiPlayer.getPlayerId());
            aiPlayer.setScore(0); // can't afford a vowel, so it has to spin and call a consonant
            spinAndSettle(engine, aiPlayer.getPlayerId());

            List<AiMove> moves = engine.pendingAiMoves();
            moves.get(0).invoke().apply(session);
            if (engine.toSnapshot(null).roundScores().getOrDefault(aiPlayer.getPlayerId(), 0) == 0) {
                misses++;
            }
        }
        return misses;
    }

    @Test
    void aiOnlyGameReachesGameOverOnEveryDifficulty() {
        for (AiDifficulty difficulty : AiDifficulty.values()) {
            assertThat(playOutAiOnlyGame(difficulty))
                    .as("%s stalled", difficulty)
                    .isEqualTo(WofPhase.GAME_OVER);
        }
    }

    private WofPhase playOutAiOnlyGame(AiDifficulty difficulty) {
        SessionPlayer ai1 = SessionPlayer.ai("ai1", "Bot1");
        SessionPlayer ai2 = SessionPlayer.ai("ai2", "Bot2");
        SessionPlayer ai3 = SessionPlayer.ai("ai3", "Bot3");
        WofEngine engine = new WofEngine(List.of(ai1, ai2, ai3), wofService, Duration.ZERO, difficulty);
        GameSession session = new GameSession("AI" + difficulty, GameType.WOF, ai1.getPlayerId());
        session.setEngine(engine);

        int steps = 0;
        while (engine.getPhase() != WofPhase.GAME_OVER && steps < 20_000) {
            List<AiMove> moves = engine.pendingAiMoves();
            if (moves.isEmpty()) {
                break;
            }
            for (AiMove move : moves) {
                try {
                    move.invoke().apply(session);
                } catch (RuntimeException ignored) {
                    // Stale by the time it fires - exactly what the real scheduler swallows.
                }
            }
            steps++;
        }
        return engine.getPhase();
    }

    @Test
    void aiOnlyGameReachesGameOverWithoutStallingGivenRoundAdvanceIsDriven() {
        SessionPlayer ai1 = SessionPlayer.ai("ai1", "Bot1");
        SessionPlayer ai2 = SessionPlayer.ai("ai2", "Bot2");
        SessionPlayer ai3 = SessionPlayer.ai("ai3", "Bot3");
        // A zero-length game clock so Round 4+ heads straight to the Final Spin instead of playing
        // open-ended rounds for the full 20-minute default (which real wall-clock time never reaches
        // in a fast test loop).
        WofEngine engine = new WofEngine(List.of(ai1, ai2, ai3), wofService, Duration.ZERO, AiDifficulty.HARD);
        GameSession session = new GameSession("AITEST", GameType.WOF, ai1.getPlayerId());
        session.setEngine(engine);

        int steps = 0;
        int maxSteps = 5000;
        while (engine.getPhase() != WofPhase.GAME_OVER && steps < maxSteps) {
            List<AiMove> moves = engine.pendingAiMoves();
            if (moves.isEmpty()) {
                if (engine.getPhase() == WofPhase.ROUND_OVER) {
                    // In production this is scheduled by WheelOfFortuneWsController, not the
                    // generic AI hook - simulate that here to isolate whether anything ELSE stalls.
                    engine.continueToNextRound("p1");
                    steps++;
                    continue;
                }
                break;
            }
            for (AiMove move : moves) {
                try {
                    move.invoke().apply(session);
                } catch (RuntimeException ignored) {
                    // Mirrors LiveGameOrchestrator.scheduleDelayed's safety net: a queued AI move
                    // whose target phase has since moved on (e.g. a second toss-up contestant
                    // after the first already solved it, or a second buzz-in attempt after the
                    // buzzer's already held) is expected to no-op, not blow up.
                }
            }
            steps++;
        }

        assertThat(engine.getPhase())
                .as("stalled after %d steps in phase %s", steps, engine.getPhase())
                .isEqualTo(WofPhase.GAME_OVER);
    }

    @Test
    void beginFrenzySolveRejectedWhenAnotherPlayerIsAlreadyAnswering() {
        WofEngine engine = new WofEngine(List.of(alice, bob, carol), wofService);
        engine.beginFrenzySolve("p1");

        assertThatThrownBy(() -> engine.beginFrenzySolve("p2")).isInstanceOf(InvalidActionException.class);
    }

    @Test
    void aWrongFrenzyAnswerThrowsThePuzzleOutForEveryone() {
        WofEngine engine = new WofEngine(List.of(alice, bob, carol), wofService);
        engine.beginFrenzySolve("p1");

        WofStateSnapshot snap = (WofStateSnapshot) engine.attemptFrenzyAnswer("p1", "wrong guess").snapshot();

        // One ring-in settles the puzzle: it's revealed and discarded, and nobody else gets a go.
        assertThat(snap.phase()).isEqualTo("FRENZY_PUZZLE_OVER");
        assertThat(snap.frenzyOutcome()).isEqualTo("MISSED");
        assertThat(snap.frenzyOutcomePlayerId()).isEqualTo("p1");
        assertThat(snap.puzzleTemplate()).isEqualTo("HAWAII BEACH");
        assertThat(snap.puzzleLockedOutPlayerIds()).isEmpty();
        assertThatThrownBy(() -> engine.beginFrenzySolve("p2")).isInstanceOf(InvalidActionException.class);
        // There's no other penalty.
        assertThat(alice.getScore()).isZero();
    }

    @Test
    void noFrenzyAnswerInTimeThrowsThePuzzleOutToo() {
        WofEngine engine = new WofEngine(List.of(alice, bob, carol), wofService);
        engine.beginFrenzySolve("p1");

        WofStateSnapshot snap = (WofStateSnapshot) engine.handleFrenzySolveTimeout(engine.getVersion()).snapshot();

        assertThat(snap.phase()).isEqualTo("FRENZY_PUZZLE_OVER");
        assertThat(snap.frenzyOutcome()).isEqualTo("MISSED");
        assertThat(snap.frenzySolvingPlayerId()).isNull();
        assertThat(alice.getScore()).isZero();
    }

    @Test
    void aCorrectFrenzyAnswerWinsAThousandDollars() {
        WofEngine engine = new WofEngine(List.of(alice, bob, carol), wofService);
        engine.beginFrenzySolve("p2");

        WofStateSnapshot snap = (WofStateSnapshot) engine.attemptFrenzyAnswer("p2", "hawaii beach").snapshot();

        assertThat(bob.getScore()).isEqualTo(1000);
        assertThat(snap.phase()).isEqualTo("FRENZY_PUZZLE_OVER");
        assertThat(snap.frenzyOutcome()).isEqualTo("SOLVED");
        assertThat(snap.frenzyOutcomePlayerId()).isEqualTo("p2");
    }

    @Test
    void frenzyBoardStopsFillingInWhileAPlayerIsAnswering() {
        WofEngine engine = new WofEngine(List.of(alice, bob, carol), wofService);
        engine.beginFrenzySolve("p1");
        WofStateSnapshot before = engine.toSnapshot(null);

        // The reveal tick is a complete no-op while someone's mid-answer - nothing about the board
        // or the attempt changes at all.
        assertThat(engine.revealNextFrenzyLetter(engine.getVersion())).isNull();

        WofStateSnapshot after = engine.toSnapshot(null);
        assertThat(after.frenzySolvingPlayerId()).isEqualTo("p1");
        assertThat(after.frenzySolveDeadlineAt()).isEqualTo(before.frenzySolveDeadlineAt());
        assertThat(after.puzzleTemplate()).isEqualTo(before.puzzleTemplate());
    }

    @Test
    void frenzyRevealsALetterEveryFourTenthsOfASecond() {
        WofEngine engine = new WofEngine(List.of(alice, bob, carol), wofService);

        List<AiMove> moves = engine.pendingAiMoves(); // the clock's deadline, and the next reveal

        assertThat(moves).extracting(AiMove::delay).contains(Duration.ofMillis(400));
    }

    @Test
    void frenzyRevealsOneBoardPositionAtATimeNotEveryOccurrenceOfALetter() {
        WofEngine engine = new WofEngine(List.of(alice, bob, carol), wofService);
        // "HAWAII BEACH" has 11 letter cells (A x3, I x2, H x2). If reveals were keyed by letter
        // identity, landing on 'A' would expose all 3 A's at once and the whole puzzle would be
        // fully revealed in just 7 ticks (its 7 unique letters). A true per-position reveal must
        // take exactly 11 ticks - one new cell filled in per tick, never more.
        for (int tick = 1; tick <= 11; tick++) {
            engine.revealNextFrenzyLetter(engine.getVersion());
            String template = engine.toSnapshot(null).puzzleTemplate();
            long revealedCells = template.chars().filter(Character::isLetter).count();
            assertThat(revealedCells).as("revealed letter cells after tick %d", tick).isEqualTo(tick);
            assertThat(engine.getPhase()).as("phase after tick %d", tick).isEqualTo(WofPhase.FORTUNE_FRENZY);
        }

        // The next tick finds no blank cells left - nobody rang in, so the puzzle goes unsolved.
        engine.revealNextFrenzyLetter(engine.getVersion());
        assertThat(engine.getPhase()).isEqualTo(WofPhase.FRENZY_PUZZLE_OVER);
        assertThat(engine.toSnapshot(null).frenzyOutcome()).isEqualTo("UNSOLVED");
    }

    @Test
    void everyFrenzyPuzzleComesFromTheSameCategory() {
        WheelOfFortuneService service = mock(WheelOfFortuneService.class);
        List<Puzzle> food = List.of(puzzle(10L, "FOOD", "APPLE PIE"), puzzle(11L, "FOOD", "HOT DOG"),
                puzzle(12L, "FOOD", "TACO"), puzzle(13L, "FOOD", "PIZZA"));
        List<Puzzle> place = List.of(puzzle(20L, "PLACE", "PARIS"), puzzle(21L, "PLACE", "ROME"));
        when(service.getAllCategories()).thenReturn(Set.of("FOOD", "PLACE"));
        when(service.getPuzzlesByCategory("FOOD")).thenReturn(food);
        when(service.getPuzzlesByCategory("PLACE")).thenReturn(place);
        when(service.getAllPuzzles()).thenReturn(Stream.concat(food.stream(), place.stream()).toList());
        WofEngine engine = new WofEngine(List.of(alice, bob, carol), service);

        Set<Long> seen = new HashSet<>();
        // More puzzles than FOOD has: once they've all been used they come round again, rather
        // than the Frenzy switching category.
        for (int puzzle = 0; puzzle < 6; puzzle++) {
            assertThat(engine.toSnapshot(null).category()).isEqualTo("FOOD");
            seen.add(engine.currentPuzzleForTesting().getId());
            fillInFrenzyBoard(engine);
            engine.continueFrenzy("p1");
        }
        assertThat(seen).containsExactlyInAnyOrder(10L, 11L, 12L, 13L);
    }

    @Test
    void theFrenzyClockRunsThirtySecondsAndStopsWhileASolutionIsUp() throws InterruptedException {
        WofEngine engine = new WofEngine(List.of(alice, bob, carol), wofService);
        WofStateSnapshot running = engine.toSnapshot(null);
        assertThat(running.frenzyWindowMs()).isEqualTo(30_000L);
        assertThat(Duration.between(running.frenzyStartedAt(), running.frenzyDeadlineAt())).isEqualTo(Duration.ofSeconds(30));

        engine.beginFrenzySolve("p1");
        WofStateSnapshot stopped = (WofStateSnapshot) engine.attemptFrenzyAnswer("p1", "hawaii beach").snapshot();
        assertThat(stopped.frenzyDeadlineAt()).isNull();
        long frozen = stopped.frenzyRemainingMs();
        assertThat(frozen).isBetween(25_000L, 30_000L);

        Thread.sleep(50);
        // The clock doesn't move while the solution is up...
        assertThat(engine.toSnapshot(null).frenzyRemainingMs()).isEqualTo(frozen);

        // ...and picks up from there with the next puzzle.
        WofStateSnapshot resumed = (WofStateSnapshot) engine.continueFrenzy("p1").snapshot();
        assertThat(resumed.frenzyPuzzleNumber()).isEqualTo(2);
        assertThat(Duration.between(Instant.now(), resumed.frenzyDeadlineAt()).toMillis()).isBetween(frozen - 1000, frozen);
    }

    @Test
    void theNextFrenzyPuzzleComesUpOnItsOwnAfterAShortPause() {
        SessionPlayer aiOpponent = SessionPlayer.ai("ai1", "Watson (AI)");
        WofEngine engine = new WofEngine(List.of(alice, aiOpponent, carol), wofService);
        GameSession session = new GameSession("FRENZYNEXT", GameType.WOF, "p1");
        session.setEngine(engine);
        engine.beginFrenzySolve("p1");
        engine.attemptFrenzyAnswer("p1", "hawaii beach");

        List<AiMove> moves = engine.pendingAiMoves();

        // Just the auto-advance - nobody, bot or human, has to click anything mid-Frenzy.
        assertThat(moves).hasSize(1);
        assertThat(moves.get(0).delay().toMillis()).isBetween(2000L, 2500L);
        moves.get(0).invoke().apply(session);
        assertThat(engine.getPhase()).isEqualTo(WofPhase.FORTUNE_FRENZY);
        assertThat(engine.toSnapshot(null).frenzyPuzzleNumber()).isEqualTo(2);
    }

    @Test
    void onceTheFrenzyIsOverBotsClickContinueIntoRoundOne() {
        SessionPlayer aiOpponent = SessionPlayer.ai("ai1", "Watson (AI)");
        WofEngine engine = new WofEngine(List.of(alice, aiOpponent, carol), wofService);
        engine.handleFrenzyTimeout(engine.getVersion());
        assertThat(engine.getPhase()).isEqualTo(WofPhase.FRENZY_PUZZLE_OVER);

        List<AiMove> moves = engine.pendingAiMoves();

        // Only the one AI player (alice/carol are human) - exactly one Continue click, and the
        // Frenzy never moves on by itself once it's over.
        assertThat(moves).hasSize(1);
        assertThat(engine.advanceFrenzyAfterPause(engine.getVersion())).isNull();
    }

    @Test
    void frenzyKeepsCyclingPuzzlesUntilTimeoutThenFirstPlayerStartsIfNobodySolved() {
        WofEngine engine = new WofEngine(List.of(alice, bob, carol), wofService);

        for (int cycle = 0; cycle < 3; cycle++) {
            fillInFrenzyBoard(engine);
            engine.continueFrenzy("p1");
        }
        assertThat(engine.getPhase()).isEqualTo(WofPhase.FORTUNE_FRENZY);

        engine.handleFrenzyTimeout(engine.getVersion());
        LiveActionOutcome ended = engine.continueFrenzy("p2");
        WofStateSnapshot snap = (WofStateSnapshot) ended.snapshot();
        assertThat(snap.phase()).isEqualTo("WAITING_FOR_TURN_ACTION");
        assertThat(snap.currentPlayerId()).isEqualTo("p1");
        assertThat(snap.roundNumber()).isEqualTo(1);
    }

    @Test
    void theLastCorrectFrenzyAnswerStartsRoundOne() {
        WofEngine engine = new WofEngine(List.of(alice, bob, carol), wofService);
        engine.beginFrenzySolve("p1");
        engine.attemptFrenzyAnswer("p1", "hawaii beach");
        engine.continueFrenzy("p1");
        engine.beginFrenzySolve("p3");
        engine.attemptFrenzyAnswer("p3", "hawaii beach");
        engine.continueFrenzy("p1");
        engine.beginFrenzySolve("p1");
        engine.attemptFrenzyAnswer("p1", "wrong guess"); // a miss doesn't change who solved last
        engine.continueFrenzy("p1");
        engine.handleFrenzyTimeout(engine.getVersion());

        assertThat(engine.toSnapshot(null).frenzyRoundOneStarterId()).isEqualTo("p3");

        WofStateSnapshot round1 = (WofStateSnapshot) engine.continueFrenzy("p2").snapshot();

        assertThat(round1.phase()).isEqualTo("WAITING_FOR_TURN_ACTION");
        assertThat(round1.currentPlayerId()).isEqualTo("p3");
        assertThat(round1.frenzyRoundOneStarterId()).isNull(); // only meaningful during the Frenzy
        assertThat(alice.getScore()).isEqualTo(1000);
        assertThat(carol.getScore()).isEqualTo(1000);
    }

    @Test
    void continueFrenzyRejectedWhenPuzzleIsNotOver() {
        WofEngine engine = new WofEngine(List.of(alice, bob, carol), wofService);

        assertThatThrownBy(() -> engine.continueFrenzy("p1")).isInstanceOf(InvalidActionException.class);
    }

    @Test
    void continueFrenzyEndsFrenzyOutrightWhenTheWindowHasAlreadyRunOut() {
        // A Frenzy clock that's already in the past the instant the engine is constructed - by the
        // time the puzzle resolves and Continue is clicked, the budget is already spent, so Continue
        // ends Frenzy outright instead of starting yet another puzzle.
        WofEngine engine = new WofEngine(List.of(alice, bob, carol), wofService) {
            @Override
            protected Duration frenzyWindow() {
                return Duration.ofSeconds(-1);
            }
        };
        engine.beginFrenzySolve("p1");
        engine.attemptFrenzyAnswer("p1", "hawaii beach"); // -> FRENZY_PUZZLE_OVER

        LiveActionOutcome outcome = engine.continueFrenzy("p1");

        WofStateSnapshot snap = (WofStateSnapshot) outcome.snapshot();
        assertThat(snap.phase()).isEqualTo("WAITING_FOR_TURN_ACTION"); // Round 1, not another puzzle
        assertThat(snap.currentPlayerId()).isEqualTo("p1"); // most recent solver starts Round 1
    }

    @Test
    void lessThanASecondLeftEndsTheFrenzyRatherThanFlashingUpAnotherPuzzle() {
        WofEngine engine = new WofEngine(List.of(alice, bob, carol), wofService) {
            @Override
            protected Duration frenzyWindow() {
                return Duration.ofMillis(500);
            }
        };
        engine.beginFrenzySolve("p1");

        WofStateSnapshot snap = (WofStateSnapshot) engine.attemptFrenzyAnswer("p1", "hawaii beach").snapshot();

        assertThat(snap.frenzyTimeUp()).isTrue();
        assertThat(snap.frenzyRemainingMs()).isZero();
        assertThat(((WofStateSnapshot) engine.continueFrenzy("p2").snapshot()).phase()).isEqualTo("WAITING_FOR_TURN_ACTION");
    }

    @Test
    void handleFrenzyTimeoutIsNoOpWhenVersionIsStale() {
        WofEngine engine = new WofEngine(List.of(alice, bob, carol), wofService);
        engine.beginFrenzySolve("p1");
        long staleVersion = engine.getVersion() - 1;

        LiveActionOutcome result = engine.handleFrenzyTimeout(staleVersion);

        assertThat(result).isNull();
    }

    @Test
    void ringingInJustBeforeTheClockRunsOutStillGetsAnAnswer() {
        WofEngine engine = new WofEngine(List.of(alice, bob, carol), wofService);
        engine.beginFrenzySolve("p1");
        WofStateSnapshot before = engine.toSnapshot(null);

        LiveActionOutcome timeUp = engine.handleFrenzyTimeout(engine.getVersion());

        // Nothing about Alice's attempt changes: same puzzle, same buzzer, same answer window.
        WofStateSnapshot snap = (WofStateSnapshot) timeUp.snapshot();
        assertThat(snap.phase()).isEqualTo("FORTUNE_FRENZY");
        assertThat(snap.frenzyTimeUp()).isTrue();
        assertThat(snap.frenzySolvingPlayerId()).isEqualTo("p1");
        assertThat(snap.frenzySolveDeadlineAt()).isEqualTo(before.frenzySolveDeadlineAt());
        assertThat(snap.puzzleTemplate()).isEqualTo(before.puzzleTemplate());

        // Her answer still counts, and only after it does Frenzy wrap up - straight into Round 1.
        engine.attemptFrenzyAnswer("p1", "hawaii beach");
        assertThat(alice.getScore()).isEqualTo(1000);
        assertThat(engine.getPhase()).isEqualTo(WofPhase.FRENZY_PUZZLE_OVER);
        WofStateSnapshot round1 = (WofStateSnapshot) engine.continueFrenzy("p2").snapshot();
        assertThat(round1.phase()).isEqualTo("WAITING_FOR_TURN_ACTION");
        assertThat(round1.roundNumber()).isEqualTo(1);
        assertThat(round1.currentPlayerId()).isEqualTo("p1");
    }

    @Test
    void theClockRunningOutWithNobodyAnsweringEndsTheFrenzyOnTheSpot() {
        WofEngine engine = new WofEngine(List.of(alice, bob, carol), wofService);
        engine.revealNextFrenzyLetter(engine.getVersion());

        WofStateSnapshot snap = (WofStateSnapshot) engine.handleFrenzyTimeout(engine.getVersion()).snapshot();

        assertThat(snap.phase()).isEqualTo("FRENZY_PUZZLE_OVER");
        assertThat(snap.frenzyTimeUp()).isTrue();
        assertThat(snap.frenzyOutcome()).isEqualTo("UNSOLVED");
        assertThat(snap.puzzleTemplate()).isEqualTo("HAWAII BEACH");
        assertThat(snap.frenzyRemainingMs()).isZero();
        assertThatThrownBy(() -> engine.beginFrenzySolve("p1")).isInstanceOf(InvalidActionException.class);
    }

    @Test
    void frenzyTimeoutOnlyFiresOnce() {
        WofEngine engine = new WofEngine(List.of(alice, bob, carol), wofService);
        engine.handleFrenzyTimeout(engine.getVersion());

        assertThat(engine.handleFrenzyTimeout(engine.getVersion())).isNull();
        assertThat(engine.getPhase()).isEqualTo(WofPhase.FRENZY_PUZZLE_OVER);
    }

    @Test
    void aBotOnlyRingsInOnceEnoughOfTheFrenzyBoardShows() {
        SessionPlayer bot = SessionPlayer.ai("ai1", "Bot");
        // HARD rings in every time it can, once 60% of the board shows.
        WofEngine engine = new WofEngine(List.of(alice, bot, carol), wofService, Duration.ofMinutes(20), AiDifficulty.HARD);
        for (int i = 0; i < 6; i++) { // 6 of HAWAII BEACH's 11 letter cells
            engine.revealNextFrenzyLetter(engine.getVersion());
        }
        assertThat(engine.pendingAiMoves()).hasSize(2); // just the clock and the next reveal

        engine.revealNextFrenzyLetter(engine.getVersion()); // 7 of 11

        assertThat(engine.pendingAiMoves()).hasSize(3); // ...plus the bot ringing in
    }

    @Test
    void aBotsRingInMeantForAThrownOutPuzzleCantLandOnTheNextOne() {
        SessionPlayer bot = SessionPlayer.ai("ai1", "Bot");
        WofEngine engine = new WofEngine(List.of(alice, bot, carol), wofService, Duration.ofMinutes(20), AiDifficulty.HARD);
        engine.beginFrenzySolve("p1");
        engine.attemptFrenzyAnswer("p1", "wrong guess");
        engine.continueFrenzy("p1"); // puzzle 2

        assertThatThrownBy(() -> engine.aiBeginFrenzySolve("ai1", 1)).isInstanceOf(InvalidActionException.class);
        assertThat(engine.toSnapshot(null).frenzySolvingPlayerId()).isNull();

        engine.aiBeginFrenzySolve("ai1", 2); // one decided on this puzzle still counts...
        assertThat(engine.toSnapshot(null).frenzySolvingPlayerId()).isEqualTo("ai1");
        // ...but an answer worked out for the last one never gets submitted against it.
        assertThatThrownBy(() -> engine.aiAttemptFrenzyAnswer("ai1", 1, "hawaii beach"))
                .isInstanceOf(InvalidActionException.class);
    }

    @Test
    void everyTimedWindowReportsWhenItOpenedAsWellAsWhenItCloses() {
        WofEngine frenzyEngine = new WofEngine(List.of(alice, bob, carol), wofService, Duration.ofMinutes(20));
        WofStateSnapshot frenzy = (WofStateSnapshot) frenzyEngine.beginFrenzySolve("p1").snapshot();
        assertThat(Duration.between(frenzy.frenzyStartedAt(), frenzy.frenzyDeadlineAt())).isEqualTo(Duration.ofSeconds(30));
        assertThat(Duration.between(frenzy.frenzySolveStartedAt(), frenzy.frenzySolveDeadlineAt()))
                .isEqualTo(Duration.ofSeconds(10));
        assertThat(Duration.between(frenzy.gameClockStartedAt(), frenzy.gameClockEndsAt())).isEqualTo(Duration.ofMinutes(20));

        WofEngine engine = engineAtTripleTossUp();
        WofStateSnapshot tossUp = (WofStateSnapshot) engine.beginTripleTossUpSolve("p1").snapshot();
        assertThat(Duration.between(tossUp.tripleTossUpSolveStartedAt(), tossUp.tripleTossUpSolveDeadlineAt()))
                .isEqualTo(Duration.ofSeconds(30));

        WofStateSnapshot round = (WofStateSnapshot) engineForcing().beginSolve("p1").snapshot();
        assertThat(Duration.between(round.solveStartedAt(), round.solveDeadlineAt())).isEqualTo(Duration.ofSeconds(30));
    }

    // ---- Special wedges: the Wild Card and the Million Dollar Wedge -----------------------------

    private static boolean hasWedge(WofStateSnapshot snap, String effect) {
        return snap.wheel().stream().anyMatch(w -> effect.equals(w.effect()));
    }

    @Test
    void bothSpecialWedgesStartTheGameOnTheWheel() {
        WofStateSnapshot snap = engineForcing().toSnapshot(null);

        assertThat(hasWedge(snap, Wedge.EFFECT_WILD_CARD)).isTrue();
        assertThat(hasWedge(snap, Wedge.EFFECT_MILLION)).isTrue();
        assertThat(snap.wildCardHolderId()).isNull();
        assertThat(snap.millionWedgeHolderId()).isNull();
    }

    @Test
    void landingOnTheWildCardAndCallingARightConsonantClaimsIt() {
        WofEngine engine = engineForcing(Wedge::isWildCard);
        WofStateSnapshot landed = (WofStateSnapshot) spinAndSettle(engine, "p1").snapshot();
        assertThat(landed.phase()).isEqualTo("AWAITING_LETTER_GUESS");
        assertThat(landed.lastSpinResult().label()).isEqualTo("WILD CARD");
        assertThat(landed.lastSpinResult().value()).isEqualTo(500);

        WofStateSnapshot snap = (WofStateSnapshot) engine.guessConsonant("p1", 'H').snapshot(); // two H's

        assertThat(snap.roundScores().get("p1")).isEqualTo(1000); // $500 a letter, the wedge underneath
        assertThat(snap.wildCardHolderId()).isEqualTo("p1");
        assertThat(hasWedge(snap, Wedge.EFFECT_WILD_CARD)).isFalse(); // off the wheel...
        assertThat(snap.lastSpinResult().label()).isEqualTo("WILD CARD"); // ...but still what this spin won
        assertThat(snap.wheel().get(snap.lastSpinIndex()).label()).isEqualTo("$500"); // what's under the pointer now
    }

    @Test
    void aClaimOnThePuzzlesLastLetterIsStillAnnouncedAndKept() {
        Predicate<Wedge> cash = w -> w.getValue() != null && !w.isWildCard() && !w.isMillion();
        WofEngine engine = engineSpinning(cash, cash, cash, Wedge::isWildCard);
        for (char consonant : new char[] { 'W', 'B', 'C' }) {
            spinAndSettle(engine, "p1");
            engine.guessConsonant("p1", consonant);
        }
        for (char vowel : new char[] { 'A', 'I', 'E' }) {
            engine.buyVowel("p1", vowel);
        }
        spinAndSettle(engine, "p1"); // the Wild Card

        LiveActionOutcome outcome = engine.guessConsonant("p1", 'H'); // the last letters on the board

        WofStateSnapshot snap = (WofStateSnapshot) outcome.snapshot();
        assertThat(snap.phase()).isEqualTo("ROUND_OVER");
        assertThat(snap.wildCardHolderId()).isEqualTo("p1");
        assertThat(outcome.announceText()).contains("claimed the Wild Card").contains("solved the puzzle");
    }

    @Test
    void aWrongConsonantLeavesTheWildCardOnTheWheel() {
        WofEngine engine = engineForcing(Wedge::isWildCard);
        spinAndSettle(engine, "p1");

        WofStateSnapshot snap = (WofStateSnapshot) engine.guessConsonant("p1", 'Z').snapshot();

        assertThat(snap.wildCardHolderId()).isNull();
        assertThat(hasWedge(snap, Wedge.EFFECT_WILD_CARD)).isTrue();
    }

    @Test
    void theWildCardCallsOneMoreConsonantAtTheSameValueRightAfterARightOne() {
        WofEngine engine = engineForcing(Wedge::isWildCard);
        spinAndSettle(engine, "p1");
        assertThat(((WofStateSnapshot) engine.guessConsonant("p1", 'H').snapshot()).wildCardUsable()).isTrue();

        WofStateSnapshot playing = (WofStateSnapshot) engine.playWildCard("p1").snapshot();
        assertThat(playing.phase()).isEqualTo("AWAITING_LETTER_GUESS");
        assertThat(playing.wildCardInPlay()).isTrue();
        assertThat(playing.wildCardHolderId()).isNull(); // spent the moment it's played

        WofStateSnapshot after = (WofStateSnapshot) engine.guessConsonant("p1", 'W').snapshot(); // one W

        assertThat(after.roundScores().get("p1")).isEqualTo(1000 + 500);
        assertThat(after.currentPlayerId()).isEqualTo("p1");
        assertThat(after.wildCardInPlay()).isFalse();
        assertThat(after.wildCardUsable()).isFalse();
        engine.beginSolve("p1");
        engine.attemptSolve("p1", "hawaii beach");
        engine.continueToNextRound("p1");
        assertThat(hasWedge(engine.toSnapshot(null), Wedge.EFFECT_WILD_CARD)).isFalse(); // played - gone for good
    }

    @Test
    void aMissedWildCardLetterStillUsesItUp() {
        WofEngine engine = engineForcing(Wedge::isWildCard);
        spinAndSettle(engine, "p1");
        engine.guessConsonant("p1", 'H');
        engine.playWildCard("p1");

        WofStateSnapshot snap = (WofStateSnapshot) engine.guessConsonant("p1", 'Z').snapshot();

        assertThat(snap.currentPlayerId()).isEqualTo("p2");
        assertThat(snap.wildCardHolderId()).isNull();
    }

    @Test
    void theWildCardOnlyWorksAsTheVeryNextMove() {
        WofEngine engine = engineForcing(Wedge::isWildCard);
        spinAndSettle(engine, "p1");
        engine.guessConsonant("p1", 'H');

        engine.buyVowel("p1", 'A');

        assertThat(engine.toSnapshot(null).wildCardUsable()).isFalse();
        assertThatThrownBy(() -> engine.playWildCard("p1")).isInstanceOf(InvalidActionException.class)
                .hasMessageContaining("right after");
        assertThat(engine.toSnapshot(null).wildCardHolderId()).isEqualTo("p1"); // still theirs for later
    }

    @Test
    void onlyTheHolderMayPlayTheWildCard() {
        WofEngine engine = engineForcing(w -> w.getValue() != null);
        spinAndSettle(engine, "p1");
        engine.guessConsonant("p1", 'H');

        assertThat(engine.toSnapshot(null).wildCardUsable()).isFalse();
        assertThatThrownBy(() -> engine.playWildCard("p1")).isInstanceOf(InvalidActionException.class)
                .hasMessageContaining("don't have");
    }

    @Test
    void bankruptTakesTheWildCardAndItGoesBackOnTheWheelNextRound() {
        WofEngine engine = engineSpinning(Wedge::isWildCard, Wedge::isBankrupt);
        spinAndSettle(engine, "p1");
        engine.guessConsonant("p1", 'H');

        LiveActionOutcome bankrupt = spinAndSettle(engine, "p1");

        WofStateSnapshot snap = (WofStateSnapshot) bankrupt.snapshot();
        assertThat(bankrupt.announceText()).contains("along with the Wild Card");
        assertThat(snap.wildCardHolderId()).isNull();
        assertThat(hasWedge(snap, Wedge.EFFECT_WILD_CARD)).isFalse(); // not for the rest of this round
        engine.beginSolve("p2");
        engine.attemptSolve("p2", "hawaii beach");
        engine.continueToNextRound("p1");
        assertThat(hasWedge(engine.toSnapshot(null), Wedge.EFFECT_WILD_CARD)).isTrue();
    }

    @Test
    void theWildCardCantBePlayedInTheSpeedUpButAddsAFourthBonusConsonant() {
        // Alice claims the Wild Card in Round 1 and keeps it all game.
        WofEngine engine = engineSpinning(Wedge::isWildCard, w -> w.getValue() != null);
        spinAndSettle(engine, "p1");
        engine.guessConsonant("p1", 'H');
        engine.beginSolve("p1");
        engine.attemptSolve("p1", "hawaii beach");
        aliceReachesTheSpeedUp(engine);

        assertThat(engine.toSnapshot(null).wildCardUsable()).isFalse();
        assertThatThrownBy(() -> engine.playWildCard("p1")).isInstanceOf(InvalidActionException.class);

        engine.speedupGuessLetter("p1", 'H');
        WofStateSnapshot bonus = (WofStateSnapshot) engine.speedupAttemptSolve("p1", "hawaii beach").snapshot();
        assertThat(bonus.bonusConsonantCount()).isEqualTo(4);
        engine.pickBonusCategory("p1", bonus.bonusCategoryOptions().get(0));
        assertThatThrownBy(() -> engine.submitBonusLetters("p1", List.of('H', 'W', 'B'), 'A'))
                .isInstanceOf(InvalidActionException.class);

        WofStateSnapshot letters = (WofStateSnapshot) engine.submitBonusLetters("p1", List.of('H', 'W', 'B', 'C'), 'A')
                .snapshot();

        assertThat(letters.phase()).isEqualTo("BONUS_SOLVE");
        // All four consonants (and the A) are revealed - only the unpicked vowel, I, stays hidden.
        assertThat(letters.bonusPuzzleTemplate()).isEqualTo("HAWA__ BEACH");
        assertThat(letters.wildCardHolderId()).isNull(); // the fourth consonant spent it
    }

    @Test
    void landingOnOneMillionAndCallingARightConsonantPicksUpTheWedge() {
        WofEngine engine = engineForcing(Wedge::isMillion);
        WofStateSnapshot landed = (WofStateSnapshot) spinAndSettle(engine, "p1").snapshot();
        assertThat(landed.lastSpinResult().label()).isEqualTo("ONE MILLION");
        assertThat(landed.lastSpinResult().slotCount()).isEqualTo(1);
        int wedgesBefore = landed.wheel().size();

        WofStateSnapshot snap = (WofStateSnapshot) engine.guessConsonant("p1", 'H').snapshot();

        assertThat(snap.roundScores().get("p1")).isEqualTo(1000); // plays for the $500 underneath
        assertThat(snap.millionWedgeHolderId()).isEqualTo("p1");
        assertThat(snap.millionWedgeSecured()).isFalse(); // not until this round is solved
        // The wedge comes off - Bankrupt flanks and all - leaving the plain pink $500 under the pointer.
        assertThat(snap.wheel()).hasSize(wedgesBefore - 2);
        assertThat(hasWedge(snap, Wedge.EFFECT_MILLION)).isFalse();
        WedgeView underPointer = snap.wheel().get(snap.lastSpinIndex());
        assertThat(underPointer.label()).isEqualTo("$500");
        assertThat(snap.wheelSpin().landingSlot())
                .isBetween(underPointer.startSlot(), underPointer.startSlot() + underPointer.slotCount() - 1);
    }

    @Test
    void theMillionsFlanksAreOrdinaryBankrupts() {
        WofEngine engine = engineForcing(w -> w.isBankrupt() && w.getSlotCount() == 1);

        WofStateSnapshot snap = (WofStateSnapshot) spinAndSettle(engine, "p1").snapshot();

        assertThat(snap.lastSpinResult().label()).isEqualTo("BANKRUPT");
        assertThat(snap.lastSpinResult().slotCount()).isEqualTo(1);
        assertThat(snap.currentPlayerId()).isEqualTo("p2");
    }

    @Test
    void solvingTheRoundBanksTheMillionDollarWedge() {
        WofEngine engine = engineForcing(Wedge::isMillion);
        spinAndSettle(engine, "p1");
        engine.guessConsonant("p1", 'H');
        engine.beginSolve("p1");

        LiveActionOutcome solved = engine.attemptSolve("p1", "hawaii beach");

        WofStateSnapshot snap = (WofStateSnapshot) solved.snapshot();
        assertThat(snap.millionWedgeHolderId()).isEqualTo("p1");
        assertThat(snap.millionWedgeSecured()).isTrue();
        assertThat(solved.announceText()).contains("keeps the Million Dollar Wedge");
        engine.continueToNextRound("p1");
        assertThat(hasWedge(engine.toSnapshot(null), Wedge.EFFECT_MILLION)).isFalse(); // it's taken
    }

    @Test
    void notSolvingTheRoundLosesTheMillionDollarWedgeAndItComesBackNextRound() {
        WofEngine engine = engineForcing(Wedge::isMillion);
        spinAndSettle(engine, "p1");
        engine.guessConsonant("p1", 'H');
        missSolve(engine, "p1");
        engine.beginSolve("p2");

        LiveActionOutcome solved = engine.attemptSolve("p2", "hawaii beach");

        assertThat(((WofStateSnapshot) solved.snapshot()).millionWedgeHolderId()).isNull();
        assertThat(solved.announceText()).contains("Million Dollar Wedge is lost");
        engine.continueToNextRound("p1");
        assertThat(hasWedge(engine.toSnapshot(null), Wedge.EFFECT_MILLION)).isTrue();
    }

    @Test
    void bankruptTakesEvenABankedMillionDollarWedge() {
        WofEngine engine = engineSpinning(Wedge::isMillion, Wedge::isBankrupt);
        spinAndSettle(engine, "p1");
        engine.guessConsonant("p1", 'H');
        engine.beginSolve("p1");
        engine.attemptSolve("p1", "hawaii beach"); // banked
        engine.continueToNextRound("p1"); // Round 2 starts with p2
        spinAndSettle(engine, "p2"); // BANKRUPT
        spinAndSettle(engine, "p3"); // BANKRUPT

        LiveActionOutcome outcome = spinAndSettle(engine, "p1"); // BANKRUPT

        assertThat(((WofStateSnapshot) outcome.snapshot()).millionWedgeHolderId()).isNull();
        assertThat(outcome.announceText()).contains("along with the Million Dollar Wedge");
    }

    @Test
    void theMillionDollarWedgeIsOnlyOnTheWheelThroughRoundThree() {
        WofStateSnapshot snap = engineInRoundFour(Duration.ofMinutes(20), w -> w.getValue() != null).toSnapshot(null);

        assertThat(snap.roundNumber()).isEqualTo(4);
        assertThat(hasWedge(snap, Wedge.EFFECT_MILLION)).isFalse();
        assertThat(hasWedge(snap, Wedge.EFFECT_WILD_CARD)).isTrue(); // the Wild Card stays until it's claimed
    }

    @Test
    void theFinalSpinWheelHasNoSpecialWedges() {
        WofEngine engine = engineAtTripleTossUp();
        for (int i = 0; i < 3; i++) {
            engine.beginTripleTossUpSolve("p1");
            engine.attemptTripleTossUpAnswer("p1", "hawaii beach");
        }

        WofStateSnapshot snap = engine.toSnapshot(null);

        assertThat(snap.phase()).isEqualTo("FINAL_SPIN");
        assertThat(hasWedge(snap, Wedge.EFFECT_WILD_CARD)).isFalse();
        assertThat(hasWedge(snap, Wedge.EFFECT_MILLION)).isFalse();
    }

    @Test
    void theMillionEnvelopeReplacesTheHundredThousandOne() {
        int[] usual = WofEngine.bonusEnvelopes(false);
        int[] withMillion = WofEngine.bonusEnvelopes(true);

        assertThat(usual).contains(100_000).doesNotContain(1_000_000);
        assertThat(withMillion).contains(1_000_000).doesNotContain(100_000).hasSameSizeAs(usual);
    }

    @Test
    void aBankedMillionDollarWedgePutsTheMillionOnTheBonusWheel() {
        int[] spins = {0};
        WofEngine engine = new WofEngine(List.of(alice, bob, carol), wofService, Duration.ZERO, AiDifficulty.HARD) {
            @Override
            protected int chooseWedgeIndex(List<Wedge> wheel) {
                return firstMatching(wheel, spins[0]++ == 0 ? Wedge::isMillion : w -> w.getValue() != null);
            }

            @Override
            protected int chooseBonusEnvelope(int[] envelopes) {
                return Arrays.stream(envelopes).max().orElseThrow();
            }
        };
        playOutFrenzy(engine, "p1");
        spinAndSettle(engine, "p1");
        engine.guessConsonant("p1", 'H');
        engine.beginSolve("p1");
        engine.attemptSolve("p1", "hawaii beach"); // banks the wedge
        aliceReachesTheSpeedUp(engine);
        engine.speedupGuessLetter("p1", 'H');
        WofStateSnapshot bonus = (WofStateSnapshot) engine.speedupAttemptSolve("p1", "hawaii beach").snapshot();
        assertThat(bonus.bonusMillionInPlay()).isTrue();
        assertThat(bonus.bonusConsonantCount()).isEqualTo(3);
        engine.pickBonusCategory("p1", bonus.bonusCategoryOptions().get(0));
        engine.submitBonusLetters("p1", List.of('H', 'W', 'B'), 'A');
        int before = alice.getScore();

        LiveActionOutcome won = engine.attemptBonusSolve("p1", "hawaii beach");

        assertThat(((WofStateSnapshot) won.snapshot()).bonusPrizeAmount()).isEqualTo(1_000_000);
        assertThat(alice.getScore()).isEqualTo(before + 1_000_000);
        assertThat(won.announceText()).contains("ONE MILLION DOLLARS");
    }

    @Test
    void withoutEitherSpecialWedgeTheBonusRoundIsTheUsualOne() {
        WofStateSnapshot bonus = engineAtBonusRound().toSnapshot(null);

        assertThat(bonus.bonusConsonantCount()).isEqualTo(3);
        assertThat(bonus.bonusMillionInPlay()).isFalse();
    }

    @Test
    void aBotSpendsItsWildCardOnATopDollarSpinAndKeepsItOtherwise() {
        SessionPlayer bot = SessionPlayer.ai("ai1", "Bot");
        int[] spins = {0};
        WofEngine engine = new WofEngine(List.of(bot, bob, carol), wofService, Duration.ZERO, AiDifficulty.HARD) {
            @Override
            protected int chooseWedgeIndex(List<Wedge> wheel) {
                return firstMatching(wheel, spins[0]++ == 0 ? Wedge::isWildCard : w -> w.getValue() != null);
            }
        };
        GameSession session = new GameSession("WILDBOT", GameType.WOF, "ai1");
        session.setEngine(engine);
        playOutFrenzy(engine, "ai1");
        bot.setScore(0);
        spinAndSettle(engine, "ai1");
        engine.guessConsonant("ai1", 'H'); // claims the Wild Card off a $500 spin

        // $500 isn't worth the card - it keeps it (and buys a vowel with its $1,000 instead).
        engine.pendingAiMoves().get(0).invoke().apply(session);
        assertThat(engine.toSnapshot(null).wildCardHolderId()).isEqualTo("ai1");

        spinAndSettle(engine, "ai1"); // $2,500
        engine.guessConsonant("ai1", 'B');
        engine.pendingAiMoves().get(0).invoke().apply(session);

        WofStateSnapshot snap = engine.toSnapshot(null);
        assertThat(snap.wildCardHolderId()).isNull();
        assertThat(snap.wildCardInPlay()).isTrue();
    }

    /** From Round 1's ROUND_OVER: Rounds 2-3 are solved without a letter called, Alice sweeps the
     *  Triple Toss-Up, and the Final Spin lands her on a dollar value - leaving her in the Speed-Up,
     *  far enough ahead to be the bonus contestant once she solves it. Spins must land on cash from
     *  here on. */
    private static void aliceReachesTheSpeedUp(WofEngine engine) {
        engine.continueToNextRound("p1");
        for (int round = 2; round <= 3; round++) {
            String solver = engine.toSnapshot(null).currentPlayerId();
            engine.beginSolve(solver);
            engine.attemptSolve(solver, "hawaii beach");
            engine.continueToNextRound("p1");
        }
        for (int i = 0; i < 3; i++) {
            engine.beginTripleTossUpSolve("p1");
            engine.attemptTripleTossUpAnswer("p1", "hawaii beach");
        }
        finalSpinAndSettle(engine, "p1");
        assertThat(engine.getPhase()).isEqualTo(WofPhase.SPEEDUP_AWAITING_LETTER);
        assertThat(engine.toSnapshot(null).currentPlayerId()).isEqualTo("p1");
    }

    @Test
    void speedupWrongLetterPassesTurnWithoutScoring() {
        WofEngine engine = engineAtSpeedupRound();
        String playerId = engine.toSnapshot(null).currentPlayerId();
        SessionPlayer player = playerById(playerId);
        int scoreBefore = player.getScore();

        LiveActionOutcome outcome = engine.speedupGuessLetter(playerId, 'Z'); // not in HAWAII BEACH

        WofStateSnapshot after = (WofStateSnapshot) outcome.snapshot();
        assertThat(after.phase()).isEqualTo("SPEEDUP_AWAITING_LETTER");
        assertThat(after.currentPlayerId()).isNotEqualTo(playerId);
        assertThat(player.getScore()).isEqualTo(scoreBefore);
    }

    @Test
    void speedupVowelFoundIsFreeButMovesToSolvePhase() {
        WofEngine engine = engineAtSpeedupRound();
        String playerId = engine.toSnapshot(null).currentPlayerId();
        SessionPlayer player = playerById(playerId);
        int scoreBefore = player.getScore();

        LiveActionOutcome outcome = engine.speedupGuessLetter(playerId, 'A'); // vowel, present, always free

        WofStateSnapshot snap = (WofStateSnapshot) outcome.snapshot();
        assertThat(snap.phase()).isEqualTo("SPEEDUP_AWAITING_SOLVE");
        assertThat(snap.speedupSolvingPlayerId()).isEqualTo(playerId);
        assertThat(player.getScore()).isEqualTo(scoreBefore);
    }

    @Test
    void speedupSolveTimeoutPassesTurnAndReopensForLetters() {
        WofEngine engine = engineAtSpeedupRound();
        String playerId = engine.toSnapshot(null).currentPlayerId();
        engine.speedupGuessLetter(playerId, 'H'); // guaranteed hit -> SPEEDUP_AWAITING_SOLVE
        assertThat(engine.getPhase()).isEqualTo(WofPhase.SPEEDUP_AWAITING_SOLVE);

        LiveActionOutcome outcome = engine.handleSpeedupSolveTimeout(engine.getVersion());

        WofStateSnapshot snap = (WofStateSnapshot) outcome.snapshot();
        assertThat(snap.phase()).isEqualTo("SPEEDUP_AWAITING_LETTER");
        assertThat(snap.currentPlayerId()).isNotEqualTo(playerId);
    }

    /** Swaps in a fuller puzzle library mid-game - everything played up to now was puzzle 1 (PLACE). */
    private void libraryOf(Puzzle... puzzles) {
        List<Puzzle> library = List.of(puzzles);
        when(wofService.getAllPuzzles()).thenReturn(library);
        for (Puzzle p : library) {
            when(wofService.getPuzzlesByCategory(p.getCategory())).thenReturn(List.of(p));
        }
    }

    /** Solves the speed-up puzzle, which sends its solver's game on to the bonus category pick. */
    private WofStateSnapshot finishSpeedupRound(WofEngine engine) {
        String solver = engine.toSnapshot(null).currentPlayerId();
        engine.speedupGuessLetter(solver, 'H');
        return (WofStateSnapshot) engine.speedupAttemptSolve(solver, "hawaii beach").snapshot();
    }

    @Test
    void theBonusContestantChoosesBetweenThreeCategoriesThatStillHaveAnUnplayedPuzzle() {
        WofEngine engine = engineAtSpeedupRound();
        libraryOf(puzzle(1L, "PLACE", "HAWAII BEACH"), puzzle(2L, "PHRASE", "BREAK A LEG"),
                puzzle(3L, "THING", "GRAND PIANO"), puzzle(4L, "EVENT", "SURPRISE PARTY"),
                puzzle(5L, "FOOD & DRINK", "APPLE PIE"));

        WofStateSnapshot picking = finishSpeedupRound(engine);

        assertThat(picking.phase()).isEqualTo("BONUS_PICK_CATEGORY");
        // PLACE's only puzzle has been played, so offering it would mean replaying it.
        assertThat(picking.bonusCategoryOptions()).hasSize(3).doesNotHaveDuplicates()
                .isSubsetOf("PHRASE", "THING", "EVENT", "FOOD & DRINK");

        String chosen = picking.bonusCategoryOptions().get(2);
        LiveActionOutcome picked = engine.pickBonusCategory(picking.bonusWinnerPlayerId(), chosen);

        WofStateSnapshot playing = (WofStateSnapshot) picked.snapshot();
        assertThat(playing.phase()).isEqualTo("BONUS_PICK_LETTERS");
        assertThat(playing.bonusCategory()).isEqualTo(chosen);
        assertThat(picked.announceText()).contains("chose " + chosen);
    }

    @Test
    void whenTooFewCategoriesHaveAnUnplayedPuzzleTheChoiceIsMadeUpFromOnesThatDont() {
        WofEngine engine = engineAtSpeedupRound();
        libraryOf(puzzle(1L, "PLACE", "HAWAII BEACH"), puzzle(2L, "PHRASE", "BREAK A LEG"));

        WofStateSnapshot picking = finishSpeedupRound(engine);

        // Fresh ones lead, so a bot - which takes the first option - always plays a new puzzle.
        assertThat(picking.bonusCategoryOptions()).containsExactly("PHRASE", "PLACE");
    }

    @Test
    void bonusPickCategoryRejectsOptionNotOffered() {
        WofEngine engine = engineAtBonusRound();
        WofStateSnapshot snap = engine.toSnapshot(null);

        assertThatThrownBy(() -> engine.pickBonusCategory(snap.bonusWinnerPlayerId(), "NOT_AN_OPTION"))
                .isInstanceOf(InvalidActionException.class);
    }

    @Test
    void bonusSubmitLettersRejectsRstlneOverlapAndDuplicates() {
        WofEngine engine = engineAtBonusRound();
        WofStateSnapshot snap = engine.toSnapshot(null);
        String winnerId = snap.bonusWinnerPlayerId();
        engine.pickBonusCategory(winnerId, snap.bonusCategoryOptions().get(0));

        assertThatThrownBy(() -> engine.submitBonusLetters(winnerId, List.of('R', 'W', 'B'), 'A'))
                .isInstanceOf(InvalidActionException.class); // R is pre-revealed (RSTLNE)
        assertThatThrownBy(() -> engine.submitBonusLetters(winnerId, List.of('H', 'H', 'B'), 'A'))
                .isInstanceOf(InvalidActionException.class); // duplicate consonant
    }

    @Test
    void bonusSolveTimeoutRevealsAnswerAndEndsGameUnsolved() {
        WofEngine engine = engineAtBonusRound();
        WofStateSnapshot snap = engine.toSnapshot(null);
        String winnerId = snap.bonusWinnerPlayerId();
        engine.pickBonusCategory(winnerId, snap.bonusCategoryOptions().get(0));
        engine.submitBonusLetters(winnerId, List.of('H', 'W', 'B'), 'A');

        LiveActionOutcome outcome = engine.handleBonusSolveTimeout(engine.getVersion());

        WofStateSnapshot finalSnap = (WofStateSnapshot) outcome.snapshot();
        assertThat(finalSnap.phase()).isEqualTo("GAME_OVER");
        assertThat(finalSnap.bonusSolved()).isFalse();
        assertThat(finalSnap.bonusPrizeAmount()).isNotNull();
        // The real show always reveals the full phrase at the end, win or lose.
        assertThat(finalSnap.bonusPuzzleTemplate()).isEqualTo("HAWAII BEACH");
    }

    @Test
    void bonusCorrectSolveAwardsThePrize() {
        WofEngine engine = engineAtBonusRound();
        WofStateSnapshot snap = engine.toSnapshot(null);
        String winnerId = snap.bonusWinnerPlayerId();
        SessionPlayer winner = playerById(winnerId);
        int before = winner.getScore();
        engine.pickBonusCategory(winnerId, snap.bonusCategoryOptions().get(0));
        engine.submitBonusLetters(winnerId, List.of('H', 'W', 'B'), 'A');

        LiveActionOutcome outcome = engine.attemptBonusSolve(winnerId, "hawaii beach");

        WofStateSnapshot finalSnap = (WofStateSnapshot) outcome.snapshot();
        assertThat(finalSnap.phase()).isEqualTo("GAME_OVER");
        assertThat(finalSnap.bonusSolved()).isTrue();
        assertThat(winner.getScore()).isEqualTo(before + finalSnap.bonusPrizeAmount());
    }

    /** Drives a fresh engine through Frenzy and Rounds 1-3 so it lands right at the start of the
     *  Triple Toss-Up ("p1"/alice wins Frenzy and solves both normal-round puzzles). */
    private WofEngine engineAtTripleTossUp() {
        WofEngine engine = engineForcing();
        engine.beginSolve("p1");
        engine.attemptSolve("p1", "hawaii beach");
        engine.continueToNextRound("p1");
        WofStateSnapshot snap2 = engine.toSnapshot(null);
        engine.beginSolve(snap2.currentPlayerId());
        engine.attemptSolve(snap2.currentPlayerId(), "hawaii beach");
        engine.continueToNextRound("p1");
        WofStateSnapshot snap3 = engine.toSnapshot(null);
        engine.beginSolve(snap3.currentPlayerId());
        engine.attemptSolve(snap3.currentPlayerId(), "hawaii beach");
        engine.continueToNextRound("p1");
        return engine;
    }

    /** Drives a fresh engine all the way from Frenzy through Rounds 1-3, the Triple Toss-Up (alice
     *  sweeps it), and the Final Spin (engineForcing()'s zero-length clock means Round 4 goes
     *  straight there, and its wedge-matcher guarantees a dollar value on the first spin) so it
     *  lands in the speed-up round. */
    private WofEngine engineAtSpeedupRound() {
        WofEngine engine = engineAtTripleTossUp();
        engine.beginTripleTossUpSolve("p1");
        engine.attemptTripleTossUpAnswer("p1", "hawaii beach");
        engine.beginTripleTossUpSolve("p1");
        engine.attemptTripleTossUpAnswer("p1", "hawaii beach");
        engine.beginTripleTossUpSolve("p1");
        LiveActionOutcome outcome = engine.attemptTripleTossUpAnswer("p1", "hawaii beach");
        finalSpinAndSettle(engine, ((WofStateSnapshot) outcome.snapshot()).currentPlayerId());
        return engine;
    }

    /** Drives a fresh engine all the way into the bonus round (BONUS_PICK_CATEGORY). */
    private WofEngine engineAtBonusRound() {
        WofEngine engine = engineAtSpeedupRound();
        String playerId = engine.toSnapshot(null).currentPlayerId();
        engine.speedupGuessLetter(playerId, 'H');
        engine.speedupAttemptSolve(playerId, "hawaii beach");
        return engine;
    }

    /** Plays Frenzy, Rounds 1-3 and an unsolved Triple Toss-Up with the given game clock, leaving
     *  the engine at the start of Round 4 - the first round the clock can cut short. */
    private WofEngine engineInRoundFour(Duration gameClock, Predicate<Wedge> matcher) {
        WofEngine engine = new WofEngine(List.of(alice, bob, carol), wofService, gameClock) {
            @Override
            protected int chooseWedgeIndex(List<Wedge> wheel) {
                for (int i = 0; i < wheel.size(); i++) {
                    if (matcher.test(wheel.get(i))) {
                        return i;
                    }
                }
                throw new IllegalStateException("No wedge in the wheel matched the test predicate.");
            }
        };
        playOutFrenzy(engine, "p1");
        for (int round = 1; round <= 3; round++) {
            String solver = engine.toSnapshot(null).currentPlayerId();
            engine.beginSolve(solver);
            engine.attemptSolve(solver, "hawaii beach");
            engine.continueToNextRound("p1");
        }
        for (int i = 0; i < 3; i++) {
            for (String playerId : List.of("p1", "p2", "p3")) {
                engine.beginTripleTossUpSolve(playerId);
                engine.attemptTripleTossUpAnswer(playerId, "wrong guess");
            }
        }
        assertThat(engine.getPhase()).isEqualTo(WofPhase.WAITING_FOR_TURN_ACTION);
        return engine;
    }

    private SessionPlayer playerById(String playerId) {
        return List.of(alice, bob, carol).stream().filter(p -> p.getPlayerId().equals(playerId)).findFirst()
                .orElseThrow();
    }

    /** Same Frenzy-skipping trick as {@link #engineForcing(Predicate)}, but resolved in favor of
     *  the AI player so it starts round 1 as the current player. */
    private WofEngine engineForcingAi(SessionPlayer aiPlayer, Predicate<Wedge> matcher) {
        return engineForcingAi(aiPlayer, matcher, AiDifficulty.HARD);
    }

    private WofEngine engineForcingAi(SessionPlayer aiPlayer, Predicate<Wedge> matcher, AiDifficulty difficulty) {
        List<SessionPlayer> playersList = List.of(aiPlayer, bob, carol);
        WofEngine engine = new WofEngine(playersList, wofService, Duration.ZERO, difficulty) {
            @Override
            protected int chooseWedgeIndex(List<Wedge> wheel) {
                for (int i = 0; i < wheel.size(); i++) {
                    if (matcher.test(wheel.get(i))) {
                        return i;
                    }
                }
                throw new IllegalStateException("No wedge in the wheel matched the test predicate.");
            }
        };
        playOutFrenzy(engine, aiPlayer.getPlayerId());
        return engine;
    }
}
