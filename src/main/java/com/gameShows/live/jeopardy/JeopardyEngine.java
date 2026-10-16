package com.gameShows.live.jeopardy;

import com.gameShows.live.error.InvalidActionException;
import com.gameShows.live.jeopardy.choices.ResponseChoiceDealer;
import com.gameShows.live.jeopardy.choices.ResponseChoices;
import com.gameShows.live.session.AiCapableEngine;
import com.gameShows.live.session.AiDifficulty;
import com.gameShows.live.session.AiMove;
import com.gameShows.live.session.CompletableGameEngine;
import com.gameShows.live.session.CompletedGame;
import com.gameShows.live.session.GameType;
import com.gameShows.live.session.LiveActionOutcome;
import com.gameShows.live.session.LiveGameEngine;
import com.gameShows.live.session.PlayerView;
import com.gameShows.live.session.SessionPlayer;
import com.gameShows.models.jeopardy.Clue;
import com.gameShows.services.JeopardyService;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * One instance per live Jeopardy game session. Not a Spring bean - constructed
 * per game by whoever
 * starts the session, and mutated only while the owning GameSession's lock is
 * held.
 */
public class JeopardyEngine implements LiveGameEngine, AiCapableEngine, CompletableGameEngine {

    private static final int CATEGORIES_PER_ROUND = 6;
    private static final int CLUES_PER_CATEGORY = 5;
    /** Host-configurable, but a game that doesn't say falls back to the classic $100 ladder. */
    public static final int DEFAULT_CLUE_BASE_INCREMENT = 100;
    public static final int MIN_ROUNDS = 2;
    public static final int MAX_ROUNDS = 4;
    public static final int DEFAULT_ROUNDS = 2;

    private static final int DAILY_DOUBLE_MIN_WAGER = 5;
    /** How long a clue stays open for someone - anyone - to ring in before it's simply revealed and
     *  the board comes back. Below the top difficulty an AI may pass on a clue entirely, so without
     *  this a clue nobody fancies would hang the game. */
    public static final Duration BUZZ_WINDOW = Duration.ofSeconds(8);
    public static final Duration ANSWER_WINDOW = Duration.ofSeconds(5);
    /** How long a clue nobody got stays up with its correct response before the board comes back -
     *  long enough to read from across the room, short enough not to stall the game. */
    public static final Duration REVEAL_WINDOW = Duration.ofSeconds(4);
    public static final Duration FINAL_ANSWER_WINDOW = Duration.ofSeconds(30);
    /** Ties stand unless the host turns them off - how every game played before the option existed. */
    public static final boolean DEFAULT_ALLOW_TIES = true;
    /** How long a tiebreaker's category (and who's tied) is up before its clue is - so the room can
     *  take in that there's a tiebreaker at all before anyone's on the clock. */
    public static final Duration TIEBREAKER_INTRO_WINDOW = Duration.ofSeconds(4);
    private static final Duration AI_SELECT_DELAY = Duration.ofMillis(1200);
    private static final Duration AI_BUZZ_DELAY = Duration.ofMillis(650);
    private static final Duration AI_ANSWER_DELAY = Duration.ofMillis(800);
    private static final Duration AI_WAGER_DELAY = Duration.ofMillis(1000);
    private static final Duration AI_STAGGER = Duration.ofMillis(150);

    private final List<SessionPlayer> players;
    private final JeopardyService jeopardyService;
    private final Set<String> usedCategories = new HashSet<>();
    /** Dollar step between rows in round 1; round N's rows are this x N, so the whole board scales
     *  with the round exactly like the real show's Double/Triple Jeopardy do. */
    private final int clueBaseIncrement;
    private final AiDifficulty aiDifficulty;
    private final ResponseChoiceDealer responseChoiceDealer;
    private final boolean allowTies;

    private int currentRound = 1;
    private final int maxRounds;
    private JeopardyPhase phase;
    private List<BoardCell> board;
    private String playerIdInControl;
    private String activeCellId;
    private ClueSubPhase clueSubPhase = ClueSubPhase.NONE;
    private String activeAnswererId;
    /** The current timed window (buzz-in, answer, or Final Jeopardy answers) - both null whenever
     *  nothing is on the clock. Set together only via {@link #startPhaseTimer}/{@link #clearPhaseTimer}. */
    private Instant phaseStartedAt;
    private Instant phaseDeadlineAt;
    private int dailyDoubleWager;
    /** Why the active clue's response is showing - non-null exactly while clueSubPhase is REVEALED. */
    private ClueRevealReason revealReason;

    private String finalCategory;
    private Clue finalClue;
    private AnswerKey finalAnswerKey;
    private ResponseChoices finalResponseChoices;
    /** Set the moment the last regular round ends: only players who finished it in the black get
     *  to play Final Jeopardy. Null before then. */
    private List<String> finalEligiblePlayerIds;
    private final Map<String, Integer> finalWagers = new LinkedHashMap<>();
    private final Map<String, String> finalAnswers = new LinkedHashMap<>();
    private List<FinalResultEntry> finalResults;

    /** Who was tied for the lead when the game would have ended - null unless a tiebreaker was
     *  needed. Only they may buzz in on a tiebreaker clue. */
    private List<String> tiebreakerPlayerIds;
    /** The tiebreaker clue in play: 1 for the first, 2 once nobody got that one, and so on. */
    private int tiebreakerNumber;
    /** The tiebreaker clue itself, played through the same buzz/answer/reveal steps as a board clue
     *  but never on the board - so {@link #activeCell()} hands it out while the phase is TIEBREAKER. */
    private BoardCell tiebreakerCell;
    private String tiebreakerWinnerPlayerId;

    private List<String> gameWinnerPlayerIds;
    private boolean completedGameClaimed;
    private long version = 0;

    // Bumped each time the corresponding timed phase is (re)entered, so
    // pendingAiMoves() can arm
    // its safety-net timeout exactly once per entry regardless of how many times
    // it's called while
    // still in that phase (it's called after every single action, human or AI).
    private long buzzEntryId = 0;
    private long buzzTimeoutArmedForEntry = -1;
    private long answeringEntryId = 0;
    private long answeringTimeoutArmedForEntry = -1;
    private long revealEntryId = 0;
    private long revealTimeoutArmedForEntry = -1;
    private long finalAnswerEntryId = 0;
    private long finalAnswerTimeoutArmedForEntry = -1;
    private long tiebreakerIntroEntryId = 0;
    private long tiebreakerIntroArmedForEntry = -1;

    public JeopardyEngine(List<SessionPlayer> playersInJoinOrder, JeopardyService jeopardyService) {
        this(playersInJoinOrder, jeopardyService, DEFAULT_ROUNDS, DEFAULT_CLUE_BASE_INCREMENT, AiDifficulty.DEFAULT);
    }

    /** A game where every clue is answered by typing. */
    public JeopardyEngine(List<SessionPlayer> playersInJoinOrder, JeopardyService jeopardyService,
            int roundCount, int clueBaseIncrement, AiDifficulty aiDifficulty) {
        this(playersInJoinOrder, jeopardyService, roundCount, clueBaseIncrement, aiDifficulty,
                ResponseChoiceDealer.NONE);
    }

    /**
     * @param roundCount           how many regular rounds to play before Final Jeopardy, clamped to
     *                             {@value #MIN_ROUNDS}-{@value #MAX_ROUNDS}; host-configurable at
     *                             session start.
     * @param clueBaseIncrement    the dollar step between rows in round 1 - round N uses this x N.
     * @param aiDifficulty         how strong the computer opponents are; null means the default.
     * @param responseChoiceDealer decides which clues are answered by picking from a list.
     */
    public JeopardyEngine(List<SessionPlayer> playersInJoinOrder, JeopardyService jeopardyService,
            int roundCount, int clueBaseIncrement, AiDifficulty aiDifficulty,
            ResponseChoiceDealer responseChoiceDealer) {
        this(playersInJoinOrder, jeopardyService, roundCount, clueBaseIncrement, aiDifficulty,
                responseChoiceDealer, DEFAULT_ALLOW_TIES);
    }

    /**
     * @param allowTies whether the game may end with the lead shared. When false, contestants tied
     *                  for it play tiebreaker clues until one of them gets one right.
     */
    public JeopardyEngine(List<SessionPlayer> playersInJoinOrder, JeopardyService jeopardyService,
            int roundCount, int clueBaseIncrement, AiDifficulty aiDifficulty,
            ResponseChoiceDealer responseChoiceDealer, boolean allowTies) {
        this.allowTies = allowTies;
        this.players = playersInJoinOrder;
        this.jeopardyService = jeopardyService;
        this.maxRounds = Math.max(MIN_ROUNDS, Math.min(MAX_ROUNDS, roundCount));
        this.clueBaseIncrement = clueBaseIncrement > 0 ? clueBaseIncrement : DEFAULT_CLUE_BASE_INCREMENT;
        this.aiDifficulty = AiDifficulty.orDefault(aiDifficulty);
        this.responseChoiceDealer = responseChoiceDealer;
        this.playerIdInControl = players.get(0).getPlayerId();
        this.board = buildBoard(1);
        this.phase = JeopardyPhase.BOARD;
    }

    public long getVersion() {
        return version;
    }

    public JeopardyPhase getPhase() {
        return phase;
    }

    /**
     * Package-visible only - lets tests find the Daily Double cell without leaking
     * it publicly.
     */
    List<BoardCell> boardForTesting() {
        return board;
    }

    public ClueSubPhase getClueSubPhase() {
        return clueSubPhase;
    }

    public Instant getPhaseStartedAt() {
        return phaseStartedAt;
    }

    public Instant getPhaseDeadlineAt() {
        return phaseDeadlineAt;
    }

    private void startPhaseTimer(Duration window) {
        phaseStartedAt = Instant.now();
        phaseDeadlineAt = phaseStartedAt.plus(window);
    }

    private void clearPhaseTimer() {
        phaseStartedAt = null;
        phaseDeadlineAt = null;
    }

    public String getActiveAnswererId() {
        return activeAnswererId;
    }

    // ---- AI opponents (strength set by the host's AiDifficulty - see that enum for the dials) ----

    @Override
    public List<AiMove> pendingAiMoves() {
        return switch (phase) {
            case BOARD -> pendingSelectClueMoves();
            case CLUE_OPEN -> pendingClueOpenMoves();
            case DAILY_DOUBLE_WAGER -> pendingDailyDoubleWagerMoves();
            case DAILY_DOUBLE_ANSWER -> pendingDailyDoubleAnswerMoves();
            case FINAL_WAGER -> pendingFinalWagerMoves();
            case FINAL_ANSWER_COLLECTION -> pendingFinalAnswerMoves();
            case TIEBREAKER -> pendingTiebreakerMoves();
            default -> List.of();
        };
    }

    /** The intro's countdown to its clue, then exactly what an open board clue schedules. */
    private List<AiMove> pendingTiebreakerMoves() {
        if (clueSubPhase != ClueSubPhase.NONE) {
            return pendingClueOpenMoves();
        }
        if (tiebreakerIntroArmedForEntry == tiebreakerIntroEntryId) {
            return List.of();
        }
        tiebreakerIntroArmedForEntry = tiebreakerIntroEntryId;
        long expectedVersion = version;
        return List.of(new AiMove(TIEBREAKER_INTRO_WINDOW,
                session -> ((JeopardyEngine) session.getEngine()).openTiebreakerClue(expectedVersion)));
    }

    private List<AiMove> pendingSelectClueMoves() {
        SessionPlayer control = findPlayer(playerIdInControl);
        if (!control.isAi()) {
            return List.of();
        }
        String cellId = chooseAiCellId();
        if (cellId == null) {
            return List.of();
        }
        String aiId = control.getPlayerId();
        return List.of(new AiMove(aiDifficulty.stretch(AI_SELECT_DELAY),
                session -> ((JeopardyEngine) session.getEngine()).selectClue(aiId, cellId)));
    }

    private List<AiMove> pendingClueOpenMoves() {
        if (clueSubPhase == ClueSubPhase.BUZZ_OPEN) {
            BoardCell cell = activeCell();
            if (cell == null) {
                return List.of();
            }
            List<AiMove> moves = new ArrayList<>();
            // Safety net armed once per buzzer opening: below the top difficulty an AI may decline
            // to ring in at all, so without this a clue nobody buzzes on would sit open forever.
            if (buzzTimeoutArmedForEntry != buzzEntryId) {
                buzzTimeoutArmedForEntry = buzzEntryId;
                long expectedVersion = version;
                moves.add(new AiMove(BUZZ_WINDOW,
                        session -> ((JeopardyEngine) session.getEngine()).handleBuzzTimeout(expectedVersion)));
            }
            int i = 0;
            for (SessionPlayer p : players) {
                if (p.isAi() && mayBuzz(p.getPlayerId()) && !cell.getLockedOutPlayerIds().contains(p.getPlayerId())
                        && aiDifficulty.rolls(aiDifficulty.buzzInChance())) {
                    String aiId = p.getPlayerId();
                    Duration delay = aiDifficulty.stretch(AI_BUZZ_DELAY.plus(AI_STAGGER.multipliedBy(i)));
                    moves.add(new AiMove(delay, session -> ((JeopardyEngine) session.getEngine()).buzz(aiId)));
                    i++;
                }
            }
            return moves;
        }
        if (clueSubPhase == ClueSubPhase.ANSWERING && activeAnswererId != null) {
            List<AiMove> moves = new ArrayList<>();
            // Safety-net timeout: armed once per BUZZ->ANSWERING entry, regardless of
            // whether the
            // answerer is human (the WS controller no longer schedules this) or AI (which
            // normally
            // resolves on its own well before this fires anyway).
            if (answeringTimeoutArmedForEntry != answeringEntryId) {
                answeringTimeoutArmedForEntry = answeringEntryId;
                long expectedVersion = version;
                moves.add(new AiMove(ANSWER_WINDOW,
                        session -> ((JeopardyEngine) session.getEngine()).handleAnswerTimeout(expectedVersion)));
            }
            SessionPlayer answerer = findPlayer(activeAnswererId);
            if (answerer.isAi()) {
                BoardCell cell = activeCell();
                if (cell != null) {
                    String aiId = answerer.getPlayerId();
                    String response = aiResponseFor(cell.getAnswerKey(), cell.getResponseChoices());
                    moves.add(new AiMove(aiDifficulty.stretch(AI_ANSWER_DELAY),
                            session -> ((JeopardyEngine) session.getEngine()).submitAnswer(aiId, response)));
                }
            }
            return moves;
        }
        if (clueSubPhase == ClueSubPhase.REVEALED && revealTimeoutArmedForEntry != revealEntryId) {
            // Not an AI move, just the same scheduling pipeline: armed once per reveal, it brings the
            // board back after everyone has had time to read the correct response.
            revealTimeoutArmedForEntry = revealEntryId;
            long expectedVersion = version;
            return List.of(new AiMove(REVEAL_WINDOW,
                    session -> ((JeopardyEngine) session.getEngine()).finishReveal(expectedVersion)));
        }
        return List.of();
    }

    private List<AiMove> pendingDailyDoubleWagerMoves() {
        if (activeAnswererId == null) {
            return List.of();
        }
        SessionPlayer finder = findPlayer(activeAnswererId);
        if (!finder.isAi()) {
            return List.of();
        }
        String aiId = finder.getPlayerId();
        int max = Math.max(DAILY_DOUBLE_MIN_WAGER, finder.getScore());
        int wager = Math.max(DAILY_DOUBLE_MIN_WAGER, (int) Math.round(max * aiDifficulty.wagerFraction()));
        return List.of(new AiMove(aiDifficulty.stretch(AI_WAGER_DELAY),
                session -> ((JeopardyEngine) session.getEngine()).submitDailyDoubleWager(aiId, wager)));
    }

    private List<AiMove> pendingDailyDoubleAnswerMoves() {
        if (activeAnswererId == null) {
            return List.of();
        }
        SessionPlayer finder = findPlayer(activeAnswererId);
        if (!finder.isAi()) {
            return List.of();
        }
        BoardCell cell = activeCell();
        if (cell == null) {
            return List.of();
        }
        String aiId = finder.getPlayerId();
        String response = aiResponseFor(cell.getAnswerKey(), cell.getResponseChoices());
        return List.of(new AiMove(aiDifficulty.stretch(AI_ANSWER_DELAY),
                session -> ((JeopardyEngine) session.getEngine()).submitDailyDoubleAnswer(aiId, response)));
    }

    private List<AiMove> pendingFinalWagerMoves() {
        List<AiMove> moves = new ArrayList<>();
        int i = 0;
        for (SessionPlayer p : players) {
            if (p.isAi() && isFinalEligible(p.getPlayerId()) && !finalWagers.containsKey(p.getPlayerId())) {
                String aiId = p.getPlayerId();
                int wager = (int) Math.round(Math.max(0, p.getScore()) * aiDifficulty.wagerFraction());
                Duration delay = aiDifficulty.stretch(AI_WAGER_DELAY.plus(AI_STAGGER.multipliedBy(i)));
                moves.add(new AiMove(delay,
                        session -> ((JeopardyEngine) session.getEngine()).submitFinalWager(aiId, wager)));
                i++;
            }
        }
        return moves;
    }

    private List<AiMove> pendingFinalAnswerMoves() {
        List<AiMove> moves = new ArrayList<>();
        // Safety-net timeout: armed once per FINAL_WAGER->FINAL_ANSWER_COLLECTION
        // entry, regardless
        // of whether it was a human's or an AI's wager that completed the transition.
        if (finalAnswerTimeoutArmedForEntry != finalAnswerEntryId) {
            finalAnswerTimeoutArmedForEntry = finalAnswerEntryId;
            long expectedVersion = version;
            moves.add(new AiMove(FINAL_ANSWER_WINDOW,
                    session -> ((JeopardyEngine) session.getEngine()).handleFinalAnswerTimeout(expectedVersion)));
        }
        if (finalClue == null) {
            return moves;
        }
        int i = 0;
        for (SessionPlayer p : players) {
            if (p.isAi() && isFinalEligible(p.getPlayerId()) && !finalAnswers.containsKey(p.getPlayerId())) {
                String aiId = p.getPlayerId();
                String response = aiResponseFor(finalAnswerKey, finalResponseChoices);
                Duration delay = aiDifficulty.stretch(AI_ANSWER_DELAY.plus(AI_STAGGER.multipliedBy(i)));
                moves.add(new AiMove(delay,
                        session -> ((JeopardyEngine) session.getEngine()).submitFinalAnswer(aiId, response)));
                i++;
            }
        }
        return moves;
    }

    /**
     * What the bot "comes up with" for a clue: the real response when it happens to know it (always,
     * at the top difficulty), otherwise some other clue's response - a wrong answer that still reads
     * like a genuine guess when it's shown in the Final Jeopardy results. On a list clue it picks
     * from the list like anyone else; an off-list answer would just be turned away.
     *
     * <p>Both come from answer keys rather than the displayed response, which may read "Krone /
     * Krona" - not something anyone would answer, and not something the key accepts.
     */
    private String aiResponseFor(AnswerKey key, ResponseChoices choices) {
        boolean knowsIt = aiDifficulty.knowsAnswer();
        if (choices != null) {
            if (knowsIt) {
                return choices.correctOption();
            }
            List<String> wrong = choices.wrongOptions();
            return wrong.get(ThreadLocalRandom.current().nextInt(wrong.size()));
        }
        if (knowsIt) {
            return key.canonicalResponse();
        }
        List<String> alternatives = board.stream()
                .map(c -> c.getAnswerKey().canonicalResponse())
                .filter(r -> !r.isBlank() && !AnswerMatcher.isCorrect(r, key))
                .distinct()
                .toList();
        if (alternatives.isEmpty()) {
            return "";
        }
        return alternatives.get(ThreadLocalRandom.current().nextInt(alternatives.size()));
    }

    /**
     * Only the top difficulty hunts the biggest value on the board; weaker bots pick at random,
     * the way a casual player works through categories they like the look of.
     */
    private String chooseAiCellId() {
        List<BoardCell> hidden = board.stream().filter(c -> c.getState() == CellState.HIDDEN).toList();
        if (hidden.isEmpty()) {
            return null;
        }
        if (aiDifficulty == AiDifficulty.HARD) {
            return hidden.stream()
                    .max(Comparator.comparingInt(BoardCell::getValue))
                    .map(BoardCell::getCellId)
                    .orElse(null);
        }
        return hidden.get(ThreadLocalRandom.current().nextInt(hidden.size())).getCellId();
    }

    // ---- Regular clue flow
    // -------------------------------------------------------------------

    public LiveActionOutcome selectClue(String playerId, String cellId) {
        requirePhase(JeopardyPhase.BOARD);
        if (!playerIdInControl.equals(playerId)) {
            throw new InvalidActionException("Only the player in control may select a clue.");
        }
        BoardCell cell = boardById().get(cellId);
        if (cell == null || cell.getState() != CellState.HIDDEN) {
            throw new InvalidActionException("That clue is not available.");
        }
        version++;
        activeCellId = cellId;
        cell.setState(CellState.REVEALED_OPEN);

        if (cell.isDailyDouble()) {
            phase = JeopardyPhase.DAILY_DOUBLE_WAGER;
            activeAnswererId = playerId;
            clearPhaseTimer();
            return LiveActionOutcome.of(toSnapshot(null),
                    "Daily Double! " + findPlayer(playerId).getDisplayName() + " must wager.");
        }
        phase = JeopardyPhase.CLUE_OPEN;
        openBuzzer();
        return LiveActionOutcome.of(toSnapshot(null), "The clue for " + cell.getCategory() + " is open.");
    }

    /** Opens (or reopens) the buzzer on the active clue, restarting its buzz-in countdown. */
    private void openBuzzer() {
        clueSubPhase = ClueSubPhase.BUZZ_OPEN;
        activeAnswererId = null;
        startPhaseTimer(BUZZ_WINDOW);
        buzzEntryId++;
    }

    public LiveActionOutcome buzz(String playerId) {
        requirePhaseAny(JeopardyPhase.CLUE_OPEN, JeopardyPhase.TIEBREAKER);
        if (!mayBuzz(playerId)) {
            throw new InvalidActionException("Only the contestants tied for the lead play the tiebreaker.");
        }
        if (clueSubPhase != ClueSubPhase.BUZZ_OPEN) {
            throw new InvalidActionException("Buzzing isn't open right now.");
        }
        BoardCell cell = activeCell();
        if (cell.getLockedOutPlayerIds().contains(playerId)) {
            throw new InvalidActionException("You've already answered this clue incorrectly.");
        }
        version++;
        activeAnswererId = playerId;
        clueSubPhase = ClueSubPhase.ANSWERING;
        startPhaseTimer(ANSWER_WINDOW);
        answeringEntryId++;
        return LiveActionOutcome.of(toSnapshot(null), findPlayer(playerId).getDisplayName() + " buzzed in!");
    }

    public LiveActionOutcome submitAnswer(String playerId, String answerText) {
        requirePhaseAny(JeopardyPhase.CLUE_OPEN, JeopardyPhase.TIEBREAKER);
        if (clueSubPhase != ClueSubPhase.ANSWERING || !playerId.equals(activeAnswererId)) {
            throw new InvalidActionException("It isn't your turn to answer.");
        }
        requireOfferedChoice(activeCell().getResponseChoices(), answerText);
        version++;
        return resolveRegularAnswer(playerId, answerText);
    }

    /**
     * Invoked by a scheduled callback when the answer window elapses with no
     * submission.
     */
    public LiveActionOutcome handleAnswerTimeout(long expectedVersion) {
        if (expectedVersion != version || clueSubPhase != ClueSubPhase.ANSWERING) {
            return null;
        }
        version++;
        return resolveRegularAnswer(activeAnswererId, null);
    }

    /**
     * Invoked by a scheduled callback when the buzz-in window elapses with nobody ringing in - the
     * clue's correct response is revealed, with no score change for anyone.
     */
    public LiveActionOutcome handleBuzzTimeout(long expectedVersion) {
        boolean clueOnScreen = phase == JeopardyPhase.CLUE_OPEN || phase == JeopardyPhase.TIEBREAKER;
        if (expectedVersion != version || !clueOnScreen || clueSubPhase != ClueSubPhase.BUZZ_OPEN) {
            return null;
        }
        version++;
        return revealCorrectResponse(ClueRevealReason.TIME_EXPIRED,
                "Time's up! The correct response was: " + activeCell().getClue().getResponse());
    }

    private LiveActionOutcome resolveRegularAnswer(String playerId, String answerText) {
        if (phase == JeopardyPhase.TIEBREAKER) {
            return resolveTiebreakerAnswer(playerId, answerText);
        }
        BoardCell cell = activeCell();
        boolean correct = isCorrect(answerText, cell.getAnswerKey(), cell.getResponseChoices());
        SessionPlayer player = findPlayer(playerId);
        String announce;
        if (correct) {
            player.addToScore(cell.getValue());
            cell.setState(CellState.ANSWERED);
            playerIdInControl = playerId;
            announce = player.getDisplayName() + " answered correctly for $" + cell.getValue() + "!";
            return closeClueAndReturnToBoard(announce);
        }
        player.addToScore(-cell.getValue());
        cell.getLockedOutPlayerIds().add(playerId);
        announce = player.getDisplayName() + " answered incorrectly.";
        if (everyoneLockedOut(cell)) {
            return revealCorrectResponse(ClueRevealReason.ALL_INCORRECT,
                    announce + " The correct response was: " + cell.getClue().getResponse());
        }
        openBuzzer();
        return LiveActionOutcome.of(toSnapshot(null), announce);
    }

    /** Whether nobody who could still ring in on this clue is left - a tiebreaker only counts the tied. */
    private boolean everyoneLockedOut(BoardCell cell) {
        return players.stream()
                .filter(p -> mayBuzz(p.getPlayerId()))
                .allMatch(p -> !p.isConnected() || cell.getLockedOutPlayerIds().contains(p.getPlayerId()));
    }

    /** Anyone may ring in on a board clue; only the contestants tied for the lead on a tiebreaker. */
    private boolean mayBuzz(String playerId) {
        return phase != JeopardyPhase.TIEBREAKER || tiebreakerPlayerIds.contains(playerId);
    }

    /**
     * Ends the active clue without anyone having got it: it stays on screen, now showing its correct
     * response, until {@link #finishReveal} brings the board back {@link #REVEAL_WINDOW} later. Nobody
     * can act in the meantime - every action needs a phase or sub-phase this isn't.
     */
    private LiveActionOutcome revealCorrectResponse(ClueRevealReason reason, String announce) {
        activeCell().setState(CellState.ANSWERED);
        if (phase == JeopardyPhase.DAILY_DOUBLE_ANSWER) {
            // A missed Daily Double's response goes up like any other clue's.
            phase = JeopardyPhase.CLUE_OPEN;
        }
        clueSubPhase = ClueSubPhase.REVEALED;
        revealReason = reason;
        activeAnswererId = null;
        dailyDoubleWager = 0;
        clearPhaseTimer();
        revealEntryId++;
        return LiveActionOutcome.of(toSnapshot(null), announce);
    }

    /** Invoked by a scheduled callback once a revealed response has been on screen long enough. */
    public LiveActionOutcome finishReveal(long expectedVersion) {
        if (expectedVersion != version || clueSubPhase != ClueSubPhase.REVEALED) {
            return null;
        }
        version++;
        if (phase == JeopardyPhase.TIEBREAKER) {
            // Nobody broke the tie with that one - on to another. Dealt before the snapshot is taken.
            String announce = dealTiebreakerClue();
            return LiveActionOutcome.of(toSnapshot(null), announce);
        }
        return closeClueAndReturnToBoard(null);
    }

    /** @param announce what just happened, or null when there's nothing new to say. */
    private LiveActionOutcome closeClueAndReturnToBoard(String announce) {
        activeCellId = null;
        activeAnswererId = null;
        clueSubPhase = ClueSubPhase.NONE;
        revealReason = null;
        clearPhaseTimer();
        phase = JeopardyPhase.BOARD;
        String transitionAnnounce = checkRoundComplete();
        String combined;
        if (announce == null) {
            combined = transitionAnnounce;
        } else if (transitionAnnounce == null) {
            combined = announce;
        } else {
            combined = announce + " " + transitionAnnounce;
        }
        return LiveActionOutcome.of(toSnapshot(null), combined);
    }

    private String checkRoundComplete() {
        boolean allAnswered = board.stream().allMatch(c -> c.getState() == CellState.ANSWERED);
        if (!allAnswered) {
            return null;
        }
        String finishedRoundName = roundName(currentRound);
        if (currentRound < maxRounds) {
            currentRound++;
            board = buildBoard(currentRound);
            phase = JeopardyPhase.BOARD;
            return finishedRoundName + " complete! " + roundName(currentRound) + " begins.";
        }
        return finishedRoundName + " complete! " + beginFinalJeopardy();
    }

    /** "Jeopardy Round", "Double Jeopardy", "Triple Jeopardy", "Quadruple Jeopardy". */
    private static String roundName(int round) {
        return switch (round) {
            case 1 -> "The Jeopardy Round";
            case 2 -> "Double Jeopardy";
            case 3 -> "Triple Jeopardy";
            default -> "Quadruple Jeopardy";
        };
    }

    // ---- Daily Double flow
    // --------------------------------------------------------------------

    public LiveActionOutcome submitDailyDoubleWager(String playerId, int wager) {
        requirePhase(JeopardyPhase.DAILY_DOUBLE_WAGER);
        if (!playerId.equals(activeAnswererId)) {
            throw new InvalidActionException("Only the player who found the Daily Double may wager.");
        }
        SessionPlayer player = findPlayer(playerId);
        int max = Math.max(DAILY_DOUBLE_MIN_WAGER, player.getScore());
        if (wager < DAILY_DOUBLE_MIN_WAGER || wager > max) {
            throw new InvalidActionException("Wager must be between $" + DAILY_DOUBLE_MIN_WAGER + " and $" + max + ".");
        }
        version++;
        dailyDoubleWager = wager;
        phase = JeopardyPhase.DAILY_DOUBLE_ANSWER;
        Map<String, Object> priv = Map.of(playerId, toSnapshot(playerId));
        return LiveActionOutcome.of(toSnapshot(null), player.getDisplayName() + " wagered on the Daily Double.", priv);
    }

    public LiveActionOutcome submitDailyDoubleAnswer(String playerId, String answerText) {
        requirePhase(JeopardyPhase.DAILY_DOUBLE_ANSWER);
        if (!playerId.equals(activeAnswererId)) {
            throw new InvalidActionException("Only the player who found the Daily Double may answer.");
        }
        BoardCell cell = activeCell();
        requireOfferedChoice(cell.getResponseChoices(), answerText);
        version++;
        boolean correct = isCorrect(answerText, cell.getAnswerKey(), cell.getResponseChoices());
        SessionPlayer player = findPlayer(playerId);
        int wager = dailyDoubleWager;
        playerIdInControl = playerId;
        if (correct) {
            player.addToScore(wager);
            cell.setState(CellState.ANSWERED);
            dailyDoubleWager = 0;
            return closeClueAndReturnToBoard(player.getDisplayName() + " got the Daily Double right for $" + wager
                    + ". The response was: " + cell.getClue().getResponse());
        }
        player.addToScore(-wager);
        return revealCorrectResponse(ClueRevealReason.DAILY_DOUBLE_INCORRECT,
                player.getDisplayName() + " missed the Daily Double, losing $" + wager
                        + ". The correct response was: " + cell.getClue().getResponse());
    }

    // ---- Final Jeopardy flow
    // ------------------------------------------------------------------

    /**
     * Sets up Final Jeopardy and returns the line announcing it. Only contestants who finished the
     * last regular round in the black take part - anyone sitting at $0 or in the red is out, exactly
     * as on the show. If that leaves nobody, there's no Final round at all and the game ends here.
     *
     * @return the announcement to append to the round-complete message.
     */
    private String beginFinalJeopardy() {
        finalEligiblePlayerIds = players.stream()
                .filter(p -> p.getScore() > 0)
                .map(SessionPlayer::getPlayerId)
                .toList();
        if (finalEligiblePlayerIds.isEmpty()) {
            String tiebreaker = endGame();
            return "Nobody finished with money, so there's no Final Jeopardy."
                    + (tiebreaker == null ? " That's the game!" : " " + tiebreaker);
        }

        finalClue = pickStandaloneClue();
        finalCategory = finalClue.getCategory();
        finalAnswerKey = AnswerKey.forClue(finalClue);
        finalResponseChoices = dealChoices(finalClue, finalAnswerKey);

        phase = JeopardyPhase.FINAL_WAGER;
        finalWagers.clear();
        finalAnswers.clear();
        finalResults = null;
        int outCount = players.size() - finalEligiblePlayerIds.size();
        return "Final Jeopardy begins."
                + (outCount == 0 ? "" : " " + outCount + " contestant" + (outCount == 1 ? "" : "s")
                        + " didn't finish with money and can't play.");
    }

    /** Whether this player finished the last regular round in the black (see beginFinalJeopardy). */
    private boolean isFinalEligible(String playerId) {
        return finalEligiblePlayerIds != null && finalEligiblePlayerIds.contains(playerId);
    }

    public LiveActionOutcome submitFinalWager(String playerId, int wager) {
        requirePhase(JeopardyPhase.FINAL_WAGER);
        if (!isFinalEligible(playerId)) {
            throw new InvalidActionException(
                    "Only contestants who finished the last round with money play Final Jeopardy.");
        }
        SessionPlayer player = findPlayer(playerId);
        int max = Math.max(0, player.getScore());
        if (wager < 0 || wager > max) {
            throw new InvalidActionException("Wager must be between $0 and $" + max + ".");
        }
        if (finalWagers.containsKey(playerId)) {
            throw new InvalidActionException("You've already submitted a wager.");
        }
        version++;
        finalWagers.put(playerId, wager);
        Map<String, Object> priv = Map.of(playerId, Map.of("type", "SUBMISSION_ACK"));
        if (allEligibleSubmitted(finalWagers.keySet())) {
            phase = JeopardyPhase.FINAL_ANSWER_COLLECTION;
            startPhaseTimer(FINAL_ANSWER_WINDOW);
            finalAnswerEntryId++;
            return LiveActionOutcome.of(toSnapshot(null), "All wagers are in! Here's the Final Jeopardy clue.", priv);
        }
        return LiveActionOutcome.of(toSnapshot(null), null, priv);
    }

    public LiveActionOutcome submitFinalAnswer(String playerId, String answerText) {
        requirePhase(JeopardyPhase.FINAL_ANSWER_COLLECTION);
        if (!finalWagers.containsKey(playerId)) {
            throw new InvalidActionException("You did not submit a wager.");
        }
        if (finalAnswers.containsKey(playerId)) {
            throw new InvalidActionException("You've already submitted an answer.");
        }
        requireOfferedChoice(finalResponseChoices, answerText);
        version++;
        finalAnswers.put(playerId, answerText == null ? "" : answerText);
        Map<String, Object> priv = Map.of(playerId, Map.of("type", "SUBMISSION_ACK"));
        if (allEligibleSubmitted(finalAnswers.keySet())) {
            return revealFinalResults();
        }
        return LiveActionOutcome.of(toSnapshot(null), null, priv);
    }

    /**
     * Invoked by a scheduled callback when the Final Jeopardy answer window
     * elapses.
     */
    public LiveActionOutcome handleFinalAnswerTimeout(long expectedVersion) {
        if (expectedVersion != version || phase != JeopardyPhase.FINAL_ANSWER_COLLECTION) {
            return null;
        }
        version++;
        for (SessionPlayer p : players) {
            if (finalWagers.containsKey(p.getPlayerId()) && !finalAnswers.containsKey(p.getPlayerId())) {
                finalAnswers.put(p.getPlayerId(), "");
            }
        }
        return revealFinalResults();
    }

    /** Ignores anyone who isn't playing Final Jeopardy, so a contestant who didn't qualify can't
     *  hold the round open waiting for a wager or an answer they're never allowed to give. */
    private boolean allEligibleSubmitted(Set<String> submittedIds) {
        return players.stream()
                .filter(p -> p.isConnected() && isFinalEligible(p.getPlayerId()))
                .allMatch(p -> submittedIds.contains(p.getPlayerId()));
    }

    private LiveActionOutcome revealFinalResults() {
        List<FinalResultEntry> results = new ArrayList<>();
        for (String playerId : finalWagers.keySet()) {
            SessionPlayer player = findPlayer(playerId);
            int wager = finalWagers.get(playerId);
            String answer = finalAnswers.getOrDefault(playerId, "");
            boolean correct = isCorrect(answer, finalAnswerKey, finalResponseChoices);
            int delta = correct ? wager : -wager;
            player.addToScore(delta);
            results.add(new FinalResultEntry(playerId, player.getDisplayName(), wager, answer, correct, delta,
                    player.getScore()));
        }
        finalResults = results;
        String tiebreaker = endGame();
        return LiveActionOutcome.of(toSnapshot(null),
                "Final Jeopardy results are in!" + (tiebreaker == null ? "" : " " + tiebreaker));
    }

    /**
     * A clue from a category not yet played this game (any category, once they all have been), for a
     * clue that stands alone rather than on a board - Final Jeopardy's, or a tiebreaker's.
     */
    private Clue pickStandaloneClue() {
        Map<String, List<Clue>> byCategory = jeopardyService.getAllClues().stream()
                .collect(Collectors.groupingBy(Clue::getCategory));
        List<String> eligible = byCategory.keySet().stream()
                .filter(cat -> !usedCategories.contains(cat))
                .collect(Collectors.toCollection(ArrayList::new));
        if (eligible.isEmpty()) {
            eligible = new ArrayList<>(byCategory.keySet());
        }
        Collections.shuffle(eligible);
        String category = eligible.get(0);
        usedCategories.add(category);
        List<Clue> candidates = byCategory.get(category);
        return candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
    }

    // ---- Game end and the tiebreaker
    // ------------------------------------------------------------------

    /**
     * Ends the game, or - when ties aren't allowed and two or more contestants share the lead -
     * sends just those contestants into a tiebreaker instead.
     *
     * @return the line announcing the tiebreaker, or null when the game is simply over.
     */
    private String endGame() {
        clearPhaseTimer();
        List<String> leaders = computeWinners();
        if (allowTies || leaders.size() < 2) {
            phase = JeopardyPhase.GAME_OVER;
            gameWinnerPlayerIds = leaders;
            return null;
        }
        tiebreakerPlayerIds = leaders;
        String tied = leaders.stream().map(id -> findPlayer(id).getDisplayName()).collect(Collectors.joining(" and "));
        return tied + " are tied for the lead at " + dollars(findPlayer(leaders.get(0)).getScore()) + "! "
                + dealTiebreakerClue();
    }

    /**
     * Puts the next tiebreaker clue's category up - the clue itself follows once
     * {@link #TIEBREAKER_INTRO_WINDOW} has passed (see {@link #openTiebreakerClue}). Called again
     * each time a tiebreaker clue goes by without anyone getting it.
     *
     * <p>Should every tied contestant have left, nobody remains who could break the tie, so it
     * stands rather than clues cycling forever in front of everyone else.
     *
     * @return the line announcing it.
     */
    private String dealTiebreakerClue() {
        activeAnswererId = null;
        revealReason = null;
        if (tiebreakerPlayerIds.stream().noneMatch(id -> findPlayer(id).isConnected())) {
            phase = JeopardyPhase.GAME_OVER;
            clueSubPhase = ClueSubPhase.NONE;
            clearPhaseTimer();
            gameWinnerPlayerIds = tiebreakerPlayerIds;
            return "Nobody who's tied is still here to break the tie, so it stands.";
        }
        tiebreakerNumber++;
        Clue clue = pickStandaloneClue();
        AnswerKey answerKey = AnswerKey.forClue(clue);
        tiebreakerCell = new BoardCell("tiebreaker|" + tiebreakerNumber, clue.getCategory(), 0, false, clue,
                answerKey, dealChoices(clue, answerKey));
        phase = JeopardyPhase.TIEBREAKER;
        clueSubPhase = ClueSubPhase.NONE;
        startPhaseTimer(TIEBREAKER_INTRO_WINDOW);
        tiebreakerIntroEntryId++;
        return (tiebreakerNumber == 1 ? "Tiebreaker" : "Tiebreaker " + tiebreakerNumber)
                + " - the category is " + clue.getCategory() + ".";
    }

    /** Invoked by a scheduled callback once a tiebreaker's category has been up long enough. */
    public LiveActionOutcome openTiebreakerClue(long expectedVersion) {
        if (expectedVersion != version || phase != JeopardyPhase.TIEBREAKER || clueSubPhase != ClueSubPhase.NONE) {
            return null;
        }
        version++;
        openBuzzer();
        return LiveActionOutcome.of(toSnapshot(null), "Here's the tiebreaker clue.");
    }

    /**
     * The first tied contestant to get a tiebreaker right wins the game. No money changes hands - it
     * settles who won, not by how much. A miss locks that contestant out of this clue and reopens
     * the buzzer for the others; once none of them can ring in, the response is shown and another
     * tiebreaker clue follows it.
     */
    private LiveActionOutcome resolveTiebreakerAnswer(String playerId, String answerText) {
        BoardCell cell = tiebreakerCell;
        SessionPlayer player = findPlayer(playerId);
        if (isCorrect(answerText, cell.getAnswerKey(), cell.getResponseChoices())) {
            phase = JeopardyPhase.GAME_OVER;
            clueSubPhase = ClueSubPhase.NONE;
            activeAnswererId = null;
            clearPhaseTimer();
            tiebreakerWinnerPlayerId = playerId;
            gameWinnerPlayerIds = List.of(playerId);
            return LiveActionOutcome.of(toSnapshot(null), player.getDisplayName() + " got the tiebreaker - "
                    + cell.getClue().getResponse() + " - and wins the game!");
        }
        cell.getLockedOutPlayerIds().add(playerId);
        String announce = player.getDisplayName() + " missed the tiebreaker.";
        if (everyoneLockedOut(cell)) {
            return revealCorrectResponse(ClueRevealReason.ALL_INCORRECT,
                    announce + " The correct response was: " + cell.getClue().getResponse());
        }
        openBuzzer();
        return LiveActionOutcome.of(toSnapshot(null), announce);
    }

    /** "$1200", or "-$400" - the minus goes in front, as a score reads on a podium. */
    private static String dollars(int amount) {
        return amount < 0 ? "-$" + (-amount) : "$" + amount;
    }

    /** Package-visible only - lets tests answer a tiebreaker without leaking it publicly. */
    BoardCell tiebreakerCellForTesting() {
        return tiebreakerCell;
    }

    // ---- Board construction
    // --------------------------------------------------------------------

    private List<BoardCell> buildBoard(int round) {
        Map<String, List<Clue>> byCategory = jeopardyService.getAllClues().stream()
                .collect(Collectors.groupingBy(Clue::getCategory));
        List<String> eligible = byCategory.entrySet().stream()
                .filter(e -> e.getValue().size() >= CLUES_PER_CATEGORY)
                .map(Map.Entry::getKey)
                .filter(cat -> !usedCategories.contains(cat))
                .collect(Collectors.toCollection(ArrayList::new));
        if (eligible.size() < CATEGORIES_PER_ROUND) {
            throw new IllegalStateException(
                    "Not enough Jeopardy categories with at least " + CLUES_PER_CATEGORY + " clues to build round "
                            + round + ".");
        }
        Collections.shuffle(eligible);
        List<String> chosenCategories = new ArrayList<>(eligible.subList(0, CATEGORIES_PER_ROUND));
        usedCategories.addAll(chosenCategories);

        int[] values = IntStream.rangeClosed(1, CLUES_PER_CATEGORY)
                .map(i -> i * clueBaseIncrement * round)
                .toArray();
        int numDailyDoubles = round;

        List<int[]> nonTopRowSlots = new ArrayList<>();
        for (int catIdx = 0; catIdx < chosenCategories.size(); catIdx++) {
            for (int row = 1; row < CLUES_PER_CATEGORY; row++) {
                nonTopRowSlots.add(new int[] { catIdx, row });
            }
        }
        Collections.shuffle(nonTopRowSlots);
        Set<String> dailyDoubleSlots = new HashSet<>();
        for (int i = 0; i < numDailyDoubles && i < nonTopRowSlots.size(); i++) {
            int[] slot = nonTopRowSlots.get(i);
            dailyDoubleSlots.add(slot[0] + ":" + slot[1]);
        }

        List<BoardCell> cells = new ArrayList<>();
        for (int catIdx = 0; catIdx < chosenCategories.size(); catIdx++) {
            String category = chosenCategories.get(catIdx);
            List<Clue> categoryClues = new ArrayList<>(byCategory.get(category));
            Collections.shuffle(categoryClues);
            for (int row = 0; row < CLUES_PER_CATEGORY; row++) {
                String cellId = category + "|" + row;
                boolean isDailyDouble = dailyDoubleSlots.contains(catIdx + ":" + row);
                Clue clue = categoryClues.get(row);
                AnswerKey answerKey = AnswerKey.forClue(clue);
                cells.add(new BoardCell(cellId, category, values[row], isDailyDouble, clue, answerKey,
                        dealChoices(clue, answerKey)));
            }
        }
        return cells;
    }

    private Map<String, BoardCell> boardById() {
        Map<String, BoardCell> map = new HashMap<>();
        for (BoardCell cell : board) {
            map.put(cell.getCellId(), cell);
        }
        return map;
    }

    private BoardCell activeCell() {
        if (phase == JeopardyPhase.TIEBREAKER) {
            return tiebreakerCell;
        }
        return activeCellId == null ? null : boardById().get(activeCellId);
    }

    // ---- Answering from a list
    // ------------------------------------------------------------------

    /**
     * The clue's options if it's answered from a list, or null for a typed answer - which is always
     * the case when more than one response is right or several are needed (see
     * {@link AnswerKey#isSingleResponse()}).
     */
    private ResponseChoices dealChoices(Clue clue, AnswerKey answerKey) {
        if (!answerKey.isSingleResponse()) {
            return null;
        }
        return responseChoiceDealer.deal(clue, ThreadLocalRandom.current()).orElse(null);
    }

    /**
     * A list clue only accepts one of the options it offered. Anything else came from a stale or
     * tampered client, so it's turned away before any state changes: the player keeps their turn
     * and can still pick before the clock runs out.
     */
    private static void requireOfferedChoice(ResponseChoices choices, String answerText) {
        if (choices != null && !choices.offers(answerText)) {
            throw new InvalidActionException("Pick one of the listed responses.");
        }
    }

    /** A list clue is judged by the exact pick; a typed answer gets the usual lenient matching. */
    private static boolean isCorrect(String answerText, AnswerKey answerKey, ResponseChoices choices) {
        if (choices != null) {
            return choices.isCorrect(answerText);
        }
        return answerText != null && AnswerMatcher.isCorrect(answerText, answerKey);
    }

    // ---- Shared helpers
    // -------------------------------------------------------------------------

    private void requirePhase(JeopardyPhase expected) {
        if (phase != expected) {
            throw new InvalidActionException("That action isn't allowed right now.");
        }
    }

    private void requirePhaseAny(JeopardyPhase... expected) {
        for (JeopardyPhase p : expected) {
            if (phase == p) {
                return;
            }
        }
        throw new InvalidActionException("That action isn't allowed right now.");
    }

    private SessionPlayer findPlayer(String playerId) {
        return players.stream().filter(p -> p.getPlayerId().equals(playerId)).findFirst()
                .orElseThrow(() -> new InvalidActionException("Unknown player."));
    }

    private List<String> computeWinners() {
        int max = players.stream().mapToInt(SessionPlayer::getScore).max().orElse(0);
        return players.stream().filter(p -> p.getScore() == max).map(SessionPlayer::getPlayerId).toList();
    }

    @Override
    public Optional<CompletedGame> claimCompletedGame() {
        if (phase != JeopardyPhase.GAME_OVER || completedGameClaimed) {
            return Optional.empty();
        }
        completedGameClaimed = true;
        return Optional.of(CompletedGame.of(GameType.JEOPARDY, maxRounds, Instant.now(), players,
                tiebreakerWinnerPlayerId));
    }

    @Override
    public JeopardyStateSnapshot toSnapshot(String forPlayerId) {
        BoardCell cell = activeCell();
        boolean finalClueRevealed = finalClue != null && isFinalAnswerVisiblePhase();
        return new JeopardyStateSnapshot(
                "JEOPARDY_STATE",
                phase.name(),
                currentRound,
                maxRounds,
                board.stream().map(BoardCellView::from).toList(),
                buildActiveCellView(forPlayerId, cell),
                clueSubPhase.name(),
                activeAnswererId,
                cell == null ? List.of() : List.copyOf(cell.getLockedOutPlayerIds()),
                playerIdInControl,
                phaseStartedAt,
                phaseDeadlineAt,
                finalCategory,
                finalClueRevealed ? finalClue.getClueText() : null,
                finalClueRevealed ? optionsOf(finalResponseChoices) : null,
                finalClueRevealed ? finalAnswerKey.requiredCount() : null,
                finalEligiblePlayerIds,
                isFinalWagerVisiblePhase() ? List.copyOf(finalWagers.keySet()) : null,
                isFinalAnswerVisiblePhase() ? List.copyOf(finalAnswers.keySet()) : null,
                finalResults,
                tiebreakerPlayerIds,
                tiebreakerNumber == 0 ? null : tiebreakerNumber,
                players.stream().map(PlayerView::from).toList(),
                gameWinnerPlayerIds);
    }

    /** A tiebreaker comes after Final Jeopardy, so everything about the Final round stays public through it. */
    private boolean isFinalWagerVisiblePhase() {
        return phase == JeopardyPhase.FINAL_WAGER || isFinalAnswerVisiblePhase();
    }

    private boolean isFinalAnswerVisiblePhase() {
        return phase == JeopardyPhase.FINAL_ANSWER_COLLECTION || phase == JeopardyPhase.TIEBREAKER
                || phase == JeopardyPhase.GAME_OVER;
    }

    private ActiveCellView buildActiveCellView(String forPlayerId, BoardCell cell) {
        if (cell == null) {
            return null;
        }
        boolean wagerPhase = phase == JeopardyPhase.DAILY_DOUBLE_WAGER;
        boolean answerPhase = phase == JeopardyPhase.DAILY_DOUBLE_ANSWER;
        boolean dailyDoubleInProgress = cell.isDailyDouble() && (wagerPhase || answerPhase);

        boolean revealClueText;
        if (phase == JeopardyPhase.TIEBREAKER) {
            // Only the category is up during a tiebreaker's intro.
            revealClueText = clueSubPhase != ClueSubPhase.NONE;
        } else if (!cell.isDailyDouble()) {
            revealClueText = true;
        } else if (wagerPhase) {
            revealClueText = false;
        } else if (answerPhase) {
            revealClueText = forPlayerId != null && forPlayerId.equals(activeAnswererId);
        } else {
            revealClueText = true;
        }

        boolean revealResponse = clueSubPhase == ClueSubPhase.REVEALED;

        return new ActiveCellView(
                cell.getCellId(),
                cell.getCategory(),
                cell.getValue(),
                revealClueText ? cell.getClue().getClueText() : null,
                cell.isDailyDouble(),
                dailyDoubleInProgress,
                revealClueText ? optionsOf(cell.getResponseChoices()) : null,
                revealClueText ? cell.getAnswerKey().requiredCount() : null,
                revealResponse ? cell.getClue().getResponse() : null,
                revealResponse ? revealReason.name() : null);
    }

    private static List<String> optionsOf(ResponseChoices choices) {
        return choices == null ? null : choices.options();
    }
}
