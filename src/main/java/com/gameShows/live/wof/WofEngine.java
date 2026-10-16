package com.gameShows.live.wof;

import com.gameShows.live.error.InvalidActionException;
import com.gameShows.live.session.AiCapableEngine;
import com.gameShows.live.session.AiDifficulty;
import com.gameShows.live.session.AiMove;
import com.gameShows.live.session.LiveActionOutcome;
import com.gameShows.live.session.LiveGameEngine;
import com.gameShows.live.session.PlayerView;
import com.gameShows.live.session.SessionPlayer;
import com.gameShows.models.wof.Puzzle;
import com.gameShows.models.wof.Wedge;
import com.gameShows.models.wof.WheelLayout;
import com.gameShows.services.WheelOfFortuneService;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * One instance per live Wheel of Fortune game session. Not a Spring bean - constructed per game by
 * whoever starts the session, and mutated only while the owning GameSession's lock is held.
 *
 * <p>Game flow: Fortune Frenzy - 30 seconds of rapid-fire $1,000 toss-ups, all from one category,
 * where a wrong answer throws the puzzle out - decides who starts Round 1; Rounds 1-3
 * are normal spin-and-guess play; a Triple Toss-Up (three same-category
 * puzzles worth $2,000 each, $4,000 more if one contestant sweeps all three) precedes Round 4.
 * From Round 4 on, normal rounds just keep going (Round 4, 5, 6, ...) - open-ended - until the
 * overall game clock runs out, at which point the very next "waiting for a turn" moment (even
 * mid-round) goes to the Final Spin instead: whoever's up spins for real (re-spinning past any
 * Bankrupt/Lose a Turn) until they land a dollar value, which becomes the fixed per-letter value
 * for one last speed-up puzzle. The overall high scorer then plays a solo bonus round for a hidden
 * cash prize. Only then does the game truly end.
 *
 * <p>Two special wedges ride on the wheel (see {@link Wedge#buildSyndicatedWheel(int, boolean,
 * boolean)}), each claimed by landing on it and calling a correct consonant:
 * <ul>
 *   <li>The Wild Card: played straight after a correct consonant on a spin, it calls one more
 *       consonant at the same value; or, kept to the Bonus Round, it adds a fourth consonant. It
 *       can't be played in the Speed-Up, and once played it's gone for the game.</li>
 *   <li>The Million Dollar Wedge (Rounds 1-3 only): kept only if its holder solves the round they
 *       picked it up in. If they then win the game, the Bonus Round's $100,000 envelope becomes
 *       $1,000,000.</li>
 * </ul>
 * Bankrupt takes either one away. A lost wedge goes back on the wheel at the start of the next
 * round it's allowed in.
 */
public class WofEngine implements LiveGameEngine, AiCapableEngine {

    public static final Duration DEFAULT_GAME_DURATION = Duration.ofMinutes(20);
    /** Rounds 1-3 are always played in full regardless of the clock; the Triple Toss-Up fires the
     *  first time round-advancement reaches this round number. */
    private static final int TRIPLE_TOSS_UP_AT_ROUND = 4;
    /** What the snapshot reports as "totalRounds" while it's still meaningful (see
     *  {@link WofStateSnapshot#totalRounds()}) - once the Triple Toss-Up has happened, rounds are
     *  open-ended and this stops being reported. */
    private static final int FIXED_ROUNDS_REPORTED = TRIPLE_TOSS_UP_AT_ROUND - 1;

    private static final int VOWEL_COST = 250;
    private static final String VOWELS = "AEIOU";
    private static final String RSTLNE = "RSTLNE";
    private static final int BONUS_CONSONANTS_TO_PICK = 3;
    private static final int[] BONUS_PRIZES = {30_000, 40_000, 50_000, 75_000, 100_000};
    /** The envelope the Million Dollar Wedge swaps out of the Bonus Wheel... */
    private static final int BONUS_TOP_PRIZE = 100_000;
    /** ...and what it swaps in. */
    static final int MILLION_DOLLAR_PRIZE = 1_000_000;

    /** The Million Dollar Wedge is only on the wheel in the fixed rounds before the Triple Toss-Up. */
    private static final int MILLION_WEDGE_LAST_ROUND = TRIPLE_TOSS_UP_AT_ROUND - 1;
    /** An AI holding the Wild Card only plays it on a top-dollar spin; anything less, and it keeps
     *  the card for the Bonus Round's fourth consonant. */
    private static final int AI_WILD_CARD_MIN_SPIN_VALUE = 2500;
    /** The special wedges come off the wheel for the Final Spin: it only sets a dollar value for the
     *  Speed-Up, where neither can be won or played. */
    private static final Set<WofPhase> PHASES_WITHOUT_SPECIAL_WEDGES = EnumSet.of(WofPhase.FINAL_SPIN,
            WofPhase.SPEEDUP_AWAITING_LETTER, WofPhase.SPEEDUP_AWAITING_SOLVE, WofPhase.BONUS_PICK_CATEGORY,
            WofPhase.BONUS_PICK_LETTERS, WofPhase.BONUS_SOLVE, WofPhase.GAME_OVER);

    /** AI "thinking" delays are randomized within these human-reaction-time ranges (rather than a
     *  fixed duration) so AI opponents feel like they're actually pausing to decide, not executing
     *  on a metronome - see {@link #humanDelay(Duration, Duration)}. Ranges are narrower for
     *  reflexive buzzer reactions (the AI already knows the answer, it's just reacting) and wider
     *  for open-ended decisions (choosing a letter, deciding to spin, typing out a solve). */
    private static final Duration AI_THINK_DELAY_MIN = Duration.ofMillis(900);
    private static final Duration AI_THINK_DELAY_MAX = Duration.ofMillis(2600);
    /** After a round (or a Frenzy puzzle) ends, a human must click Continue before play resumes -
     *  an AI opponent "clicks" it too, after this human-like pause, so AI-only/mixed games don't
     *  stall forever waiting on a bot to press a button. */
    private static final Duration AI_CONTINUE_DELAY_MIN = Duration.ofSeconds(2);
    private static final Duration AI_CONTINUE_DELAY_MAX = Duration.ofSeconds(5);

    private static final int FRENZY_PUZZLE_VALUE = 1000;
    /** The Frenzy clock. It runs while a puzzle is on the board - including while a contestant who
     *  rang in is answering - and stops while a finished puzzle's solution is shown. */
    private static final Duration FRENZY_WINDOW = Duration.ofSeconds(30);
    /** One random letter cell every 0.4s - the show's pace after its first week (it began at 0.5s). */
    private static final Duration FRENZY_REVEAL_INTERVAL = Duration.ofMillis(400);
    /** On the show a contestant must answer the moment they're prompted. Answers here are typed
     *  into the letter boxes, so they get a short window to do it rather than the usual 30s. */
    private static final Duration FRENZY_ANSWER_WINDOW = Duration.ofSeconds(10);
    /** How long a finished puzzle's solution stays up (the frame flashing green or red) before the
     *  next puzzle comes up on its own. The Frenzy clock is stopped meanwhile. */
    private static final Duration FRENZY_RESULT_PAUSE = Duration.ofMillis(2500);
    /** With less than this left on the clock no new puzzle is started - it would vanish before
     *  anyone could read it - so the Frenzy ends instead. */
    private static final Duration FRENZY_MIN_PUZZLE_TIME = Duration.ofSeconds(1);
    private static final Duration AI_FRENZY_ANSWER_DELAY_MIN = Duration.ofMillis(400);
    private static final Duration AI_FRENZY_ANSWER_DELAY_MAX = Duration.ofMillis(1400);

    private static final int TRIPLE_TOSS_UP_VALUE = 2000;
    private static final int TRIPLE_TOSS_UP_SWEEP_BONUS = 4000;
    private static final int TRIPLE_TOSS_UP_PUZZLES = 3;
    private static final Duration TRIPLE_TOSS_UP_REVEAL_INTERVAL = Duration.ofMillis(2500);
    private static final Duration AI_TRIPLE_TOSS_UP_ANSWER_DELAY_MIN = Duration.ofMillis(500);
    private static final Duration AI_TRIPLE_TOSS_UP_ANSWER_DELAY_MAX = Duration.ofMillis(1800);

    private static final int SPEEDUP_VALUE_BONUS = 1000;
    // Human players get the same 30s PLAYER_SOLVE_WINDOW as everywhere else (see below) - the
    // Speed-Up round's "speed" comes from consonants being called and revealed in real time, not
    // from a shorter solve window.
    private static final Duration SPEEDUP_SOLVE_WINDOW = Duration.ofSeconds(30);
    private static final Duration AI_SPEEDUP_SOLVE_DELAY_MIN = Duration.ofMillis(600);
    private static final Duration AI_SPEEDUP_SOLVE_DELAY_MAX = Duration.ofMillis(1800);

    /** Categories the bonus contestant chooses between, as on the show. */
    static final int BONUS_CATEGORY_CHOICES = 3;
    /** Longer than the usual think time: the three categories are up on the big screen, and the
     *  room should get to read them before a bot makes its pick. */
    private static final Duration AI_BONUS_CATEGORY_DELAY_MIN = Duration.ofMillis(2500);
    private static final Duration AI_BONUS_CATEGORY_DELAY_MAX = Duration.ofMillis(4000);
    private static final Duration BONUS_SOLVE_WINDOW = Duration.ofSeconds(30);
    private static final Duration AI_BONUS_SOLVE_DELAY_MIN = Duration.ofMillis(1200);
    private static final Duration AI_BONUS_SOLVE_DELAY_MAX = Duration.ofMillis(3000);

    /** Shared by the main round, Fortune Frenzy, and the Triple Toss-Up: once a player declares
     *  they're solving, they have this long to submit before it counts as a miss. */
    private static final Duration PLAYER_SOLVE_WINDOW = Duration.ofSeconds(30);

    /** How long the physical wheel turns. Randomized so spins don't all run to the same beat, and
     *  kept short enough that a turn never drags. */
    private static final Duration SPIN_DURATION_MIN = Duration.ofMillis(5200);
    private static final Duration SPIN_DURATION_MAX = Duration.ofMillis(6800);
    /** Extra wait between the wheel stopping on screen and the server applying the result, so a
     *  little network lag never lets the result (a Bankrupt wipe, the turn passing) show up while
     *  the wheel is still turning. */
    private static final Duration SPIN_REVEAL_GRACE = Duration.ofMillis(350);

    private final List<SessionPlayer> players;
    private final WheelOfFortuneService wofService;
    private final AiDifficulty aiDifficulty;
    private final Set<Long> usedPuzzleIds = new HashSet<>();
    private final Set<Character> guessedConsonants = new LinkedHashSet<>();
    private final Set<Character> guessedVowels = new LinkedHashSet<>();

    private int roundNumber = 0;
    private int currentPlayerIndex = 0;
    /** Whether the player whose turn it is has spun yet this turn. Reset by {@link #startTurnOf},
     *  the one place a turn changes hands. */
    private boolean spunThisTurn;
    private WofPhase phase = WofPhase.FORTUNE_FRENZY;
    private Puzzle currentPuzzle;
    /** The wedge this turn's spin landed on, exactly as it was when it landed - a Wild Card or
     *  Million Dollar Wedge that's since been claimed off the wheel still reads as what was won. */
    private Wedge lastSpinResult;
    private int lastSpinResultStartSlot;
    /** The latest physical spin. Deliberately not reset between rounds: the real wheel stays
     *  wherever it last stopped. */
    private WheelSpin lastWheelSpin;
    private long spinSequence = 0;
    /** True from a spin until {@link #settleSpin} applies its result - no turn action is accepted
     *  in between (see {@link #requireWheelAtRest()}). */
    private boolean wheelInMotion;
    private String roundWinnerPlayerId;
    private List<String> gameWinnerPlayerIds;
    private long version = 0;

    /** Shared by Fortune Frenzy and the Triple Toss-Up: who's used their one attempt on the puzzle
     *  currently on screen. Cleared every time a new puzzle in either sequence comes up. */
    private final Set<String> puzzleLockedOutPlayerIds = new HashSet<>();

    /** Fortune Frenzy reveals by BOARD POSITION, not by letter (unlike a normal round's "call a
     *  letter" reveal, which exposes every occurrence at once): each tick picks one still-blank
     *  letter cell at random and fills in just that cell, so a repeated letter (e.g. three E's)
     *  can appear on screen at three different times. Indexes into the current Frenzy puzzle's
     *  (trimmed) text; cleared every time a new Frenzy puzzle comes up. */
    private final Set<Integer> frenzyRevealedPositions = new HashSet<>();

    /** Money won this round (rounds 1-3, the open-ended rounds 4+, and the final speed-up round)
     *  that hasn't been folded into the player's permanent SessionPlayer.score yet - landing on
     *  BANKRUPT wipes only this, never the permanent total, and it's banked into the permanent
     *  total the moment the round ends (solved, fully revealed, or cut short by the game clock).
     *  Frenzy/Triple-Toss-Up/Bonus cash prizes bypass this entirely and go straight to the
     *  permanent total, since they aren't part of "the round" wheel-spinning gameplay. */
    private final Map<String, Integer> roundScores = new LinkedHashMap<>();

    // Main round: the 30s letter-box solve window, opened only by an explicit declare action.
    // Every timed window keeps its start alongside its deadline so clients can draw how much of it
    // is left, not just when it ends.
    private String solvingPlayerId;
    private Instant solveStartedAt;
    private Instant solveDeadlineAt;

    /** When the Frenzy clock runs out, while it's running (a puzzle is on the board). */
    private Instant frenzyDeadlineAt;
    /** Flips true once the Frenzy clock has run out (see {@link #handleFrenzyTimeout}). A contestant
     *  who rang in before that still gets to answer; otherwise the Frenzy is over, and the Continue
     *  after the last solution begins Round 1. */
    private boolean frenzyTimeUp;
    private String frenzyLastSolverPlayerId;
    /** Fortune Frenzy's buzz-in answer window: only one player may hold it at a time, and whatever
     *  they answer (or fail to) settles the puzzle. */
    private String frenzySolvingPlayerId;
    private Instant frenzySolveStartedAt;
    private Instant frenzySolveDeadlineAt;
    /** Set the moment a Frenzy puzzle resolves (phase moves to FRENZY_PUZZLE_OVER) to how much
     *  Frenzy clock was left at that instant. The clock stays stopped while the solution is up, then
     *  resumes from this same budget with the next puzzle (see {@link #advanceFrenzy()}). */
    private Duration frenzyTimeRemainingOnPause;
    /** When the next Frenzy puzzle comes up on its own, while a solution is up with time left. */
    private Instant frenzyNextPuzzleAt;
    /** Every Frenzy puzzle comes from this one category. */
    private String frenzyCategory;
    /** 1-based count of Frenzy puzzles brought up so far. Also tags each AI buzz-in, so one meant
     *  for a puzzle that's since been thrown out can't land on the next. */
    private int frenzyPuzzleNumber;
    /** How the puzzle on screen in FRENZY_PUZZLE_OVER ended, and who rang in on it (if anyone). */
    private FrenzyOutcome frenzyOutcome;
    private String frenzyOutcomePlayerId;

    private List<Puzzle> tripleTossUpPuzzles;
    private String tripleTossUpCategory;
    private int tripleTossUpIndex;
    private final List<String> tripleTossUpSolverIds = new ArrayList<>();
    /** Triple Toss-Up's own 30s buzz-in solve window, same rule as Frenzy's. */
    private String tripleTossUpSolvingPlayerId;
    private Instant tripleTossUpSolveStartedAt;
    private Instant tripleTossUpSolveDeadlineAt;
    /** Flips true the moment the Triple Toss-Up begins - guards it from firing again once rounds
     *  keep climbing past 4, and gates the game clock (rounds 1-3 always play out in full). */
    private boolean tripleTossUpDone;

    private final Instant gameClockStartedAt;
    private final Instant gameClockEndsAt;

    private Integer speedupLetterValue;
    private String speedupSolvingPlayerId;

    // Special wedges - one of each per game. "On the wheel" is decided at the start of each round:
    // one lost to Bankrupt (or, for the Million wedge, to an unsolved round) mid-round stays off
    // until the next round.
    private boolean wildCardOnWheel = true;
    private String wildCardHolderId;
    /** Played (in a round or the Bonus Round) - gone for the rest of the game. */
    private boolean wildCardPlayed;
    /** The version at which the current player called a correct consonant on a spin. The Wild Card
     *  may only be played as the very next move, so it's playable only while nothing has happened
     *  since: any other action bumps {@link #version} past it. */
    private long wildCardWindowVersion = -1;
    /** True while the consonant being called is the Wild Card's extra letter. */
    private boolean wildCardLetterInPlay;
    private boolean millionWedgeOnWheel = true;
    private String millionWedgeHolderId;
    /** False while the holder still has to solve the round they picked it up in. */
    private boolean millionWedgeSecured;

    private String bonusWinnerPlayerId;
    private List<String> bonusCategoryOptions;
    private Puzzle bonusPuzzle;
    private final Set<Character> bonusRevealedLetters = new LinkedHashSet<>();
    private Integer bonusPrizeAmount;
    private Boolean bonusSolved;
    /** Three, or four when the bonus contestant brings the Wild Card along. */
    private int bonusConsonantCount = BONUS_CONSONANTS_TO_PICK;
    /** The bonus contestant holds the Million Dollar Wedge: the $1,000,000 envelope is on the Bonus Wheel. */
    private boolean bonusMillionInPlay;

    public WofEngine(List<SessionPlayer> playersInJoinOrder, WheelOfFortuneService wofService) {
        this(playersInJoinOrder, wofService, DEFAULT_GAME_DURATION);
    }

    /** @param gameDuration how long the game clock runs (from construction) before Round 4+ starts
     *  routing into the Final Spin instead of another normal round; host-configurable at session
     *  start. */
    public WofEngine(List<SessionPlayer> playersInJoinOrder, WheelOfFortuneService wofService, Duration gameDuration) {
        this(playersInJoinOrder, wofService, gameDuration, AiDifficulty.DEFAULT);
    }

    /** @param aiDifficulty how strong the computer opponents are; null means the default. */
    public WofEngine(List<SessionPlayer> playersInJoinOrder, WheelOfFortuneService wofService, Duration gameDuration,
            AiDifficulty aiDifficulty) {
        this.players = playersInJoinOrder;
        this.wofService = wofService;
        this.aiDifficulty = AiDifficulty.orDefault(aiDifficulty);
        this.frenzyCategory = pickFrenzyCategory();
        this.currentPuzzle = pickFrenzyPuzzle();
        this.frenzyPuzzleNumber = 1;
        this.frenzyDeadlineAt = Instant.now().plus(frenzyWindow());
        this.gameClockStartedAt = Instant.now();
        this.gameClockEndsAt = gameClockStartedAt.plus(gameDuration);
    }

    /** Extracted so tests can force a short Frenzy window instead of waiting a full real minute -
     *  same idea as {@link #chooseWedgeIndex}'s override hook for forcing wheel outcomes. */
    protected Duration frenzyWindow() {
        return FRENZY_WINDOW;
    }

    public long getVersion() {
        return version;
    }

    public WofPhase getPhase() {
        return phase;
    }

    /** Package-visible only - lets tests solve the actual puzzle instead of guessing which one a
     *  mocked puzzle pool randomly produced. */
    Puzzle currentPuzzleForTesting() {
        return currentPuzzle;
    }

    Puzzle bonusPuzzleForTesting() {
        return bonusPuzzle;
    }

    // ---- Fortune Frenzy (pre-round-1) ----------------------------------------------------------

    /** Invoked by a scheduled callback every 0.4s while Frenzy is active; no-op if state has since
     *  moved on. Also a no-op while a player is answering (see {@link #beginFrenzySolve}) - the
     *  board stops filling in the moment someone rings in. If the board fills in with nobody
     *  ringing in, the puzzle goes unsolved. Deliberately unannounced: at this pace a line per
     *  letter would drown out everything else a screen reader has to say. */
    public LiveActionOutcome revealNextFrenzyLetter(long expectedVersion) {
        if (expectedVersion != version || phase != WofPhase.FORTUNE_FRENZY || frenzySolvingPlayerId != null) {
            return null;
        }
        version++;
        Integer position = pickUnrevealedFrenzyPosition();
        if (position == null) {
            return resolveFrenzyPuzzle(FrenzyOutcome.UNSOLVED, null, "Nobody rang in on that one!");
        }
        frenzyRevealedPositions.add(position);
        return LiveActionOutcome.of(toSnapshot(null));
    }

    /** How much of the current Frenzy puzzle's letter cells are showing, from 0 to 1. */
    private double frenzyRevealedFraction() {
        String text = currentPuzzle.getPuzzle().stripTrailing();
        long letterCells = text.chars().filter(Character::isLetter).count();
        return letterCells == 0 ? 1.0 : (double) frenzyRevealedPositions.size() / letterCells;
    }

    /** Picks a random still-blank letter cell (by board position) in the current Frenzy puzzle, or
     *  null once every letter cell has been filled in. */
    private Integer pickUnrevealedFrenzyPosition() {
        String text = currentPuzzle.getPuzzle().stripTrailing();
        List<Integer> candidates = new ArrayList<>();
        for (int i = 0; i < text.length(); i++) {
            if (Character.isLetter(text.charAt(i)) && !frenzyRevealedPositions.contains(i)) {
                candidates.add(i);
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        return candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
    }

    /** Rings this player in - buzz-in style, only one player may hold the buzzer at a time, and
     *  whatever they answer settles the puzzle: there's one attempt per puzzle, not per player. The
     *  Frenzy clock keeps running while they answer. */
    public LiveActionOutcome beginFrenzySolve(String playerId) {
        requirePhase(WofPhase.FORTUNE_FRENZY);
        SessionPlayer player = findPlayer(playerId);
        if (frenzySolvingPlayerId != null) {
            throw new InvalidActionException("Someone else is already answering.");
        }
        version++;
        frenzySolvingPlayerId = playerId;
        frenzySolveStartedAt = Instant.now();
        frenzySolveDeadlineAt = frenzySolveStartedAt.plus(FRENZY_ANSWER_WINDOW);
        return LiveActionOutcome.of(toSnapshot(null), player.getDisplayName() + " rang in!");
    }

    /** An AI's ring-in, tagged with the puzzle it was decided on: it's refused once that puzzle is
     *  gone, so a slow bot can't ring in blind on the puzzle after it. */
    LiveActionOutcome aiBeginFrenzySolve(String aiPlayerId, int forPuzzleNumber) {
        requireCurrentFrenzyPuzzle(forPuzzleNumber);
        return beginFrenzySolve(aiPlayerId);
    }

    public LiveActionOutcome attemptFrenzyAnswer(String playerId, String guess) {
        requirePhase(WofPhase.FORTUNE_FRENZY);
        if (!playerId.equals(frenzySolvingPlayerId)) {
            throw new InvalidActionException("It isn't your turn to answer.");
        }
        version++;
        return resolveFrenzyAnswer(playerId, guess);
    }

    /** An AI's answer, tagged the same way as {@link #aiBeginFrenzySolve} - a guess worked out for
     *  one puzzle must never be submitted against the next. */
    LiveActionOutcome aiAttemptFrenzyAnswer(String aiPlayerId, int forPuzzleNumber, String guess) {
        requireCurrentFrenzyPuzzle(forPuzzleNumber);
        return attemptFrenzyAnswer(aiPlayerId, guess);
    }

    /** Throws rather than no-ops: a refused scheduled action doesn't re-arm every pending move the
     *  way a null outcome does (see LiveGameOrchestrator#act). */
    private void requireCurrentFrenzyPuzzle(int puzzleNumber) {
        if (phase != WofPhase.FORTUNE_FRENZY || puzzleNumber != frenzyPuzzleNumber) {
            throw new InvalidActionException("That puzzle is over.");
        }
    }

    /** Invoked by a scheduled callback when a contestant's answer window lapses; no-op if state has
     *  since moved on. No answer counts the same as a wrong one. */
    public LiveActionOutcome handleFrenzySolveTimeout(long expectedVersion) {
        if (expectedVersion != version || phase != WofPhase.FORTUNE_FRENZY || frenzySolvingPlayerId == null) {
            return null;
        }
        version++;
        return resolveFrenzyAnswer(frenzySolvingPlayerId, null);
    }

    /** Right: $1,000. Wrong, or nothing in time: the puzzle is revealed and thrown out - there's no
     *  other penalty, and nobody else gets a go at it. */
    private LiveActionOutcome resolveFrenzyAnswer(String playerId, String guess) {
        SessionPlayer player = findPlayer(playerId);
        if (guess != null && normalize(guess).equals(normalize(currentPuzzle.getPuzzle()))) {
            player.addToScore(FRENZY_PUZZLE_VALUE);
            frenzyLastSolverPlayerId = playerId;
            return resolveFrenzyPuzzle(FrenzyOutcome.SOLVED, playerId,
                    player.getDisplayName() + " solved it for $" + FRENZY_PUZZLE_VALUE + "!");
        }
        String miss = guess == null ? " didn't answer in time" : " missed";
        return resolveFrenzyPuzzle(FrenzyOutcome.MISSED, playerId,
                player.getDisplayName() + miss + " - that puzzle is thrown out.");
    }

    /** Invoked by a scheduled callback when the Frenzy clock runs out; no-op if state has since
     *  moved on. A contestant who rang in before then still gets to answer, and their answer ends
     *  the Frenzy. Otherwise the puzzle on the board goes unsolved and the Frenzy ends now. */
    public LiveActionOutcome handleFrenzyTimeout(long expectedVersion) {
        if (expectedVersion != version || phase != WofPhase.FORTUNE_FRENZY || frenzyTimeUp) {
            return null;
        }
        version++;
        frenzyTimeUp = true;
        if (frenzySolvingPlayerId != null) {
            return LiveActionOutcome.of(toSnapshot(null),
                    "Time's up! " + findPlayer(frenzySolvingPlayerId).getDisplayName() + " still gets to answer.");
        }
        return resolveFrenzyPuzzle(FrenzyOutcome.UNSOLVED, null, "Time's up!");
    }

    /** Whoever gave the last correct Frenzy answer - or, if nobody solved one, the first player. */
    private String frenzyRoundOneStarterId() {
        return frenzyLastSolverPlayerId != null ? frenzyLastSolverPlayerId : players.get(0).getPlayerId();
    }

    private LiveActionOutcome endFrenzyAndBeginRound1() {
        String starter = frenzyRoundOneStarterId();
        LiveActionOutcome begin = beginRound1(starter);
        return LiveActionOutcome.of(begin.snapshot(), "Fortune Frenzy is over! " + begin.announceText());
    }

    /** A Frenzy puzzle just ended - stops the Frenzy clock (remembering how much was left), reveals
     *  the solution, and moves to FRENZY_PUZZLE_OVER. With time left the next puzzle comes up on its
     *  own after {@link #FRENZY_RESULT_PAUSE}; once time's up the Frenzy is over and waits for a
     *  player to click Continue into Round 1. */
    private LiveActionOutcome resolveFrenzyPuzzle(FrenzyOutcome outcome, String playerIdOrNull, String announce) {
        // The flag, not the clock, decides: once time's up this was the last puzzle, however the
        // wall clock and the scheduled timeout happen to line up.
        Duration left = frenzyTimeUp
                ? Duration.ZERO
                : clampNonNegative(Duration.between(Instant.now(), frenzyDeadlineAt));
        if (left.compareTo(FRENZY_MIN_PUZZLE_TIME) < 0) {
            frenzyTimeUp = true;
            left = Duration.ZERO;
        }
        frenzyTimeRemainingOnPause = left;
        frenzyNextPuzzleAt = frenzyTimeUp ? null : Instant.now().plus(FRENZY_RESULT_PAUSE);
        frenzyOutcome = outcome;
        frenzyOutcomePlayerId = playerIdOrNull;
        revealEntireCurrentPuzzle();
        frenzySolvingPlayerId = null;
        frenzySolveStartedAt = null;
        frenzySolveDeadlineAt = null;
        phase = WofPhase.FRENZY_PUZZLE_OVER;
        String solution = " The answer: \"" + currentPuzzle.getPuzzle().strip() + "\".";
        String next = frenzyTimeUp ? " Fortune Frenzy is over!" : "";
        return LiveActionOutcome.of(toSnapshot(null),
                announce + (outcome == FrenzyOutcome.SOLVED ? "" : solution) + next);
    }

    private void revealEntireCurrentPuzzle() {
        for (char c : currentPuzzle.getPuzzle().toUpperCase().toCharArray()) {
            if (!Character.isLetter(c)) {
                continue;
            }
            if (VOWELS.indexOf(c) >= 0) {
                guessedVowels.add(c);
            } else {
                guessedConsonants.add(c);
            }
        }
    }

    /** Any connected player may click Continue once Fortune Frenzy is over - first click wins,
     *  matching the buzz-in pattern used elsewhere (a second click after that simply finds the phase
     *  has already moved on and is refused by requirePhase). Mid-Frenzy the next puzzle comes up on
     *  its own (see {@link #advanceFrenzyAfterPause}), but a click there just skips the wait. */
    public LiveActionOutcome continueFrenzy(String playerId) {
        requirePhase(WofPhase.FRENZY_PUZZLE_OVER);
        findPlayer(playerId);
        version++;
        return advanceFrenzy();
    }

    /** Invoked by a scheduled callback once a finished puzzle's solution has been up for
     *  {@link #FRENZY_RESULT_PAUSE}; no-op if state has since moved on, or if the Frenzy is over
     *  (that waits for a Continue click instead). */
    public LiveActionOutcome advanceFrenzyAfterPause(long expectedVersion) {
        if (expectedVersion != version || phase != WofPhase.FRENZY_PUZZLE_OVER || frenzyTimeUp) {
            return null;
        }
        version++;
        return advanceFrenzy();
    }

    /** Brings up the next puzzle, restarting the Frenzy clock from wherever it stopped - or, with
     *  no time left, ends Fortune Frenzy and begins Round 1. */
    private LiveActionOutcome advanceFrenzy() {
        if (frenzyTimeUp) {
            return endFrenzyAndBeginRound1();
        }
        frenzyDeadlineAt = Instant.now().plus(frenzyTimeRemainingOnPause);
        phase = WofPhase.FORTUNE_FRENZY;
        return startNextFrenzyPuzzle("Next puzzle!");
    }

    private LiveActionOutcome startNextFrenzyPuzzle(String announce) {
        currentPuzzle = pickFrenzyPuzzle();
        frenzyPuzzleNumber++;
        guessedConsonants.clear();
        guessedVowels.clear();
        frenzyRevealedPositions.clear();
        puzzleLockedOutPlayerIds.clear();
        frenzyOutcome = null;
        frenzyOutcomePlayerId = null;
        frenzyNextPuzzleAt = null;
        // Defensive: every path here already cleared the buzzer, but each puzzle starts clean.
        frenzySolvingPlayerId = null;
        frenzySolveStartedAt = null;
        frenzySolveDeadlineAt = null;
        return LiveActionOutcome.of(toSnapshot(null), announce);
    }

    /** Every Frenzy puzzle shares one category: the one with the most puzzles not yet used this
     *  game (ties broken at random), so the Frenzy is unlikely to run dry part-way. Null when there
     *  are no categories at all - {@link #pickFrenzyPuzzle()} then falls back to any puzzle. */
    private String pickFrenzyCategory() {
        List<String> categories = new ArrayList<>(wofService.getAllCategories());
        Collections.shuffle(categories);
        String best = null;
        long bestUnused = 0;
        for (String category : categories) {
            long unused = wofService.getPuzzlesByCategory(category).stream()
                    .filter(p -> !usedPuzzleIds.contains(p.getId())).count();
            if (unused > bestUnused) {
                best = category;
                bestUnused = unused;
            }
        }
        return best;
    }

    /** A puzzle from the Frenzy category: preferably one not yet used this game, and preferably a
     *  short one, since letters land every 0.4s. Once the category has nothing unused left, its
     *  puzzles come round again rather than breaking the one-category rule. */
    private Puzzle pickFrenzyPuzzle() {
        List<Puzzle> inCategory = frenzyCategory == null ? List.of() : wofService.getPuzzlesByCategory(frenzyCategory);
        if (inCategory.isEmpty()) {
            Puzzle any = pickPuzzle();
            frenzyCategory = any.getCategory();
            return any;
        }
        List<Puzzle> unused = inCategory.stream().filter(p -> !usedPuzzleIds.contains(p.getId())).toList();
        List<Puzzle> candidates = unused.isEmpty() ? inCategory : unused;
        List<Puzzle> shortOnes = candidates.stream().filter(Puzzle::isShort).toList();
        if (!shortOnes.isEmpty()) {
            candidates = shortOnes;
        }
        Puzzle chosen = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
        usedPuzzleIds.add(chosen.getId());
        return chosen;
    }

    private LiveActionOutcome beginRound1(String startingPlayerId) {
        bankAndResetRoundScores();
        roundNumber = 1;
        currentPuzzle = pickPuzzle();
        guessedConsonants.clear();
        guessedVowels.clear();
        puzzleLockedOutPlayerIds.clear();
        lastSpinResult = null;
        returnSpecialWedgesToTheWheel();
        startTurnOf(indexOfPlayer(startingPlayerId));
        phase = WofPhase.WAITING_FOR_TURN_ACTION;
        return LiveActionOutcome.of(toSnapshot(null),
                "Round 1 begins with " + findPlayer(startingPlayerId).getDisplayName() + "!");
    }

    private Character pickUnrevealedTossUpLetter() {
        String puzzleUpper = currentPuzzle.getPuzzle().toUpperCase();
        List<Character> candidates = new ArrayList<>();
        for (char c : puzzleUpper.toCharArray()) {
            if (Character.isLetter(c) && !guessedConsonants.contains(c) && !guessedVowels.contains(c)
                    && !candidates.contains(c)) {
                candidates.add(c);
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        return candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
    }

    // ---- Round-scoped scoring (rounds 1-3, open-ended rounds 4+, and the final speed-up round) --

    private void addToRoundScore(String playerId, int amount) {
        roundScores.merge(playerId, amount, Integer::sum);
    }

    /** Folds every player's not-yet-banked round score into their permanent total, then clears the
     *  round tally - called at the start of every new "segment" (a fresh round, the Triple Toss-Up,
     *  the Final Spin, or the Bonus Round) so nothing is lost except via an actual BANKRUPT. */
    private void bankAndResetRoundScores() {
        for (SessionPlayer p : players) {
            int amount = roundScores.getOrDefault(p.getPlayerId(), 0);
            if (amount != 0) {
                p.addToScore(amount);
            }
        }
        roundScores.clear();
    }

    // ---- Rounds 1-3: normal spin-and-guess -----------------------------------------------------

    /** Sets the wheel turning. The outcome is decided now, but it only takes effect when
     *  {@link #settleSpin} fires as the wheel comes to rest. Until then the phase stays put, the
     *  result stays hidden, and nobody can act. */
    public LiveActionOutcome spin(String playerId) {
        requireCurrentPlayer(playerId);
        requirePhase(WofPhase.WAITING_FOR_TURN_ACTION);
        if (!puzzleHasHiddenConsonant()) {
            throw new InvalidActionException("Only vowels remain - buy a vowel or solve the puzzle.");
        }
        version++;
        spunThisTurn = true;
        wildCardLetterInPlay = false;
        return startSpin(currentPlayer().getDisplayName() + " spins the wheel...");
    }

    private LiveActionOutcome startSpin(String announce) {
        List<Wedge> wheel = currentWheel();
        WheelLayout layout = WheelLayout.of(wheel);
        int wedgeIndex = chooseWedgeIndex(wheel);
        int landingSlot = chooseLandingSlot(layout, wedgeIndex);
        Duration duration = chooseSpinDuration();
        lastSpinResult = null;
        // The outcome is captured now, wedge and all: the wheel can change under the pointer later
        // in the turn (a claimed special wedge comes off), but what this spin landed on can't.
        lastWheelSpin = new WheelSpin(++spinSequence, wheel.get(wedgeIndex), layout.startSlotOf(wedgeIndex),
                landingSlot, duration, Instant.now().plus(duration).plus(SPIN_REVEAL_GRACE));
        wheelInMotion = true;
        return LiveActionOutcome.of(toSnapshot(null), announce);
    }

    /** The wheel as it stands: this round's wedges plus whichever special wedges are on it. */
    private List<Wedge> currentWheel() {
        boolean specialsAllowed = !PHASES_WITHOUT_SPECIAL_WEDGES.contains(phase);
        int round = Math.max(roundNumber, 1);
        return Wedge.buildSyndicatedWheel(round, specialsAllowed && wildCardOnWheel,
                specialsAllowed && millionWedgeOnWheel && round <= MILLION_WEDGE_LAST_ROUND);
    }

    /** Invoked by a scheduled callback once the wheel has stopped on screen; no-op if state has
     *  since moved on. Applies the result of whichever spin (normal or Final Spin) is in motion. */
    public LiveActionOutcome settleSpin(long expectedVersion) {
        if (expectedVersion != version || !wheelInMotion) {
            return null;
        }
        version++;
        wheelInMotion = false;
        Wedge result = lastWheelSpin.wedge();
        lastSpinResult = result;
        lastSpinResultStartSlot = lastWheelSpin.wedgeStartSlot();
        return phase == WofPhase.FINAL_SPIN ? resolveFinalSpin(result) : resolveSpin(result);
    }

    private LiveActionOutcome resolveSpin(Wedge result) {
        String who = currentPlayer().getDisplayName();

        String announce;
        if (result.isBankrupt()) {
            roundScores.put(currentPlayer().getPlayerId(), 0);
            announce = who + " spun BANKRUPT and lost this round's winnings"
                    + forfeitSpecialWedges(currentPlayer().getPlayerId()) + "!";
            advanceTurn();
            if (shouldTriggerFinalSpin()) {
                return beginFinalSpin(announce);
            }
        } else if (result.isLoseTurn()) {
            announce = who + " spun LOSE A TURN.";
            advanceTurn();
            if (shouldTriggerFinalSpin()) {
                return beginFinalSpin(announce);
            }
        } else {
            phase = WofPhase.AWAITING_LETTER_GUESS;
            if (result.isWildCard()) {
                announce = who + " landed on the Wild Card - call a consonant worth $" + result.getValue()
                        + " to claim it!";
            } else if (result.isMillion()) {
                announce = who + " landed on the Million Dollar Wedge - call a consonant worth $"
                        + result.getValue() + " to pick it up!";
            } else {
                announce = who + " spun " + result.getLabel() + " - guess a consonant.";
            }
        }
        return LiveActionOutcome.of(toSnapshot(null), announce);
    }

    // ---- Special wedges -------------------------------------------------------------------------

    /** Puts any special wedge nobody holds back on the wheel for the round that's starting - the
     *  Million Dollar Wedge only through Round 3, and never a Wild Card that's been played. */
    private void returnSpecialWedgesToTheWheel() {
        wildCardOnWheel = !wildCardPlayed && wildCardHolderId == null;
        millionWedgeOnWheel = roundNumber <= MILLION_WEDGE_LAST_ROUND && millionWedgeHolderId == null;
    }

    /** Called on a correct consonant: landing on a special wedge that's still on the wheel claims it.
     *  Returns how to finish the announcement. */
    private String claimSpecialWedgeLandedOn(String playerId) {
        if (lastSpinResult == null) {
            return "";
        }
        if (lastSpinResult.isWildCard() && wildCardOnWheel) {
            wildCardOnWheel = false;
            wildCardHolderId = playerId;
            return " and claimed the Wild Card";
        }
        if (lastSpinResult.isMillion() && millionWedgeOnWheel) {
            millionWedgeOnWheel = false;
            millionWedgeHolderId = playerId;
            millionWedgeSecured = false;
            return " and picked up the Million Dollar Wedge - solve this puzzle to keep it";
        }
        return "";
    }

    /** Bankrupt takes any special wedge the player holds, banked or not. Returns how to finish the
     *  announcement. */
    private String forfeitSpecialWedges(String playerId) {
        List<String> lost = new ArrayList<>();
        if (playerId.equals(wildCardHolderId)) {
            wildCardHolderId = null;
            lost.add("the Wild Card");
        }
        if (playerId.equals(millionWedgeHolderId)) {
            millionWedgeHolderId = null;
            millionWedgeSecured = false;
            lost.add("the Million Dollar Wedge");
        }
        return lost.isEmpty() ? "" : ", along with " + String.join(" and ", lost);
    }

    /** The Million Dollar Wedge is only kept by solving the round it was picked up in. Called as
     *  every round ends; returns how to finish the announcement. */
    private String settleMillionWedgeAtRoundEnd(String solverPlayerIdOrNull) {
        if (millionWedgeHolderId == null || millionWedgeSecured) {
            return "";
        }
        String holderName = findPlayer(millionWedgeHolderId).getDisplayName();
        if (millionWedgeHolderId.equals(solverPlayerIdOrNull)) {
            millionWedgeSecured = true;
            return " " + holderName + " keeps the Million Dollar Wedge!";
        }
        millionWedgeHolderId = null;
        String comesBack = roundNumber < MILLION_WEDGE_LAST_ROUND ? " - it goes back on the wheel next round." : ".";
        return " " + holderName + " didn't solve it, so the Million Dollar Wedge is lost" + comesBack;
    }

    /** Whether {@code playerId} may play the Wild Card right now: it's their turn, they hold it, and
     *  their very last move was a correct consonant on a spin. Never in the Speed-Up. */
    private boolean canPlayWildCard(String playerId) {
        return phase == WofPhase.WAITING_FOR_TURN_ACTION
                && !wheelInMotion
                && wildCardWindowVersion == version
                && playerId.equals(wildCardHolderId)
                && playerId.equals(currentPlayer().getPlayerId())
                && lastSpinResult != null && lastSpinResult.getValue() != null
                && puzzleHasHiddenConsonant();
    }

    /** Plays the Wild Card: one more consonant at the value this spin landed on. It's used up
     *  whether or not the letter is there. */
    public LiveActionOutcome playWildCard(String playerId) {
        requireCurrentPlayer(playerId);
        requirePhase(WofPhase.WAITING_FOR_TURN_ACTION);
        if (!playerId.equals(wildCardHolderId)) {
            throw new InvalidActionException("You don't have the Wild Card.");
        }
        if (!puzzleHasHiddenConsonant()) {
            throw new InvalidActionException("Only vowels remain - there's no consonant left to call.");
        }
        if (!canPlayWildCard(playerId)) {
            throw new InvalidActionException(
                    "The Wild Card can only be played right after you call a correct consonant on a spin.");
        }
        version++;
        wildCardHolderId = null;
        wildCardPlayed = true;
        wildCardLetterInPlay = true;
        phase = WofPhase.AWAITING_LETTER_GUESS;
        return LiveActionOutcome.of(toSnapshot(null), currentPlayer().getDisplayName()
                + " plays the Wild Card - one more consonant at $" + lastSpinResult.getValue() + "!");
    }

    public LiveActionOutcome guessConsonant(String playerId, char letterRaw) {
        char letter = Character.toUpperCase(letterRaw);
        if (!Character.isLetter(letter) || VOWELS.indexOf(letter) >= 0) {
            throw new InvalidActionException("That is not a consonant.");
        }
        requireCurrentPlayer(playerId);
        requirePhase(WofPhase.AWAITING_LETTER_GUESS);
        if (guessedConsonants.contains(letter)) {
            throw new InvalidActionException("That letter has already been guessed.");
        }
        version++;
        guessedConsonants.add(letter);
        int value = lastSpinResult.getValue() == null ? 0 : lastSpinResult.getValue();
        boolean wildCardLetter = wildCardLetterInPlay;
        wildCardLetterInPlay = false;
        return resolveLetterGuess(letter, value, wildCardLetter ? LetterSource.WILD_CARD : LetterSource.SPIN);
    }

    /** Where a called letter came from - it decides what a hit can lead to. */
    private enum LetterSource {
        /** The consonant a spin earned: a hit can claim the special wedge landed on, and opens the
         *  window to play the Wild Card. */
        SPIN,
        /** The Wild Card's extra consonant. */
        WILD_CARD,
        /** A bought vowel. */
        VOWEL
    }

    public LiveActionOutcome buyVowel(String playerId, char vowelRaw) {
        char vowel = Character.toUpperCase(vowelRaw);
        if (VOWELS.indexOf(vowel) < 0) {
            throw new InvalidActionException("That is not a vowel.");
        }
        requireCurrentPlayer(playerId);
        requirePhase(WofPhase.WAITING_FOR_TURN_ACTION);
        if (guessedVowels.contains(vowel)) {
            throw new InvalidActionException("That vowel has already been bought.");
        }
        if (!puzzleHasHiddenVowel()) {
            throw new InvalidActionException("There are no more vowels in the puzzle.");
        }
        if (totalFundsOf(playerId) < VOWEL_COST) {
            throw new InvalidActionException("Not enough money to buy a vowel.");
        }
        version++;
        // Buying a vowel spends against your full effective funds, including this round's
        // not-yet-banked winnings - which means it also locks those winnings in (safe from a later
        // BANKRUPT this same round), same as spending real money would.
        bankRoundScoreFor(playerId);
        currentPlayer().addToScore(-VOWEL_COST);
        guessedVowels.add(vowel);
        return resolveLetterGuess(vowel, 0, LetterSource.VOWEL);
    }

    private int totalFundsOf(String playerId) {
        return findPlayer(playerId).getScore() + roundScores.getOrDefault(playerId, 0);
    }

    private void bankRoundScoreFor(String playerId) {
        int amount = roundScores.getOrDefault(playerId, 0);
        if (amount != 0) {
            findPlayer(playerId).addToScore(amount);
        }
        roundScores.remove(playerId);
    }

    /** Declares this player is attempting to solve, opening the 30s letter-box window. Doesn't end
     *  their turn by itself - a wrong or timed-out attempt does that (see resolveSolveAttempt). */
    public LiveActionOutcome beginSolve(String playerId) {
        requireCurrentPlayer(playerId);
        requirePhaseAny(WofPhase.WAITING_FOR_TURN_ACTION, WofPhase.AWAITING_LETTER_GUESS);
        version++;
        wildCardLetterInPlay = false;
        solvingPlayerId = playerId;
        solveStartedAt = Instant.now();
        solveDeadlineAt = solveStartedAt.plus(PLAYER_SOLVE_WINDOW);
        phase = WofPhase.AWAITING_SOLVE;
        return LiveActionOutcome.of(toSnapshot(null),
                currentPlayer().getDisplayName() + " is attempting to solve! 30 seconds...");
    }

    public LiveActionOutcome attemptSolve(String playerId, String guess) {
        if (!playerId.equals(solvingPlayerId)) {
            throw new InvalidActionException("It isn't your turn to solve.");
        }
        requirePhase(WofPhase.AWAITING_SOLVE);
        version++;
        return resolveSolveAttempt(playerId, guess);
    }

    /** Invoked by a scheduled callback 30s after a solve declaration; no-op if state has since moved
     *  on. */
    public LiveActionOutcome handleSolveTimeout(long expectedVersion) {
        if (expectedVersion != version || phase != WofPhase.AWAITING_SOLVE) {
            return null;
        }
        version++;
        return resolveSolveAttempt(solvingPlayerId, null);
    }

    private LiveActionOutcome resolveSolveAttempt(String playerId, String guess) {
        solvingPlayerId = null;
        solveStartedAt = null;
        solveDeadlineAt = null;
        if (guess != null && normalize(guess).equals(normalize(currentPuzzle.getPuzzle()))) {
            return endRound(playerId);
        }
        String announce = findPlayer(playerId).getDisplayName()
                + (guess == null ? " ran out of time." : "'s solve attempt was incorrect.");
        advanceTurn();
        if (shouldTriggerFinalSpin()) {
            return beginFinalSpin(announce);
        }
        phase = WofPhase.WAITING_FOR_TURN_ACTION;
        return LiveActionOutcome.of(toSnapshot(null), announce);
    }

    /** Any connected player may click Continue once a round's outcome is on screen to start the
     *  next one - first click wins, matching the buzz-in pattern used by Frenzy's own Continue
     *  (see {@link #continueFrenzy(String)}); a second click after that simply finds the phase has
     *  already moved on and no-ops via requirePhase. */
    public LiveActionOutcome continueToNextRound(String playerId) {
        requirePhase(WofPhase.ROUND_OVER);
        findPlayer(playerId);
        version++;
        roundNumber++;
        roundWinnerPlayerId = null;
        advanceTurn();

        if (!tripleTossUpDone && roundNumber >= TRIPLE_TOSS_UP_AT_ROUND) {
            return beginTripleTossUp();
        }
        if (shouldTriggerFinalSpin()) {
            return beginFinalSpin("");
        }
        return beginNextOpenRound();
    }

    /** Extracted so tests can force a specific wedge instead of fighting real randomness. Picks a
     *  uniformly random rim slot and returns the wedge under it, so a wedge's odds are proportional
     *  to how many slots it spans (equal today, since every wedge spans three). */
    protected int chooseWedgeIndex(List<Wedge> wheel) {
        WheelLayout layout = WheelLayout.of(wheel);
        return layout.wedgeIndexAtSlot(ThreadLocalRandom.current().nextInt(WheelLayout.TOTAL_SLOTS));
    }

    /** Which of the chosen wedge's slots stops under the pointer - uniform across them, so together
     *  with {@link #chooseWedgeIndex} every rim slot is equally likely. */
    protected int chooseLandingSlot(WheelLayout layout, int wedgeIndex) {
        return layout.startSlotOf(wedgeIndex) + ThreadLocalRandom.current().nextInt(layout.slotCountOf(wedgeIndex));
    }

    protected Duration chooseSpinDuration() {
        long min = SPIN_DURATION_MIN.toMillis();
        long max = SPIN_DURATION_MAX.toMillis();
        return Duration.ofMillis(ThreadLocalRandom.current().nextLong(min, max + 1));
    }

    private LiveActionOutcome resolveLetterGuess(char letter, int valuePerOccurrence, LetterSource source) {
        int occurrences = countOccurrences(currentPuzzle.getPuzzle(), letter);
        String playerId = currentPlayer().getPlayerId();
        String who = currentPlayer().getDisplayName();
        String announce;
        if (occurrences > 0) {
            int winnings = occurrences * valuePerOccurrence;
            if (winnings > 0) {
                addToRoundScore(playerId, winnings);
            }
            String claimed = source == LetterSource.SPIN ? claimSpecialWedgeLandedOn(playerId) : "";
            announce = who + " found " + occurrences + " " + letter + (occurrences > 1 ? "'s" : "")
                    + (winnings > 0 ? " for $" + winnings : "") + claimed + "!";
            if (isPuzzleFullyRevealed()) {
                LiveActionOutcome ended = endRound(playerId);
                // Don't let the round's own announcement swallow a wedge claimed on its last letter.
                return claimed.isEmpty() ? ended
                        : LiveActionOutcome.of(ended.snapshot(), announce + " " + ended.announceText());
            }
            if (source == LetterSource.SPIN) {
                // Playable only as the very next move - see canPlayWildCard.
                wildCardWindowVersion = version;
            }
        } else {
            announce = "No " + letter + " in the puzzle.";
            advanceTurn();
        }
        // Every consonant has now been called but the board still has hidden vowels: nobody can
        // legally act (a spin only ever leads to another consonant call), so the round is over.
        if (allConsonantsCalled() && !isPuzzleFullyRevealed()) {
            return endRound(null);
        }
        if (shouldTriggerFinalSpin()) {
            return beginFinalSpin(announce);
        }
        phase = WofPhase.WAITING_FOR_TURN_ACTION;
        return LiveActionOutcome.of(toSnapshot(null), announce);
    }

    private boolean allConsonantsCalled() {
        for (char c = 'A'; c <= 'Z'; c++) {
            if (VOWELS.indexOf(c) < 0 && !guessedConsonants.contains(c)) {
                return false;
            }
        }
        return true;
    }

    private LiveActionOutcome endRound(String solverPlayerIdOrNull) {
        roundWinnerPlayerId = solverPlayerIdOrNull;
        wildCardLetterInPlay = false;
        // A correct solve attempt only checks the whole guessed phrase - it doesn't necessarily
        // mean every letter was individually called, so reveal the rest now that the round's over
        // and everyone's about to sit and look at this board until someone clicks Continue.
        revealEntireCurrentPuzzle();
        String solvedText = "\"" + currentPuzzle.getPuzzle().strip() + "\"";
        String announce = solverPlayerIdOrNull != null
                ? findPlayer(solverPlayerIdOrNull).getDisplayName() + " solved the puzzle! " + solvedText
                : "The puzzle is fully revealed: " + solvedText;
        announce += settleMillionWedgeAtRoundEnd(solverPlayerIdOrNull);
        phase = WofPhase.ROUND_OVER;
        return LiveActionOutcome.of(toSnapshot(null), announce);
    }

    // ---- Triple Toss-Up (before round 4) --------------------------------------------------------

    private LiveActionOutcome beginTripleTossUp() {
        bankAndResetRoundScores();
        tripleTossUpDone = true;
        tripleTossUpPuzzles = pickTripleTossUpPuzzles();
        tripleTossUpIndex = 0;
        tripleTossUpSolverIds.clear();
        currentPuzzle = tripleTossUpPuzzles.get(0);
        guessedConsonants.clear();
        guessedVowels.clear();
        puzzleLockedOutPlayerIds.clear();
        phase = WofPhase.TRIPLE_TOSS_UP;
        return LiveActionOutcome.of(toSnapshot(null),
                "Triple Toss-Up! Category: " + tripleTossUpCategory + ". Ring in when you know it!");
    }

    /** Invoked by a scheduled callback every ~2.5s while a Triple Toss-Up puzzle is active; no-op
     *  if state has since moved on. Also a no-op while a player is answering, same as Frenzy's
     *  reveal: the board holds still under their letter boxes, and no reveal can fill in the last
     *  letter and end the puzzle before they've submitted. */
    public LiveActionOutcome revealNextTripleTossUpLetter(long expectedVersion) {
        if (expectedVersion != version || phase != WofPhase.TRIPLE_TOSS_UP || tripleTossUpSolvingPlayerId != null) {
            return null;
        }
        version++;
        Character next = pickUnrevealedTossUpLetter();
        if (next == null) {
            return advanceTripleTossUpPuzzle(null, "Nobody solved it before the puzzle was fully revealed.");
        }
        if (VOWELS.indexOf(next) >= 0) {
            guessedVowels.add(next);
        } else {
            guessedConsonants.add(next);
        }
        return LiveActionOutcome.of(toSnapshot(null), "Another letter is revealed...");
    }

    /** Declares this player is attempting to answer - buzz-in style, same rule as Frenzy's. */
    public LiveActionOutcome beginTripleTossUpSolve(String playerId) {
        requirePhase(WofPhase.TRIPLE_TOSS_UP);
        if (puzzleLockedOutPlayerIds.contains(playerId)) {
            throw new InvalidActionException("You've already used your attempt on this puzzle.");
        }
        if (tripleTossUpSolvingPlayerId != null) {
            throw new InvalidActionException("Someone else is already answering.");
        }
        version++;
        tripleTossUpSolvingPlayerId = playerId;
        tripleTossUpSolveStartedAt = Instant.now();
        tripleTossUpSolveDeadlineAt = tripleTossUpSolveStartedAt.plus(PLAYER_SOLVE_WINDOW);
        return LiveActionOutcome.of(toSnapshot(null), findPlayer(playerId).getDisplayName() + " is answering!");
    }

    public LiveActionOutcome attemptTripleTossUpAnswer(String playerId, String guess) {
        requirePhase(WofPhase.TRIPLE_TOSS_UP);
        if (!playerId.equals(tripleTossUpSolvingPlayerId)) {
            throw new InvalidActionException("It isn't your turn to answer.");
        }
        version++;
        return resolveTripleTossUpAnswer(playerId, guess);
    }

    /** Invoked by a scheduled callback 30s after a player buzzes in; no-op if state has since moved
     *  on. */
    public LiveActionOutcome handleTripleTossUpSolveTimeout(long expectedVersion) {
        if (expectedVersion != version || phase != WofPhase.TRIPLE_TOSS_UP || tripleTossUpSolvingPlayerId == null) {
            return null;
        }
        version++;
        return resolveTripleTossUpAnswer(tripleTossUpSolvingPlayerId, null);
    }

    private LiveActionOutcome resolveTripleTossUpAnswer(String playerId, String guess) {
        tripleTossUpSolvingPlayerId = null;
        tripleTossUpSolveStartedAt = null;
        tripleTossUpSolveDeadlineAt = null;
        SessionPlayer player = findPlayer(playerId);
        if (guess != null && normalize(guess).equals(normalize(currentPuzzle.getPuzzle()))) {
            player.addToScore(TRIPLE_TOSS_UP_VALUE);
            return advanceTripleTossUpPuzzle(playerId,
                    player.getDisplayName() + " solved it for $" + TRIPLE_TOSS_UP_VALUE + "!");
        }
        puzzleLockedOutPlayerIds.add(playerId);
        String announce = player.getDisplayName() + (guess == null ? " ran out of time." : "'s guess was incorrect.");
        if (puzzleLockedOutPlayerIds.size() >= players.size()) {
            return advanceTripleTossUpPuzzle(null, announce + " Nobody solved it.");
        }
        return LiveActionOutcome.of(toSnapshot(null), announce);
    }

    /** Records this puzzle's result, then either moves to the next of the three puzzles or, after
     *  the third, resolves the same-contestant sweep bonus and begins Round 4. */
    private LiveActionOutcome advanceTripleTossUpPuzzle(String solverPlayerIdOrNull, String announcePrefix) {
        // Defensive: every caller reaches here with the buzzer already clear (reveals pause while
        // someone is answering), but each puzzle starts clean.
        tripleTossUpSolvingPlayerId = null;
        tripleTossUpSolveStartedAt = null;
        tripleTossUpSolveDeadlineAt = null;
        tripleTossUpSolverIds.add(solverPlayerIdOrNull);
        if (tripleTossUpIndex < TRIPLE_TOSS_UP_PUZZLES - 1) {
            tripleTossUpIndex++;
            currentPuzzle = tripleTossUpPuzzles.get(tripleTossUpIndex);
            guessedConsonants.clear();
            guessedVowels.clear();
            puzzleLockedOutPlayerIds.clear();
            return LiveActionOutcome.of(toSnapshot(null), announcePrefix + " Next puzzle!");
        }

        // Third (and last) puzzle just resolved: its solver (or, if nobody solved it, the
        // contestant closest to the host - approximated here as the first player in seating
        // order, same fallback used elsewhere when a toss-up goes unsolved) starts Round 4.
        String round4Starter = solverPlayerIdOrNull != null ? solverPlayerIdOrNull : players.get(0).getPlayerId();
        String bonusAnnounce = "";
        if (solvedAllThreeBySamePlayer()) {
            SessionPlayer sweeper = findPlayer(tripleTossUpSolverIds.get(0));
            sweeper.addToScore(TRIPLE_TOSS_UP_SWEEP_BONUS);
            bonusAnnounce = " " + sweeper.getDisplayName() + " swept all three toss-ups for a $"
                    + TRIPLE_TOSS_UP_SWEEP_BONUS + " bonus!";
        }

        puzzleLockedOutPlayerIds.clear();
        startTurnOf(indexOfPlayer(round4Starter));
        LiveActionOutcome begin = shouldTriggerFinalSpin() ? beginFinalSpin("") : beginNextOpenRound();
        return LiveActionOutcome.of(begin.snapshot(), announcePrefix + bonusAnnounce + " " + begin.announceText());
    }

    private boolean solvedAllThreeBySamePlayer() {
        if (tripleTossUpSolverIds.size() != TRIPLE_TOSS_UP_PUZZLES) {
            return false;
        }
        String first = tripleTossUpSolverIds.get(0);
        return first != null && tripleTossUpSolverIds.stream().allMatch(first::equals);
    }

    /** Three puzzles that share one category ("a common theme"), preferring unused puzzles; falls
     *  back to three independently-picked puzzles (themed around the first one's category) only if
     *  no category has three unused puzzles left. */
    private List<Puzzle> pickTripleTossUpPuzzles() {
        List<String> categories = new ArrayList<>(wofService.getAllCategories());
        Collections.shuffle(categories);
        for (String category : categories) {
            List<Puzzle> unused = wofService.getPuzzlesByCategory(category).stream()
                    .filter(p -> !usedPuzzleIds.contains(p.getId())).toList();
            if (unused.size() >= TRIPLE_TOSS_UP_PUZZLES) {
                List<Puzzle> shuffled = new ArrayList<>(unused);
                Collections.shuffle(shuffled);
                List<Puzzle> chosen = shuffled.subList(0, TRIPLE_TOSS_UP_PUZZLES);
                chosen.forEach(p -> usedPuzzleIds.add(p.getId()));
                tripleTossUpCategory = category;
                return chosen;
            }
        }
        List<Puzzle> fallback = new ArrayList<>();
        for (int i = 0; i < TRIPLE_TOSS_UP_PUZZLES; i++) {
            fallback.add(pickPuzzle());
        }
        tripleTossUpCategory = fallback.get(0).getCategory();
        return fallback;
    }

    // ---- Round 4+ (open-ended) and the Final Spin -----------------------------------------------

    private boolean timeIsUp() {
        return !Instant.now().isBefore(gameClockEndsAt);
    }

    /** Only rounds 4 and later are subject to the clock - rounds 1-3 and the Triple Toss-Up always
     *  play out in full regardless of how long they take. */
    private boolean shouldTriggerFinalSpin() {
        return tripleTossUpDone && timeIsUp();
    }

    /** True once the game clock is even eligible to cut a round short (see shouldTriggerFinalSpin) -
     *  used to decide whether a proactive {@link #handleGameClockExpiry} callback needs scheduling
     *  at all, independent of whether it has actually fired yet. */
    private boolean clockCuttingActive() {
        return tripleTossUpDone;
    }

    /** Invoked by a scheduled callback at the exact moment the game clock runs out; no-op if state
     *  has since moved on. This exists because the ordinary in-line checks in spin()/
     *  resolveLetterGuess()/resolveSolveAttempt() only fire reactively, the next time SOMEONE acts -
     *  which is fine for an AI (it acts every second or so regardless) but leaves a real gap for a
     *  human player who's just sitting on a decision when the clock hits zero. This callback forces
     *  the cut immediately regardless of whose turn it is or what they're doing. */
    public LiveActionOutcome handleGameClockExpiry(long expectedVersion) {
        // A spin in motion finishes first; the cut is rescheduled once it settles (pendingAiMoves).
        if (expectedVersion != version || !shouldTriggerFinalSpin() || wheelInMotion) {
            return null;
        }
        if (phase != WofPhase.WAITING_FOR_TURN_ACTION && phase != WofPhase.AWAITING_LETTER_GUESS
                && phase != WofPhase.AWAITING_SOLVE) {
            return null;
        }
        version++;
        solvingPlayerId = null;
        solveStartedAt = null;
        solveDeadlineAt = null;
        return beginFinalSpin("");
    }

    /** Starts a fresh normal round (round 4 or later - open-ended) with a new puzzle. */
    private LiveActionOutcome beginNextOpenRound() {
        bankAndResetRoundScores();
        currentPuzzle = pickPuzzle();
        guessedConsonants.clear();
        guessedVowels.clear();
        lastSpinResult = null;
        returnSpecialWedgesToTheWheel();
        phase = WofPhase.WAITING_FOR_TURN_ACTION;
        return LiveActionOutcome.of(toSnapshot(null), "Round " + roundNumber + " begins!");
    }

    /** Abandons whatever puzzle was up (if any - a round can be cut short mid-play), picks a fresh
     *  one for the last puzzle of the game, and opens the Final Spin. Whoever was already "up" spins
     *  first - re-spinning on Bankrupt/Lose a Turn until a dollar value locks in the per-letter
     *  value for the speed-up finish. */
    private LiveActionOutcome beginFinalSpin(String announcePrefix) {
        // A round cut short by the clock ends unsolved (Round 4+, so no unbanked Million wedge is
        // possible today - settled anyway so that can never change silently).
        String millionWedge = settleMillionWedgeAtRoundEnd(null);
        bankAndResetRoundScores();
        currentPuzzle = pickPuzzle();
        guessedConsonants.clear();
        guessedVowels.clear();
        lastSpinResult = null;
        wildCardLetterInPlay = false;
        phase = WofPhase.FINAL_SPIN;
        String lead = (announcePrefix + millionWedge).strip();
        String prefix = lead.isEmpty() ? "" : lead + " ";
        return LiveActionOutcome.of(toSnapshot(null),
                prefix + "Time's up! " + currentPlayer().getDisplayName() + ", spin for the final round!");
    }

    public LiveActionOutcome finalSpin(String playerId) {
        requireCurrentPlayer(playerId);
        requirePhase(WofPhase.FINAL_SPIN);
        version++;
        return startSpin(currentPlayer().getDisplayName() + " spins for the final round...");
    }

    private LiveActionOutcome resolveFinalSpin(Wedge result) {
        String who = currentPlayer().getDisplayName();

        if (result.isBankrupt() || result.isLoseTurn()) {
            advanceTurn();
            String label = result.isBankrupt() ? "BANKRUPT" : "LOSE A TURN";
            return LiveActionOutcome.of(toSnapshot(null),
                    who + " spun " + label + " - " + currentPlayer().getDisplayName() + ", spin again!");
        }

        int baseValue = result.getValue() == null ? 500 : result.getValue();
        speedupLetterValue = baseValue + SPEEDUP_VALUE_BONUS;
        speedupSolvingPlayerId = null;
        phase = WofPhase.SPEEDUP_AWAITING_LETTER;
        return LiveActionOutcome.of(toSnapshot(null),
                who + " spun " + result.getLabel() + "! Consonants are worth $" + speedupLetterValue
                        + " and vowels are free. " + who + ", call a letter!");
    }

    public LiveActionOutcome speedupGuessLetter(String playerId, char letterRaw) {
        char letter = Character.toUpperCase(letterRaw);
        if (!Character.isLetter(letter)) {
            throw new InvalidActionException("That is not a letter.");
        }
        requireCurrentPlayer(playerId);
        requirePhase(WofPhase.SPEEDUP_AWAITING_LETTER);
        boolean isVowel = VOWELS.indexOf(letter) >= 0;
        if (isVowel ? guessedVowels.contains(letter) : guessedConsonants.contains(letter)) {
            throw new InvalidActionException("That letter has already been called.");
        }
        version++;
        if (isVowel) {
            guessedVowels.add(letter);
        } else {
            guessedConsonants.add(letter);
        }
        String who = currentPlayer().getDisplayName();
        int occurrences = countOccurrences(currentPuzzle.getPuzzle(), letter);
        if (occurrences > 0) {
            if (!isVowel) {
                addToRoundScore(currentPlayer().getPlayerId(), occurrences * speedupLetterValue);
            }
            if (isPuzzleFullyRevealed()) {
                return endSpeedupRound(currentPlayer().getPlayerId());
            }
            speedupSolvingPlayerId = playerId;
            phase = WofPhase.SPEEDUP_AWAITING_SOLVE;
            return LiveActionOutcome.of(toSnapshot(null), who + " found " + letter + "! 3 seconds to solve!");
        }
        advanceTurn();
        return LiveActionOutcome.of(toSnapshot(null), "No " + letter + " in the puzzle.");
    }

    public LiveActionOutcome speedupAttemptSolve(String playerId, String guess) {
        if (!playerId.equals(speedupSolvingPlayerId)) {
            throw new InvalidActionException("It isn't your turn to solve.");
        }
        requirePhase(WofPhase.SPEEDUP_AWAITING_SOLVE);
        version++;
        return resolveSpeedupSolveAttempt(playerId, guess);
    }

    /** Invoked by a scheduled callback 3s after a letter hit; no-op if state has since moved on. */
    public LiveActionOutcome handleSpeedupSolveTimeout(long expectedVersion) {
        if (expectedVersion != version || phase != WofPhase.SPEEDUP_AWAITING_SOLVE) {
            return null;
        }
        version++;
        return resolveSpeedupSolveAttempt(speedupSolvingPlayerId, null);
    }

    private LiveActionOutcome resolveSpeedupSolveAttempt(String playerId, String guess) {
        if (guess != null && normalize(guess).equals(normalize(currentPuzzle.getPuzzle()))) {
            return endSpeedupRound(playerId);
        }
        String announce = findPlayer(playerId).getDisplayName()
                + (guess == null ? " ran out of time." : "'s solve attempt was incorrect.");
        speedupSolvingPlayerId = null;
        phase = WofPhase.SPEEDUP_AWAITING_LETTER;
        advanceTurn();
        return LiveActionOutcome.of(toSnapshot(null), announce);
    }

    private LiveActionOutcome endSpeedupRound(String solverPlayerIdOrNull) {
        roundWinnerPlayerId = solverPlayerIdOrNull;
        String solvedText = "\"" + currentPuzzle.getPuzzle() + "\"";
        String announce = solverPlayerIdOrNull != null
                ? findPlayer(solverPlayerIdOrNull).getDisplayName() + " solved the puzzle! " + solvedText
                : "The puzzle is fully revealed: " + solvedText;
        LiveActionOutcome bonus = beginBonusRound();
        return LiveActionOutcome.of(bonus.snapshot(), announce + " " + bonus.announceText());
    }

    // ---- Bonus round ----------------------------------------------------------------------------

    private LiveActionOutcome beginBonusRound() {
        bankAndResetRoundScores();
        gameWinnerPlayerIds = computeWinners();
        bonusWinnerPlayerId = gameWinnerPlayerIds.get(ThreadLocalRandom.current().nextInt(gameWinnerPlayerIds.size()));
        bonusCategoryOptions = pickBonusCategoryOptions();
        bonusPuzzle = null;
        bonusRevealedLetters.clear();
        for (char c : RSTLNE.toCharArray()) {
            bonusRevealedLetters.add(c);
        }
        boolean bringsWildCard = bonusWinnerPlayerId.equals(wildCardHolderId);
        bonusConsonantCount = BONUS_CONSONANTS_TO_PICK + (bringsWildCard ? 1 : 0);
        bonusMillionInPlay = bonusWinnerPlayerId.equals(millionWedgeHolderId) && millionWedgeSecured;
        bonusPrizeAmount = chooseBonusEnvelope(bonusEnvelopes(bonusMillionInPlay));
        bonusSolved = null;
        phase = WofPhase.BONUS_PICK_CATEGORY;
        String extras = (bringsWildCard ? " The Wild Card adds a fourth consonant." : "")
                + (bonusMillionInPlay ? " The $1,000,000 envelope is on the Bonus Wheel!" : "");
        return LiveActionOutcome.of(toSnapshot(null),
                findPlayer(bonusWinnerPlayerId).getDisplayName()
                        + " won the game and heads to the Bonus Round!" + extras + " Pick a category.");
    }

    /** The Bonus Wheel's envelopes - with the $100,000 one swapped for $1,000,000 when the bonus
     *  contestant brought a banked Million Dollar Wedge. */
    static int[] bonusEnvelopes(boolean millionInPlay) {
        int[] envelopes = BONUS_PRIZES.clone();
        if (millionInPlay) {
            for (int i = 0; i < envelopes.length; i++) {
                if (envelopes[i] == BONUS_TOP_PRIZE) {
                    envelopes[i] = MILLION_DOLLAR_PRIZE;
                }
            }
        }
        return envelopes;
    }

    /** Extracted so tests can pick a specific envelope, same idea as {@link #chooseWedgeIndex}. */
    protected int chooseBonusEnvelope(int[] envelopes) {
        return envelopes[ThreadLocalRandom.current().nextInt(envelopes.length)];
    }

    public LiveActionOutcome pickBonusCategory(String playerId, String category) {
        requireBonusWinner(playerId);
        requirePhase(WofPhase.BONUS_PICK_CATEGORY);
        if (bonusCategoryOptions == null || !bonusCategoryOptions.contains(category)) {
            throw new InvalidActionException("That category isn't one of the offered options.");
        }
        version++;
        List<Puzzle> categoryPuzzles = wofService.getPuzzlesByCategory(category);
        List<Puzzle> unused = categoryPuzzles.stream().filter(p -> !usedPuzzleIds.contains(p.getId())).toList();
        List<Puzzle> pool = !unused.isEmpty() ? unused : (!categoryPuzzles.isEmpty() ? categoryPuzzles : wofService.getAllPuzzles());
        bonusPuzzle = pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
        usedPuzzleIds.add(bonusPuzzle.getId());
        phase = WofPhase.BONUS_PICK_LETTERS;
        return LiveActionOutcome.of(toSnapshot(null), findPlayer(playerId).getDisplayName() + " chose " + category
                + ". Now pick " + bonusConsonantCount + " consonants and 1 vowel.");
    }

    public LiveActionOutcome submitBonusLetters(String playerId, List<Character> consonantsRaw, char vowelRaw) {
        requireBonusWinner(playerId);
        requirePhase(WofPhase.BONUS_PICK_LETTERS);
        if (consonantsRaw == null || consonantsRaw.size() != bonusConsonantCount) {
            throw new InvalidActionException("Pick exactly " + bonusConsonantCount + " consonants.");
        }
        Set<Character> consonants = new LinkedHashSet<>();
        for (char raw : consonantsRaw) {
            char c = Character.toUpperCase(raw);
            if (!Character.isLetter(c) || VOWELS.indexOf(c) >= 0) {
                throw new InvalidActionException("\"" + c + "\" is not a consonant.");
            }
            if (bonusRevealedLetters.contains(c) || !consonants.add(c)) {
                throw new InvalidActionException("Consonants must be different from each other and from R, S, T, L, N, E.");
            }
        }
        char vowel = Character.toUpperCase(vowelRaw);
        if (VOWELS.indexOf(vowel) < 0) {
            throw new InvalidActionException("That is not a vowel.");
        }
        if (bonusRevealedLetters.contains(vowel)) {
            throw new InvalidActionException("That vowel is already revealed.");
        }
        version++;
        bonusRevealedLetters.addAll(consonants);
        bonusRevealedLetters.add(vowel);
        if (bonusConsonantCount > BONUS_CONSONANTS_TO_PICK) {
            // The fourth consonant was the Wild Card - it's spent now.
            wildCardHolderId = null;
            wildCardPlayed = true;
        }
        phase = WofPhase.BONUS_SOLVE;
        return LiveActionOutcome.of(toSnapshot(null), "10 seconds to solve the bonus puzzle!");
    }

    public LiveActionOutcome attemptBonusSolve(String playerId, String guess) {
        requireBonusWinner(playerId);
        requirePhase(WofPhase.BONUS_SOLVE);
        version++;
        return resolveBonusSolve(guess);
    }

    /** Invoked by a scheduled callback 10s after letters are revealed; no-op if state has moved on. */
    public LiveActionOutcome handleBonusSolveTimeout(long expectedVersion) {
        if (expectedVersion != version || phase != WofPhase.BONUS_SOLVE) {
            return null;
        }
        version++;
        return resolveBonusSolve(null);
    }

    private LiveActionOutcome resolveBonusSolve(String guess) {
        boolean correct = guess != null && normalize(guess).equals(normalize(bonusPuzzle.getPuzzle()));
        bonusSolved = correct;
        // The real show always reveals the full phrase at the end, win or lose.
        for (char c : bonusPuzzle.getPuzzle().toUpperCase().toCharArray()) {
            if (Character.isLetter(c)) {
                bonusRevealedLetters.add(c);
            }
        }
        SessionPlayer winner = findPlayer(bonusWinnerPlayerId);
        String announce;
        if (correct && bonusPrizeAmount == MILLION_DOLLAR_PRIZE) {
            winner.addToScore(bonusPrizeAmount);
            announce = winner.getDisplayName() + " solved the bonus puzzle and wins ONE MILLION DOLLARS!";
        } else if (correct) {
            winner.addToScore(bonusPrizeAmount);
            announce = winner.getDisplayName() + " solved the bonus puzzle and wins $" + bonusPrizeAmount + "!";
        } else {
            announce = winner.getDisplayName() + " didn't solve it in time. The prize was $" + bonusPrizeAmount
                    + ". The answer was \"" + bonusPuzzle.getPuzzle() + "\".";
        }
        phase = WofPhase.GAME_OVER;
        gameWinnerPlayerIds = computeWinners();
        return LiveActionOutcome.of(toSnapshot(null), announce);
    }

    private void requireBonusWinner(String playerId) {
        if (!playerId.equals(bonusWinnerPlayerId)) {
            throw new InvalidActionException("Only the bonus round contestant may do that.");
        }
    }

    /**
     * The categories the bonus contestant chooses between - only ones that still have a puzzle
     * nobody has seen this game, so whichever they pick is actually what they play. Should fewer
     * than {@value #BONUS_CATEGORY_CHOICES} have one, the rest are made up from categories that
     * don't: a puzzle that comes round again beats offering fewer choices.
     */
    private List<String> pickBonusCategoryOptions() {
        Map<String, List<Puzzle>> byCategory = wofService.getAllPuzzles().stream()
                .collect(Collectors.groupingBy(Puzzle::getCategory));
        List<String> fresh = new ArrayList<>();
        List<String> spent = new ArrayList<>();
        byCategory.forEach((category, puzzles) -> {
            boolean hasUnused = puzzles.stream().anyMatch(p -> !usedPuzzleIds.contains(p.getId()));
            (hasUnused ? fresh : spent).add(category);
        });
        Collections.shuffle(fresh);
        Collections.shuffle(spent);
        return Stream.concat(fresh.stream(), spent.stream()).limit(BONUS_CATEGORY_CHOICES).toList();
    }

    // ---- AI opponents (highest difficulty: perfect knowledge of the puzzle, plays every phase) ----

    @Override
    public List<AiMove> pendingAiMoves() {
        if (phase == WofPhase.GAME_OVER) {
            return List.of();
        }
        if (phase == WofPhase.ROUND_OVER) {
            return pendingRoundOverContinueMoves();
        }
        if (phase == WofPhase.FRENZY_PUZZLE_OVER) {
            if (!frenzyTimeUp) {
                // Mid-Frenzy the next puzzle comes up on its own - timed from when this one ended,
                // not from whenever this happens to be asked.
                long expectedVersion = version;
                return List.of(new AiMove(clampNonNegative(Duration.between(Instant.now(), frenzyNextPuzzleAt)),
                        session -> ((WofEngine) session.getEngine()).advanceFrenzyAfterPause(expectedVersion)));
            }
            return pendingFrenzyContinueMoves();
        }
        if (phase == WofPhase.FORTUNE_FRENZY) {
            return pendingFrenzyMoves();
        }
        if (phase == WofPhase.TRIPLE_TOSS_UP) {
            return pendingTripleTossUpMoves();
        }
        if (phase == WofPhase.SPEEDUP_AWAITING_SOLVE) {
            return pendingSpeedupSolveMoves();
        }
        if (phase == WofPhase.BONUS_PICK_CATEGORY) {
            return pendingBonusCategoryMoves();
        }
        if (phase == WofPhase.BONUS_PICK_LETTERS) {
            return pendingBonusLettersMoves();
        }
        if (phase == WofPhase.BONUS_SOLVE) {
            return pendingBonusSolveMoves();
        }
        if (phase == WofPhase.AWAITING_SOLVE) {
            return pendingSolveMoves();
        }
        if (wheelInMotion) {
            // The only thing that can happen mid-spin is the wheel settling. The game-clock cut and
            // the next AI decision get scheduled after that, once this is recomputed.
            long expectedVersion = version;
            return List.of(new AiMove(clampNonNegative(Duration.between(Instant.now(), lastWheelSpin.settlesAt())),
                    session -> ((WofEngine) session.getEngine()).settleSpin(expectedVersion)));
        }

        // Scheduled regardless of whether the current player is AI - a human just sitting on a
        // decision when the clock hits zero still needs to be cut off immediately, not only when
        // they next happen to act.
        List<AiMove> moves = new ArrayList<>();
        boolean cuttable = clockCuttingActive()
                && (phase == WofPhase.WAITING_FOR_TURN_ACTION || phase == WofPhase.AWAITING_LETTER_GUESS);
        if (cuttable) {
            long expectedVersion = version;
            moves.add(new AiMove(clampNonNegative(Duration.between(Instant.now(), gameClockEndsAt)),
                    session -> ((WofEngine) session.getEngine()).handleGameClockExpiry(expectedVersion)));
        }

        SessionPlayer player = currentPlayer();
        if (!player.isAi()) {
            return moves;
        }
        String playerId = player.getPlayerId();

        if (phase == WofPhase.FINAL_SPIN) {
            moves.add(new AiMove(humanDelay(AI_THINK_DELAY_MIN, AI_THINK_DELAY_MAX), session -> ((WofEngine) session.getEngine()).finalSpin(playerId)));
            return moves;
        }

        if (phase == WofPhase.AWAITING_LETTER_GUESS) {
            Character letter = chooseAiConsonant();
            if (letter != null) {
                char l = letter;
                moves.add(new AiMove(humanDelay(AI_THINK_DELAY_MIN, AI_THINK_DELAY_MAX),
                        session -> ((WofEngine) session.getEngine()).guessConsonant(playerId, l)));
            }
            return moves;
        }

        if (phase == WofPhase.SPEEDUP_AWAITING_LETTER) {
            char letter = chooseAiSpeedupLetter();
            moves.add(new AiMove(humanDelay(AI_THINK_DELAY_MIN, AI_THINK_DELAY_MAX),
                    session -> ((WofEngine) session.getEngine()).speedupGuessLetter(playerId, letter)));
            return moves;
        }

        // A bot opens every turn with a spin, so the wheel turns on screen for AI turns just as it
        // does for people - otherwise a bot flush with Frenzy money would buy vowel after vowel
        // (or solve) without the wheel ever moving. Once it has spun, it plays on as before. The
        // exception is a board where only vowels are left hidden: a spin can only lead to calling
        // a consonant, and there's none left to find.
        boolean mustSpinFirst = !spunThisTurn && puzzleHasHiddenConsonant();
        if (canPlayWildCard(playerId) && lastSpinResult.getValue() >= AI_WILD_CARD_MIN_SPIN_VALUE) {
            moves.add(new AiMove(humanDelay(AI_THINK_DELAY_MIN, AI_THINK_DELAY_MAX),
                    session -> ((WofEngine) session.getEngine()).playWildCard(playerId)));
            return moves;
        }
        if (!mustSpinFirst && shouldAiAttemptSolve()) {
            moves.add(new AiMove(humanDelay(AI_THINK_DELAY_MIN, AI_THINK_DELAY_MAX),
                    session -> ((WofEngine) session.getEngine()).beginSolve(playerId)));
            return moves;
        }
        if (!mustSpinFirst && shouldAiBuyVowel(player)) {
            Character vowel = chooseAiVowel();
            if (vowel != null) {
                char v = vowel;
                moves.add(new AiMove(humanDelay(AI_THINK_DELAY_MIN, AI_THINK_DELAY_MAX),
                        session -> ((WofEngine) session.getEngine()).buyVowel(playerId, v)));
                return moves;
            }
        }
        if (!puzzleHasHiddenConsonant()) {
            // Only vowels remain and it can't pay for one, so no spin is allowed: solving is the only
            // move left. A bot that hasn't worked the puzzle out misses, and the turn moves on.
            moves.add(new AiMove(humanDelay(AI_THINK_DELAY_MIN, AI_THINK_DELAY_MAX),
                    session -> ((WofEngine) session.getEngine()).beginSolve(playerId)));
            return moves;
        }
        moves.add(new AiMove(humanDelay(AI_THINK_DELAY_MIN, AI_THINK_DELAY_MAX), session -> ((WofEngine) session.getEngine()).spin(playerId)));
        return moves;
    }

    /** ROUND_OVER: every AI player races to click Continue after its own human-like pause so an
     *  AI-only (or AI-heavy) game never stalls waiting on a bot to press a button - whichever fires
     *  first wins, the rest simply find the phase has already moved on. Mirrors
     *  {@link #pendingFrenzyContinueMoves()} for FRENZY_PUZZLE_OVER. */
    private List<AiMove> pendingRoundOverContinueMoves() {
        List<AiMove> moves = new ArrayList<>();
        for (SessionPlayer p : players) {
            if (p.isAi()) {
                String aiId = p.getPlayerId();
                moves.add(new AiMove(humanDelay(AI_CONTINUE_DELAY_MIN, AI_CONTINUE_DELAY_MAX),
                        session -> ((WofEngine) session.getEngine()).continueToNextRound(aiId)));
            }
        }
        return moves;
    }

    /** See {@link #pendingRoundOverContinueMoves()} - same idea, for the Frenzy puzzle-over pause. */
    private List<AiMove> pendingFrenzyContinueMoves() {
        List<AiMove> moves = new ArrayList<>();
        for (SessionPlayer p : players) {
            if (p.isAi()) {
                String aiId = p.getPlayerId();
                moves.add(new AiMove(humanDelay(AI_CONTINUE_DELAY_MIN, AI_CONTINUE_DELAY_MAX),
                        session -> ((WofEngine) session.getEngine()).continueFrenzy(aiId)));
            }
        }
        return moves;
    }

    private List<AiMove> pendingFrenzyMoves() {
        List<AiMove> moves = new ArrayList<>();
        long expectedVersion = version;
        // Listed first: this and every other move below fire off the same expectedVersion, and
        // whichever consumes it first "wins" for a given poll, so the window deadline needs first
        // refusal - in production each is independently scheduled at its own real time and this
        // ordering has no effect, but it matters for tests that invoke every pending move
        // synchronously. Only armed until it has fired: after that the last puzzle just plays out.
        if (!frenzyTimeUp) {
            moves.add(new AiMove(clampNonNegative(Duration.between(Instant.now(), frenzyDeadlineAt)),
                    session -> ((WofEngine) session.getEngine()).handleFrenzyTimeout(expectedVersion)));
        }

        int puzzleNumber = frenzyPuzzleNumber;
        if (frenzySolvingPlayerId != null) {
            // The board-fill reveal tick is deliberately NOT scheduled while someone is mid-answer
            // - the board holds still under their letter boxes until their answer settles it.
            //
            // The AI's own answer is listed before the timeout: both can fire off the same
            // expectedVersion, and whichever consumes it first wins for a given poll - in
            // production each is independently scheduled at its own real time so this has no
            // effect, but it lets a test that invokes every pending move synchronously see the AI
            // actually answer instead of always timing out first.
            SessionPlayer solver = findPlayer(frenzySolvingPlayerId);
            if (solver.isAi()) {
                String aiId = frenzySolvingPlayerId;
                String guess = aiSolveGuess(currentPuzzle.getPuzzle());
                moves.add(new AiMove(humanDelay(AI_FRENZY_ANSWER_DELAY_MIN, AI_FRENZY_ANSWER_DELAY_MAX),
                        session -> ((WofEngine) session.getEngine()).aiAttemptFrenzyAnswer(aiId, puzzleNumber, guess)));
            }
            // Computed as remaining time to the stored deadline, NOT a flat FRENZY_ANSWER_WINDOW,
            // since this method gets called again (e.g. by another player's action elsewhere in the
            // session) while this same attempt is still in flight, and a flat duration would keep
            // pushing the window back each time.
            moves.add(new AiMove(clampNonNegative(Duration.between(Instant.now(), frenzySolveDeadlineAt)),
                    session -> ((WofEngine) session.getEngine()).handleFrenzySolveTimeout(expectedVersion)));
        } else {
            moves.add(new AiMove(FRENZY_REVEAL_INTERVAL,
                    session -> ((WofEngine) session.getEngine()).revealNextFrenzyLetter(expectedVersion)));
            // A wrong ring-in throws the puzzle out for everyone, so a bot only rings in once enough
            // of the board shows for it to plausibly know the answer - the same bar it uses before
            // solving in a normal round. Weaker bots wait for more of the board.
            if (frenzyRevealedFraction() >= aiDifficulty.puzzleSolveThreshold()) {
                for (SessionPlayer p : players) {
                    if (p.isAi() && aiDifficulty.rolls(aiDifficulty.buzzInChance())) {
                        String aiId = p.getPlayerId();
                        moves.add(new AiMove(humanDelay(AI_FRENZY_ANSWER_DELAY_MIN, AI_FRENZY_ANSWER_DELAY_MAX),
                                session -> ((WofEngine) session.getEngine()).aiBeginFrenzySolve(aiId, puzzleNumber)));
                    }
                }
            }
        }
        return moves;
    }

    private List<AiMove> pendingTripleTossUpMoves() {
        List<AiMove> moves = new ArrayList<>();
        long expectedVersion = version;

        if (tripleTossUpSolvingPlayerId != null) {
            // No reveal tick while someone is answering (see revealNextTripleTossUpLetter). Same
            // ordering rationale as pendingFrenzyMoves(): the AI's own answer first, the give-up
            // timeout second.
            SessionPlayer solver = findPlayer(tripleTossUpSolvingPlayerId);
            if (solver.isAi()) {
                String aiId = tripleTossUpSolvingPlayerId;
                String guess = aiSolveGuess(currentPuzzle.getPuzzle());
                moves.add(new AiMove(humanDelay(AI_TRIPLE_TOSS_UP_ANSWER_DELAY_MIN, AI_TRIPLE_TOSS_UP_ANSWER_DELAY_MAX),
                        session -> ((WofEngine) session.getEngine()).attemptTripleTossUpAnswer(aiId, guess)));
            }
            moves.add(new AiMove(clampNonNegative(Duration.between(Instant.now(), tripleTossUpSolveDeadlineAt)),
                    session -> ((WofEngine) session.getEngine()).handleTripleTossUpSolveTimeout(expectedVersion)));
        } else {
            moves.add(new AiMove(TRIPLE_TOSS_UP_REVEAL_INTERVAL,
                    session -> ((WofEngine) session.getEngine()).revealNextTripleTossUpLetter(expectedVersion)));
            for (SessionPlayer p : players) {
                if (p.isAi() && !puzzleLockedOutPlayerIds.contains(p.getPlayerId())
                        && aiDifficulty.rolls(aiDifficulty.buzzInChance())) {
                    String aiId = p.getPlayerId();
                    moves.add(new AiMove(humanDelay(AI_TRIPLE_TOSS_UP_ANSWER_DELAY_MIN, AI_TRIPLE_TOSS_UP_ANSWER_DELAY_MAX),
                            session -> ((WofEngine) session.getEngine()).beginTripleTossUpSolve(aiId)));
                }
            }
        }
        return moves;
    }

    private List<AiMove> pendingSolveMoves() {
        List<AiMove> moves = new ArrayList<>();
        long expectedVersion = version;
        // The AI's own submission is listed before the timeout for the same reason as
        // pendingFrenzyMoves()/pendingTripleTossUpMoves() - it's how a test that invokes every
        // pending move synchronously sees the AI actually solve instead of always timing out first.
        SessionPlayer solver = findPlayer(solvingPlayerId);
        if (solver.isAi()) {
            String aiId = solvingPlayerId;
            String guess = aiSolveGuess(currentPuzzle.getPuzzle());
            moves.add(new AiMove(humanDelay(AI_THINK_DELAY_MIN, AI_THINK_DELAY_MAX),
                    session -> ((WofEngine) session.getEngine()).attemptSolve(aiId, guess)));
        }
        moves.add(new AiMove(PLAYER_SOLVE_WINDOW,
                session -> ((WofEngine) session.getEngine()).handleSolveTimeout(expectedVersion)));
        if (clockCuttingActive()) {
            moves.add(new AiMove(clampNonNegative(Duration.between(Instant.now(), gameClockEndsAt)),
                    session -> ((WofEngine) session.getEngine()).handleGameClockExpiry(expectedVersion)));
        }
        return moves;
    }

    private static Duration clampNonNegative(Duration duration) {
        return duration.isNegative() ? Duration.ZERO : duration;
    }

    /** A randomized "thinking" delay within [min, max] - gives AI opponents a normal human's
     *  variable reaction time instead of a suspiciously exact, identical pause every time, and
     *  stretches it further for the weaker difficulties so a human can beat them to the buzzer. */
    private Duration humanDelay(Duration min, Duration max) {
        long minMs = min.toMillis();
        long maxMs = max.toMillis();
        return aiDifficulty.stretch(Duration.ofMillis(ThreadLocalRandom.current().nextLong(minMs, maxMs + 1)));
    }

    private List<AiMove> pendingSpeedupSolveMoves() {
        List<AiMove> moves = new ArrayList<>();
        long expectedVersion = version;
        moves.add(new AiMove(SPEEDUP_SOLVE_WINDOW,
                session -> ((WofEngine) session.getEngine()).handleSpeedupSolveTimeout(expectedVersion)));
        SessionPlayer solver = findPlayer(speedupSolvingPlayerId);
        if (solver.isAi()) {
            String aiId = solver.getPlayerId();
            String guess = aiSolveGuess(currentPuzzle.getPuzzle());
            moves.add(new AiMove(humanDelay(AI_SPEEDUP_SOLVE_DELAY_MIN, AI_SPEEDUP_SOLVE_DELAY_MAX),
                    session -> ((WofEngine) session.getEngine()).speedupAttemptSolve(aiId, guess)));
        }
        return moves;
    }

    private List<AiMove> pendingBonusCategoryMoves() {
        SessionPlayer winner = findPlayer(bonusWinnerPlayerId);
        if (!winner.isAi()) {
            return List.of();
        }
        String aiId = winner.getPlayerId();
        // The options were dealt in random order, so the first is a random pick.
        String category = bonusCategoryOptions.get(0);
        return List.of(new AiMove(humanDelay(AI_BONUS_CATEGORY_DELAY_MIN, AI_BONUS_CATEGORY_DELAY_MAX),
                session -> ((WofEngine) session.getEngine()).pickBonusCategory(aiId, category)));
    }

    private List<AiMove> pendingBonusLettersMoves() {
        SessionPlayer winner = findPlayer(bonusWinnerPlayerId);
        if (!winner.isAi()) {
            return List.of();
        }
        String aiId = winner.getPlayerId();
        List<Character> consonants = chooseAiBonusConsonants();
        char vowel = chooseAiBonusVowel();
        return List.of(new AiMove(humanDelay(AI_THINK_DELAY_MIN, AI_THINK_DELAY_MAX),
                session -> ((WofEngine) session.getEngine()).submitBonusLetters(aiId, consonants, vowel)));
    }

    private List<AiMove> pendingBonusSolveMoves() {
        List<AiMove> moves = new ArrayList<>();
        long expectedVersion = version;
        moves.add(new AiMove(BONUS_SOLVE_WINDOW,
                session -> ((WofEngine) session.getEngine()).handleBonusSolveTimeout(expectedVersion)));
        SessionPlayer winner = findPlayer(bonusWinnerPlayerId);
        if (winner.isAi()) {
            String aiId = winner.getPlayerId();
            String guess = aiSolveGuess(bonusPuzzle.getPuzzle());
            moves.add(new AiMove(humanDelay(AI_BONUS_SOLVE_DELAY_MIN, AI_BONUS_SOLVE_DELAY_MAX),
                    session -> ((WofEngine) session.getEngine()).attemptBonusSolve(aiId, guess)));
        }
        return moves;
    }

    /** At the top difficulty this always picks a letter that's really in the puzzle; below that the
     *  bot only sometimes "sees" one and otherwise calls a letter that isn't there. */
    private Character chooseAiConsonant() {
        if (aiDifficulty.knowsAnswer()) {
            String puzzleUpper = currentPuzzle.getPuzzle().toUpperCase();
            for (char c : puzzleUpper.toCharArray()) {
                if (Character.isLetter(c) && VOWELS.indexOf(c) < 0 && !guessedConsonants.contains(c)) {
                    return c;
                }
            }
        }
        Character miss = randomUncalledConsonantNotInPuzzle();
        if (miss != null) {
            return miss;
        }
        // Nothing left to miss with: fall back to any uncalled consonant (which will be a hit).
        for (char c = 'A'; c <= 'Z'; c++) {
            if (VOWELS.indexOf(c) < 0 && !guessedConsonants.contains(c)) {
                return c;
            }
        }
        return null;
    }

    private Character randomUncalledConsonantNotInPuzzle() {
        String puzzleUpper = currentPuzzle.getPuzzle().toUpperCase();
        List<Character> misses = new ArrayList<>();
        for (char c = 'A'; c <= 'Z'; c++) {
            if (VOWELS.indexOf(c) < 0 && !guessedConsonants.contains(c) && puzzleUpper.indexOf(c) < 0) {
                misses.add(c);
            }
        }
        if (misses.isEmpty()) {
            return null;
        }
        return misses.get(ThreadLocalRandom.current().nextInt(misses.size()));
    }

    private Character chooseAiVowel() {
        String puzzleUpper = currentPuzzle.getPuzzle().toUpperCase();
        if (aiDifficulty.knowsAnswer()) {
            for (char c : puzzleUpper.toCharArray()) {
                if (VOWELS.indexOf(c) >= 0 && !guessedVowels.contains(c)) {
                    return c;
                }
            }
        }
        for (char c : VOWELS.toCharArray()) {
            if (!guessedVowels.contains(c)) {
                return c;
            }
        }
        return null;
    }

    /** What the bot types into the solve box: the real solution when it's worked the puzzle out,
     *  otherwise a guess that can't match (and never reaches another player's screen). */
    private String aiSolveGuess(String solution) {
        return aiDifficulty.knowsAnswer() ? solution : "";
    }

    private char chooseAiSpeedupLetter() {
        Character consonant = chooseAiConsonant();
        if (consonant != null) {
            return consonant;
        }
        Character vowel = chooseAiVowel();
        if (vowel != null) {
            return vowel;
        }
        for (char c = 'A'; c <= 'Z'; c++) {
            if (!guessedConsonants.contains(c) && !guessedVowels.contains(c)) {
                return c;
            }
        }
        return 'Z';
    }

    private List<Character> chooseAiBonusConsonants() {
        String puzzleUpper = aiDifficulty.knowsAnswer()
                ? bonusPuzzle.getPuzzle().toUpperCase()
                // Didn't work the puzzle out: falls back on the letters everyone reaches for.
                : "CDMPGBFHWKV";
        List<Character> chosen = new ArrayList<>();
        for (char c : puzzleUpper.toCharArray()) {
            if (chosen.size() == bonusConsonantCount) {
                break;
            }
            if (Character.isLetter(c) && VOWELS.indexOf(c) < 0 && !bonusRevealedLetters.contains(c) && !chosen.contains(c)) {
                chosen.add(c);
            }
        }
        for (char c = 'A'; c <= 'Z' && chosen.size() < bonusConsonantCount; c++) {
            if (VOWELS.indexOf(c) < 0 && !bonusRevealedLetters.contains(c) && !chosen.contains(c)) {
                chosen.add(c);
            }
        }
        return chosen;
    }

    private char chooseAiBonusVowel() {
        String puzzleUpper = bonusPuzzle.getPuzzle().toUpperCase();
        for (char c : puzzleUpper.toCharArray()) {
            if (VOWELS.indexOf(c) >= 0 && !bonusRevealedLetters.contains(c)) {
                return c;
            }
        }
        for (char v : VOWELS.toCharArray()) {
            if (!bonusRevealedLetters.contains(v)) {
                return v;
            }
        }
        return 'A';
    }

    private boolean shouldAiAttemptSolve() {
        String puzzle = currentPuzzle.getPuzzle().toUpperCase();
        int totalLetters = 0;
        int revealed = 0;
        for (char c : puzzle.toCharArray()) {
            if (Character.isLetter(c)) {
                totalLetters++;
                if (guessedConsonants.contains(c) || guessedVowels.contains(c)) {
                    revealed++;
                }
            }
        }
        return totalLetters > 0 && revealed >= Math.ceil(totalLetters * aiDifficulty.puzzleSolveThreshold());
    }

    /** Never once the puzzle is out of vowels - without this, a bot that knows the puzzle would fall
     *  back to buying one it knows isn't there. A weaker bot can still pick the wrong vowel while
     *  real ones remain; that miss is part of its difficulty. */
    private boolean shouldAiBuyVowel(SessionPlayer player) {
        return totalFundsOf(player.getPlayerId()) >= VOWEL_COST && puzzleHasHiddenVowel();
    }

    /** Whether any consonant in the puzzle is still hidden. Public knowledge on the show (the host
     *  announces "only vowels remain"), so every difficulty may use it. */
    private boolean puzzleHasHiddenConsonant() {
        return puzzleHasHiddenLetter(false);
    }

    /** Whether any vowel in the puzzle is still hidden - public knowledge the same way (the host
     *  announces "no more vowels"). */
    private boolean puzzleHasHiddenVowel() {
        return puzzleHasHiddenLetter(true);
    }

    private boolean puzzleHasHiddenLetter(boolean vowel) {
        Set<Character> called = vowel ? guessedVowels : guessedConsonants;
        for (char c : currentPuzzle.getPuzzle().toUpperCase().toCharArray()) {
            if (Character.isLetter(c) && (VOWELS.indexOf(c) >= 0) == vowel && !called.contains(c)) {
                return true;
            }
        }
        return false;
    }

    private void requireCurrentPlayer(String playerId) {
        if (!currentPlayer().getPlayerId().equals(playerId)) {
            throw new InvalidActionException("It is not your turn.");
        }
    }

    private void requirePhase(WofPhase expected) {
        requireWheelAtRest();
        if (phase != expected) {
            throw new InvalidActionException("That action isn't allowed right now.");
        }
    }

    private void requirePhaseAny(WofPhase... expected) {
        requireWheelAtRest();
        for (WofPhase p : expected) {
            if (phase == p) {
                return;
            }
        }
        throw new InvalidActionException("That action isn't allowed right now.");
    }

    /** Every player action is gated by requirePhase/requirePhaseAny, and the phase doesn't change
     *  while the wheel is turning, so checking here blocks every action until the result is in. */
    private void requireWheelAtRest() {
        if (wheelInMotion) {
            throw new InvalidActionException("Wait for the wheel to stop.");
        }
    }

    private SessionPlayer currentPlayer() {
        return players.get(currentPlayerIndex);
    }

    private void advanceTurn() {
        startTurnOf((currentPlayerIndex + 1) % players.size());
    }

    private void startTurnOf(int playerIndex) {
        currentPlayerIndex = playerIndex;
        spunThisTurn = false;
    }

    private SessionPlayer findPlayer(String playerId) {
        return players.stream().filter(p -> p.getPlayerId().equals(playerId)).findFirst()
                .orElseThrow(() -> new InvalidActionException("Unknown player."));
    }

    private int indexOfPlayer(String playerId) {
        for (int i = 0; i < players.size(); i++) {
            if (players.get(i).getPlayerId().equals(playerId)) {
                return i;
            }
        }
        throw new InvalidActionException("Unknown player.");
    }

    private static int countOccurrences(String puzzle, char letter) {
        int count = 0;
        for (char c : puzzle.toUpperCase().toCharArray()) {
            if (c == letter) {
                count++;
            }
        }
        return count;
    }

    private boolean isPuzzleFullyRevealed() {
        for (char c : currentPuzzle.getPuzzle().toUpperCase().toCharArray()) {
            if (Character.isLetter(c) && !guessedConsonants.contains(c) && !guessedVowels.contains(c)) {
                return false;
            }
        }
        return true;
    }

    private List<String> computeWinners() {
        int max = players.stream().mapToInt(SessionPlayer::getScore).max().orElse(0);
        return players.stream().filter(p -> p.getScore() == max).map(SessionPlayer::getPlayerId).toList();
    }

    private Puzzle pickPuzzle() {
        List<Puzzle> all = wofService.getAllPuzzles();
        if (all.isEmpty()) {
            throw new IllegalStateException("No Wheel of Fortune puzzles are available.");
        }
        List<Puzzle> unused = all.stream().filter(p -> !usedPuzzleIds.contains(p.getId())).toList();
        List<Puzzle> pool = unused.isEmpty() ? all : unused;
        Puzzle chosen = pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
        usedPuzzleIds.add(chosen.getId());
        return chosen;
    }

    private static String normalize(String s) {
        if (s == null) {
            return "";
        }
        return s.toUpperCase().replaceAll("[^A-Z0-9 ]", "").replaceAll("\\s+", " ").trim();
    }

    public WofStateSnapshot toSnapshot(String forPlayerId) {
        WheelLayout layout = WheelLayout.of(currentWheel());
        List<WedgeView> wheelViews = new ArrayList<>(layout.wedgeCount());
        for (int i = 0; i < layout.wedgeCount(); i++) {
            wheelViews.add(WedgeView.from(layout, i));
        }
        boolean frenzyRunning = phase == WofPhase.FORTUNE_FRENZY;
        boolean frenzyPaused = phase == WofPhase.FRENZY_PUZZLE_OVER;
        Long frenzyRemainingMs = null;
        if (frenzyRunning) {
            frenzyRemainingMs = frenzyTimeUp ? 0L
                    : clampNonNegative(Duration.between(Instant.now(), frenzyDeadlineAt)).toMillis();
        } else if (frenzyPaused) {
            frenzyRemainingMs = frenzyTimeRemainingOnPause.toMillis();
        }
        return new WofStateSnapshot(
                "WOF_STATE",
                phase.name(),
                roundNumber,
                tripleTossUpDone ? null : FIXED_ROUNDS_REPORTED,
                currentPuzzle.getCategory(),
                buildTemplate(),
                List.copyOf(guessedConsonants),
                List.copyOf(guessedVowels),
                puzzleHasHiddenConsonant(),
                puzzleHasHiddenVowel(),
                currentPlayer().getPlayerId(),
                lastSpinResult == null ? null : WedgeView.of(lastSpinResult, lastSpinResultStartSlot),
                List.copyOf(wheelViews),
                // Read off the wheel as it stands, so it always indexes `wheel` correctly - even once
                // a claimed Million Dollar Wedge has come off and the wedges have shifted.
                lastSpinResult == null ? null : layout.wedgeIndexAtSlot(lastWheelSpin.landingSlot()),
                lastWheelSpin == null ? null : lastWheelSpin.toView(wheelInMotion),
                players.stream().map(PlayerView::from).toList(),
                Map.copyOf(roundScores),
                roundWinnerPlayerId,
                gameWinnerPlayerIds,
                wildCardHolderId,
                canPlayWildCard(currentPlayer().getPlayerId()),
                phase == WofPhase.AWAITING_LETTER_GUESS && wildCardLetterInPlay,
                millionWedgeHolderId,
                millionWedgeHolderId != null && millionWedgeSecured,
                List.copyOf(puzzleLockedOutPlayerIds),
                phase == WofPhase.AWAITING_SOLVE ? solvingPlayerId : null,
                phase == WofPhase.AWAITING_SOLVE ? solveStartedAt : null,
                phase == WofPhase.AWAITING_SOLVE ? solveDeadlineAt : null,
                // Measured back from the deadline rather than recorded, since the clock stops
                // between puzzles and that pushes the deadline out - the bar always shows what's
                // left of the 30 seconds.
                frenzyRunning ? frenzyDeadlineAt.minus(frenzyWindow()) : null,
                frenzyRunning ? frenzyDeadlineAt : null,
                (frenzyRunning || frenzyPaused) && frenzyTimeUp,
                frenzyRemainingMs,
                frenzyRunning || frenzyPaused ? frenzyWindow().toMillis() : null,
                frenzyRunning || frenzyPaused ? frenzyPuzzleNumber : null,
                frenzyPaused && frenzyOutcome != null ? frenzyOutcome.name() : null,
                frenzyPaused ? frenzyOutcomePlayerId : null,
                frenzyRunning || frenzyPaused ? frenzyRoundOneStarterId() : null,
                frenzyRunning ? frenzySolvingPlayerId : null,
                phase == WofPhase.FORTUNE_FRENZY ? frenzySolveStartedAt : null,
                phase == WofPhase.FORTUNE_FRENZY ? frenzySolveDeadlineAt : null,
                tripleTossUpCategory,
                phase == WofPhase.TRIPLE_TOSS_UP ? tripleTossUpIndex : null,
                // List.copyOf() rejects null elements, but an unsolved puzzle legitimately records a
                // null solver - use a plain unmodifiable copy instead so that doesn't blow up.
                phase == WofPhase.TRIPLE_TOSS_UP ? Collections.unmodifiableList(new ArrayList<>(tripleTossUpSolverIds)) : null,
                phase == WofPhase.TRIPLE_TOSS_UP ? tripleTossUpSolvingPlayerId : null,
                phase == WofPhase.TRIPLE_TOSS_UP ? tripleTossUpSolveStartedAt : null,
                phase == WofPhase.TRIPLE_TOSS_UP ? tripleTossUpSolveDeadlineAt : null,
                gameClockStartedAt,
                gameClockEndsAt,
                speedupLetterValue,
                speedupSolvingPlayerId,
                bonusWinnerPlayerId,
                bonusCategoryOptions,
                bonusPuzzle == null ? null : bonusPuzzle.getCategory(),
                bonusPuzzle == null ? null : buildBonusTemplate(),
                bonusWinnerPlayerId == null ? null : bonusConsonantCount,
                bonusWinnerPlayerId != null && bonusMillionInPlay,
                (phase == WofPhase.GAME_OVER && bonusSolved != null) ? bonusPrizeAmount : null,
                bonusSolved);
    }

    private String buildTemplate() {
        StringBuilder sb = new StringBuilder();
        // Puzzle.getPuzzle() is right-padded to the board's full 52 characters - trim that padding
        // off before building the display template so it doesn't get treated as puzzle content.
        String text = currentPuzzle.getPuzzle().stripTrailing();
        // Fortune Frenzy reveals by board position (see frenzyRevealedPositions), not by letter -
        // every other phase reveals every occurrence of a called/bought letter at once.
        boolean revealByPosition = phase == WofPhase.FORTUNE_FRENZY;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            char upper = Character.toUpperCase(c);
            boolean revealed = revealByPosition
                    ? frenzyRevealedPositions.contains(i)
                    : (guessedConsonants.contains(upper) || guessedVowels.contains(upper));
            if (!Character.isLetter(upper)) {
                sb.append(c);
            } else if (revealed) {
                sb.append(upper);
            } else {
                sb.append('_');
            }
        }
        return sb.toString();
    }

    /** Server-side record of a spin; {@link #toView} is what clients see (the landed wedge stays
     *  private until it settles - clients derive it from the landing slot and the wheel layout). */
    private record WheelSpin(long spinId, Wedge wedge, int wedgeStartSlot, int landingSlot, Duration duration,
            Instant settlesAt) {
        WheelSpinView toView(boolean inMotion) {
            return new WheelSpinView(spinId, landingSlot, duration.toMillis(), settlesAt, inMotion);
        }
    }

    private String buildBonusTemplate() {
        StringBuilder sb = new StringBuilder();
        for (char c : bonusPuzzle.getPuzzle().stripTrailing().toCharArray()) {
            char upper = Character.toUpperCase(c);
            if (!Character.isLetter(upper)) {
                sb.append(c);
            } else if (bonusRevealedLetters.contains(upper)) {
                sb.append(upper);
            } else {
                sb.append('_');
            }
        }
        return sb.toString();
    }
}
