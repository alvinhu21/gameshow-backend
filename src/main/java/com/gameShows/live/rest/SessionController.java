package com.gameShows.live.rest;

import com.gameShows.live.jeopardy.JeopardyEngine;
import com.gameShows.live.jeopardy.choices.ResponseChoiceDealer;
import com.gameShows.live.session.GameSession;
import com.gameShows.live.session.GameType;
import com.gameShows.live.session.LiveActionOutcome;
import com.gameShows.live.session.LiveGameEngine;
import com.gameShows.live.session.PlayerView;
import com.gameShows.live.session.SeatView;
import com.gameShows.live.session.SessionPlayer;
import com.gameShows.live.session.SessionRegistry;
import com.gameShows.live.wof.WofEngine;
import com.gameShows.live.ws.LiveGameOrchestrator;
import com.gameShows.services.JeopardyService;
import com.gameShows.services.WheelOfFortuneService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/live/sessions")
public class SessionController {

    private final SessionRegistry registry;
    private final WheelOfFortuneService wofService;
    private final JeopardyService jeopardyService;
    private final ResponseChoiceDealer responseChoiceDealer;
    private final SimpMessagingTemplate messagingTemplate;
    private final LiveGameOrchestrator orchestrator;

    public SessionController(SessionRegistry registry, WheelOfFortuneService wofService,
            JeopardyService jeopardyService, ResponseChoiceDealer responseChoiceDealer,
            SimpMessagingTemplate messagingTemplate, LiveGameOrchestrator orchestrator) {
        this.registry = registry;
        this.wofService = wofService;
        this.jeopardyService = jeopardyService;
        this.responseChoiceDealer = responseChoiceDealer;
        this.messagingTemplate = messagingTemplate;
        this.orchestrator = orchestrator;
    }

    @PostMapping
    public ResponseEntity<CreateSessionResponse> create(@RequestBody CreateSessionRequest request) {
        GameSession session = registry.create(request.gameType(), strip(request.hostDisplayName()));
        return ResponseEntity.status(HttpStatus.CREATED).body(CreateSessionResponse.from(session));
    }

    /** Takes the first open seat (see GameSession#addPlayer), or 409 when none is open. */
    @PostMapping("/{code}/players")
    public ResponseEntity<JoinSessionResponse> join(@PathVariable String code, @RequestBody JoinSessionRequest request) {
        GameSession session = registry.get(code);
        String playerId = UUID.randomUUID().toString();
        List<PlayerView> players;
        List<SeatView> seats;
        session.getLock().lock();
        try {
            session.addPlayer(new SessionPlayer(playerId, strip(request.displayName()), false));
            players = session.playersInSeatOrder().stream().map(PlayerView::from).toList();
            seats = session.seatViews();
        } finally {
            session.getLock().unlock();
        }
        messagingTemplate.convertAndSend("/topic/session/" + session.getCode() + "/players", players);
        messagingTemplate.convertAndSend("/topic/session/" + session.getCode() + "/seats", seats);
        JoinSessionResponse response = new JoinSessionResponse(playerId, session.getCode(), session.getGameType(), players);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /** The host's lobby edit: how many other players, which are AI, and their names. */
    @PutMapping("/{code}/seats")
    public ResponseEntity<List<SeatView>> updateSeats(@PathVariable String code, @RequestBody UpdateSeatsRequest request) {
        GameSession session = registry.get(code);
        List<SeatView> seats;
        session.getLock().lock();
        try {
            session.updateSeats(request.hostPlayerId(), request.toSeats());
            seats = session.seatViews();
        } finally {
            session.getLock().unlock();
        }
        messagingTemplate.convertAndSend("/topic/session/" + session.getCode() + "/seats", seats);
        return ResponseEntity.ok(seats);
    }

    @GetMapping("/{code}")
    public ResponseEntity<SessionSummaryResponse> get(@PathVariable String code) {
        GameSession session = registry.get(code);
        session.getLock().lock();
        try {
            return ResponseEntity.ok(SessionSummaryResponse.from(session));
        } finally {
            session.getLock().unlock();
        }
    }

    @PostMapping("/{code}/start")
    public ResponseEntity<Void> start(@PathVariable String code, @RequestBody StartSessionRequest request) {
        // Routed through the orchestrator (rather than broadcasting directly) so that any AI moves
        // pending right out of the gate - e.g. Wheel of Fortune's toss-up letter-reveal timer -
        // actually get scheduled, exactly as they would after any other in-game action.
        orchestrator.act(code, session -> {
            session.start(request.hostPlayerId());
            List<SessionPlayer> players = session.playersInSeatOrder();
            LiveGameEngine engine;
            if (session.getGameType() == GameType.WOF) {
                Duration duration = request.gameDurationMinutes() != null
                        ? Duration.ofMinutes(request.gameDurationMinutes())
                        : WofEngine.DEFAULT_GAME_DURATION;
                engine = new WofEngine(players, wofService, duration, request.aiDifficulty());
            } else {
                int rounds = request.jeopardyRounds() != null
                        ? request.jeopardyRounds()
                        : JeopardyEngine.DEFAULT_ROUNDS;
                int increment = request.clueBaseIncrement() != null
                        ? request.clueBaseIncrement()
                        : JeopardyEngine.DEFAULT_CLUE_BASE_INCREMENT;
                boolean allowTies = request.allowTies() != null
                        ? request.allowTies()
                        : JeopardyEngine.DEFAULT_ALLOW_TIES;
                engine = new JeopardyEngine(players, jeopardyService, rounds, increment, request.aiDifficulty(),
                        responseChoiceDealer, allowTies);
            }
            session.setEngine(engine);
            return LiveActionOutcome.of(engine.toSnapshot(null));
        });
        return ResponseEntity.ok().build();
    }

    @GetMapping("/{code}/state")
    public ResponseEntity<Object> state(@PathVariable String code, @RequestParam String playerId) {
        GameSession session = registry.get(code);
        session.getLock().lock();
        try {
            Object engine = session.getEngine();
            if (engine == null) {
                return ResponseEntity.ok(SessionSummaryResponse.from(session));
            }
            return ResponseEntity.ok(((LiveGameEngine) engine).toSnapshot(playerId));
        } finally {
            session.getLock().unlock();
        }
    }

    private static String strip(String name) {
        return name == null ? null : name.strip();
    }
}
