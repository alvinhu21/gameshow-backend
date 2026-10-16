package com.gameShows.live.session;

import com.gameShows.live.error.DuplicateDisplayNameException;
import com.gameShows.live.error.InvalidNameException;
import com.gameShows.live.error.InvalidSeatsException;
import com.gameShows.live.error.NotHostException;
import com.gameShows.live.error.SeatsNotFilledException;
import com.gameShows.live.error.SessionAlreadyStartedException;
import com.gameShows.live.error.SessionFullException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One game: the host plus the seats the host sets up in the lobby (see {@link Seat}). Mutators are
 * not synchronized themselves - callers hold {@link #getLock()}.
 */
public class GameSession {

    public static final int MAX_PLAYERS = 6;
    /** How many places besides the host's there can be. */
    public static final int MIN_OTHER_SEATS = 0;
    public static final int MAX_OTHER_SEATS = MAX_PLAYERS - 1;
    public static final int MAX_NAME_LENGTH = 24;

    /** A new session starts as the classic three-contestant game: the host and two open seats, so
     *  friends can join straight away (playing solo is two AI ticks away). */
    private static final int DEFAULT_OTHER_SEATS = 2;

    private final String code;
    private final GameType gameType;
    private final String hostPlayerId;
    private final Map<String, SessionPlayer> players = new LinkedHashMap<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final Instant createdAt = Instant.now();

    private volatile SessionStatus status = SessionStatus.LOBBY;
    private volatile Instant lastActivityAt = Instant.now();
    private volatile Object engine;
    private List<Seat> seats = List.copyOf(openSeats(DEFAULT_OTHER_SEATS));

    public GameSession(String code, GameType gameType, String hostPlayerId) {
        this.code = code;
        this.gameType = gameType;
        this.hostPlayerId = hostPlayerId;
    }

    public String getCode() {
        return code;
    }

    public GameType getGameType() {
        return gameType;
    }

    public String getHostPlayerId() {
        return hostPlayerId;
    }

    public SessionStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastActivityAt() {
        return lastActivityAt;
    }

    public void touch() {
        lastActivityAt = Instant.now();
    }

    public ReentrantLock getLock() {
        return lock;
    }

    public Object getEngine() {
        return engine;
    }

    public void setEngine(Object engine) {
        this.engine = engine;
    }

    /** The host, then whoever is in each seat, in seat order - the game's turn order. Open seats
     *  are skipped, and AI seats only have a player once the game has started. */
    public List<SessionPlayer> playersInSeatOrder() {
        List<SessionPlayer> lineup = new ArrayList<>();
        lineup.add(players.get(hostPlayerId));
        for (Seat seat : seats) {
            if (seat.playerId() != null) {
                lineup.add(players.get(seat.playerId()));
            }
        }
        return lineup.stream().filter(Objects::nonNull).toList();
    }

    public List<SeatView> seatViews() {
        return seats.stream()
                .map(seat -> SeatView.from(seat, seat.playerId() == null ? null : players.get(seat.playerId())))
                .toList();
    }

    public SessionPlayer findPlayer(String playerId) {
        return players.get(playerId);
    }

    public boolean allDisconnected() {
        return players.values().stream().noneMatch(SessionPlayer::isConnected);
    }

    /** The host takes their own place; anyone else takes the first open seat, and their name
     *  replaces whatever the host called it. */
    public void addPlayer(SessionPlayer player) {
        requireLobby();
        String name = validName(player.getDisplayName());
        requireNameFree(name, namesInUse(seats));
        if (player.isHost()) {
            if (!player.getPlayerId().equals(hostPlayerId)) {
                throw new IllegalArgumentException("Only the session's own host can take the host's place.");
            }
            players.put(player.getPlayerId(), player);
            touch();
            return;
        }
        int open = firstOpenSeat();
        if (open < 0) {
            throw new SessionFullException();
        }
        players.put(player.getPlayerId(), player);
        List<Seat> next = new ArrayList<>(seats);
        next.set(open, new Seat(name, false, player.getPlayerId()));
        seats = List.copyOf(next);
        touch();
    }

    /**
     * Replaces the seat list with the host's edit: how many seats (1-5), which are AI, and what
     * each is called. Everyone who has joined keeps a seat - they can't be dropped or turned into
     * an AI, and a taken seat keeps its occupant's name. An AI seat left blank gets a name.
     */
    public void updateSeats(String requestingPlayerId, List<Seat> requested) {
        requireHost(requestingPlayerId);
        requireLobby();
        if (requested == null || requested.size() < MIN_OTHER_SEATS || requested.size() > MAX_OTHER_SEATS) {
            throw new InvalidSeatsException(
                    "Choose between " + MIN_OTHER_SEATS + " and " + MAX_OTHER_SEATS + " other players.");
        }
        Set<String> seated = new HashSet<>();
        for (Seat seat : seats) {
            if (seat.playerId() != null) {
                seated.add(seat.playerId());
            }
        }
        Set<String> placed = new HashSet<>();
        List<Seat> next = new ArrayList<>(requested.size());
        for (Seat seat : requested) {
            if (seat == null) {
                throw new InvalidSeatsException("Every seat needs an entry.");
            }
            if (seat.playerId() == null) {
                if (seat.name().length() > MAX_NAME_LENGTH) {
                    throw new InvalidNameException(MAX_NAME_LENGTH);
                }
                next.add(new Seat(seat.name(), seat.ai(), null));
                continue;
            }
            if (!seated.contains(seat.playerId()) || !placed.add(seat.playerId())) {
                throw new InvalidSeatsException("The seats changed while you were editing - try again.");
            }
            if (seat.ai()) {
                throw new InvalidSeatsException("Someone has already joined that seat, so it can't be an AI.");
            }
            next.add(new Seat(players.get(seat.playerId()).getDisplayName(), false, seat.playerId()));
        }
        if (!placed.equals(seated)) {
            throw new InvalidSeatsException("Everyone who has joined needs to keep a seat.");
        }
        nameBlankAiSeats(next);
        Set<String> inUse = new HashSet<>();
        inUse.add(hostName().toLowerCase(Locale.ROOT));
        for (Seat seat : next) {
            if (!seat.isOpen()) {
                requireNameFree(seat.name(), inUse);
                inUse.add(seat.name().toLowerCase(Locale.ROOT));
            }
        }
        seats = List.copyOf(next);
        touch();
    }

    /** Only once every person's seat is taken: each AI seat gets its player, and the table is set. */
    public void start(String requestingPlayerId) {
        requireHost(requestingPlayerId);
        requireLobby();
        long open = seats.stream().filter(Seat::isOpen).count();
        if (open > 0) {
            throw new SeatsNotFilledException(open);
        }
        List<Seat> filled = new ArrayList<>(seats.size());
        for (Seat seat : seats) {
            if (seat.ai()) {
                String aiId = "ai-" + UUID.randomUUID();
                players.put(aiId, SessionPlayer.ai(aiId, seat.name()));
                filled.add(new Seat(seat.name(), true, aiId));
            } else {
                filled.add(seat);
            }
        }
        seats = List.copyOf(filled);
        status = SessionStatus.IN_PROGRESS;
        touch();
    }

    public void end() {
        status = SessionStatus.ENDED;
        touch();
    }

    public boolean isEligibleForEviction(Instant now) {
        Duration idle = Duration.between(lastActivityAt, now);
        if (status == SessionStatus.ENDED && idle.compareTo(Duration.ofMinutes(5)) > 0) {
            return true;
        }
        if (idle.compareTo(Duration.ofHours(2)) > 0) {
            return true;
        }
        return allDisconnected() && idle.compareTo(Duration.ofMinutes(10)) > 0;
    }

    private void requireHost(String requestingPlayerId) {
        if (!hostPlayerId.equals(requestingPlayerId)) {
            throw new NotHostException();
        }
    }

    private void requireLobby() {
        if (status != SessionStatus.LOBBY) {
            throw new SessionAlreadyStartedException();
        }
    }

    private int firstOpenSeat() {
        for (int i = 0; i < seats.size(); i++) {
            if (seats.get(i).isOpen()) {
                return i;
            }
        }
        return -1;
    }

    private String hostName() {
        SessionPlayer host = players.get(hostPlayerId);
        return host == null ? "" : host.getDisplayName();
    }

    /** Lower-cased names of the host and every seat that's somebody's (a taken seat or an AI) -
     *  an open seat's label is only a placeholder, so it doesn't reserve its name. */
    private Set<String> namesInUse(List<Seat> seatList) {
        Set<String> names = new HashSet<>();
        if (!hostName().isEmpty()) {
            names.add(hostName().toLowerCase(Locale.ROOT));
        }
        for (Seat seat : seatList) {
            if (!seat.isOpen() && !seat.name().isEmpty()) {
                names.add(seat.name().toLowerCase(Locale.ROOT));
            }
        }
        return names;
    }

    private void nameBlankAiSeats(List<Seat> seatList) {
        for (int i = 0; i < seatList.size(); i++) {
            Seat seat = seatList.get(i);
            if (seat.ai() && seat.name().isEmpty()) {
                seatList.set(i, new Seat(AiNames.unused(namesInUse(seatList)), true, null));
            }
        }
    }

    private static String validName(String name) {
        String stripped = name == null ? "" : name.strip();
        if (stripped.isEmpty() || stripped.length() > MAX_NAME_LENGTH) {
            throw new InvalidNameException(MAX_NAME_LENGTH);
        }
        return stripped;
    }

    private static void requireNameFree(String name, Set<String> namesInUseLowercase) {
        validName(name);
        if (namesInUseLowercase.contains(name.toLowerCase(Locale.ROOT))) {
            throw new DuplicateDisplayNameException(name);
        }
    }

    private static List<Seat> openSeats(int count) {
        List<Seat> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            list.add(Seat.open(""));
        }
        return list;
    }
}
