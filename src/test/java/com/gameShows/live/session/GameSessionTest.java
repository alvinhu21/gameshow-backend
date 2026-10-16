package com.gameShows.live.session;

import com.gameShows.live.error.DuplicateDisplayNameException;
import com.gameShows.live.error.InvalidNameException;
import com.gameShows.live.error.InvalidSeatsException;
import com.gameShows.live.error.NotHostException;
import com.gameShows.live.error.SeatsNotFilledException;
import com.gameShows.live.error.SessionAlreadyStartedException;
import com.gameShows.live.error.SessionFullException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GameSessionTest {

    private GameSession session;

    @BeforeEach
    void setUp() {
        session = new GameSession("CODE01", GameType.WOF, "host1");
        session.addPlayer(new SessionPlayer("host1", "Alice", true));
    }

    private static Seat ai(String name) {
        return new Seat(name, true, null);
    }

    private static Seat taken(String playerId) {
        return new Seat("", false, playerId);
    }

    private void join(String playerId, String name) {
        session.addPlayer(new SessionPlayer(playerId, name, false));
    }

    private List<String> lineupNames() {
        return session.playersInSeatOrder().stream().map(SessionPlayer::getDisplayName).toList();
    }

    @Test
    void aNewSessionHasTwoOpenSeatsForPeople() {
        assertThat(session.seatViews()).hasSize(2).allSatisfy(seat -> {
            assertThat(seat.ai()).isFalse();
            assertThat(seat.playerId()).isNull();
        });
    }

    @Test
    void joinersTakeTheOpenSeatsInOrderUnderTheirOwnNames() {
        session.updateSeats("host1", List.of(Seat.open("Mom"), ai("Watson"), Seat.open("Dad")));

        join("p2", "Sam");
        join("p3", "Jo");

        assertThat(session.seatViews()).extracting(SeatView::name).containsExactly("Sam", "Watson", "Jo");
        assertThat(session.seatViews()).extracting(SeatView::playerId).containsExactly("p2", null, "p3");
    }

    @Test
    void nobodyCanJoinOnceEverySeatForAPersonIsTaken() {
        session.updateSeats("host1", List.of(Seat.open(""), ai("Watson")));
        join("p2", "Sam");

        assertThatThrownBy(() -> join("p3", "Jo")).isInstanceOf(SessionFullException.class);
    }

    @Test
    void playingSoloTakesOneAiSeat() {
        session.updateSeats("host1", List.of(ai("Watson")));

        session.start("host1");

        assertThat(lineupNames()).containsExactly("Alice", "Watson");
        assertThat(session.playersInSeatOrder().get(1).isAi()).isTrue();
    }

    @Test
    void theGameLineupFollowsSeatOrderWithTheHostFirst() {
        session.updateSeats("host1", List.of(ai("Watson"), Seat.open(""), ai("Ada")));
        join("p2", "Sam");

        session.start("host1");

        assertThat(lineupNames()).containsExactly("Alice", "Watson", "Sam", "Ada");
        assertThat(session.playersInSeatOrder()).extracting(SessionPlayer::isAi)
                .containsExactly(false, true, false, true);
    }

    @Test
    void anAiSeatLeftBlankGetsAnUnusedName() {
        session.updateSeats("host1", List.of(ai(""), ai(""), ai("")));

        List<String> names = session.seatViews().stream().map(SeatView::name).toList();
        assertThat(names).doesNotContain("").doesNotHaveDuplicates().doesNotContain("Alice");
    }

    @Test
    void theHostChoosesBetweenOneAndFiveOtherPlayers() {
        assertThatThrownBy(() -> session.updateSeats("host1", List.of())).isInstanceOf(InvalidSeatsException.class);
        assertThatThrownBy(() -> session.updateSeats("host1", Collections.nCopies(6, ai(""))))
                .isInstanceOf(InvalidSeatsException.class);
        assertThatThrownBy(() -> session.updateSeats("host1", null)).isInstanceOf(InvalidSeatsException.class);

        session.updateSeats("host1", Collections.nCopies(5, ai("")));
        assertThat(session.seatViews()).hasSize(5);
    }

    @Test
    void namesAtTheTableMustBeUnique() {
        assertThatThrownBy(() -> session.updateSeats("host1", List.of(ai("alice"))))
                .isInstanceOf(DuplicateDisplayNameException.class);
        assertThatThrownBy(() -> session.updateSeats("host1", List.of(ai("Watson"), ai("WATSON"))))
                .isInstanceOf(DuplicateDisplayNameException.class);

        session.updateSeats("host1", List.of(ai("Watson"), Seat.open("")));
        assertThatThrownBy(() -> join("p2", "watson")).isInstanceOf(DuplicateDisplayNameException.class);
    }

    @Test
    void namesMustFitOnTheBoard() {
        assertThatThrownBy(() -> session.updateSeats("host1", List.of(ai("x".repeat(25)))))
                .isInstanceOf(InvalidNameException.class);
        assertThatThrownBy(() -> join("p2", "   ")).isInstanceOf(InvalidNameException.class);
    }

    @Test
    void someoneWhoHasJoinedKeepsTheirSeat() {
        join("p2", "Sam");

        assertThatThrownBy(() -> session.updateSeats("host1", List.of(ai("Watson"))))
                .as("dropping Sam's seat")
                .isInstanceOf(InvalidSeatsException.class);
        assertThatThrownBy(() -> session.updateSeats("host1", List.of(new Seat("Sam", true, "p2"))))
                .as("turning Sam into an AI")
                .isInstanceOf(InvalidSeatsException.class);
        assertThatThrownBy(() -> session.updateSeats("host1", List.of(taken("p2"), taken("p2"))))
                .as("seating Sam twice")
                .isInstanceOf(InvalidSeatsException.class);
        assertThatThrownBy(() -> session.updateSeats("host1", List.of(taken("stranger"))))
                .as("seating someone who never joined")
                .isInstanceOf(InvalidSeatsException.class);

        // Moving Sam and renaming the seat both work - the seat still shows Sam's own name.
        session.updateSeats("host1", List.of(ai("Watson"), new Seat("Renamed", false, "p2")));
        assertThat(session.seatViews()).extracting(SeatView::name).containsExactly("Watson", "Sam");
    }

    @Test
    void theGameCannotStartWhileASeatForAPersonIsOpen() {
        session.updateSeats("host1", List.of(Seat.open(""), ai("Watson")));

        assertThatThrownBy(() -> session.start("host1"))
                .isInstanceOf(SeatsNotFilledException.class)
                .hasMessageContaining("1 more player");
    }

    @Test
    void onlyTheHostMaySetUpTheSeatsOrStart() {
        assertThatThrownBy(() -> session.updateSeats("someone-else", List.of(ai("Watson"))))
                .isInstanceOf(NotHostException.class);
        assertThatThrownBy(() -> session.start("someone-else")).isInstanceOf(NotHostException.class);
    }

    @Test
    void theSeatsAreFixedOnceTheGameStarts() {
        session.updateSeats("host1", List.of(ai("Watson")));
        session.start("host1");

        assertThatThrownBy(() -> session.updateSeats("host1", List.of(ai("Ada"))))
                .isInstanceOf(SessionAlreadyStartedException.class);
        assertThatThrownBy(() -> join("p2", "Sam")).isInstanceOf(SessionAlreadyStartedException.class);
    }
}
