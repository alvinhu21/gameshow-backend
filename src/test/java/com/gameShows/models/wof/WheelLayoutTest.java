package com.gameShows.models.wof;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WheelLayoutTest {

    @Test
    void everyRoundsWheelIsTwentyFourWedgesOfThreeSlotsEach() {
        for (int round = 1; round <= 5; round++) {
            WheelLayout layout = WheelLayout.of(Wedge.buildSyndicatedWheel(round));

            assertThat(layout.wedgeCount()).as("round %d", round).isEqualTo(24);
            for (int i = 0; i < layout.wedgeCount(); i++) {
                assertThat(layout.slotCountOf(i)).isEqualTo(3);
                assertThat(layout.startSlotOf(i)).isEqualTo(i * 3);
                assertThat(layout.wedgeAt(i).getAngleDegrees()).isEqualTo(15.0);
            }
        }
    }

    @Test
    void everySlotMapsToTheWedgeThatCoversIt() {
        WheelLayout layout = WheelLayout.of(Wedge.buildSyndicatedWheel(1));

        for (int slot = 0; slot < WheelLayout.TOTAL_SLOTS; slot++) {
            int wedgeIndex = layout.wedgeIndexAtSlot(slot);
            int start = layout.startSlotOf(wedgeIndex);
            assertThat(slot).isBetween(start, start + layout.slotCountOf(wedgeIndex) - 1);
        }
    }

    @Test
    void wedgeValuesAreTheSyndicatedOnes() {
        List<Wedge> round1 = Wedge.buildSyndicatedWheel(1);

        assertThat(round1.get(0).getLabel()).isEqualTo("$2500");
        assertThat(round1.stream().filter(Wedge::isBankrupt)).hasSize(2);
        assertThat(round1.stream().filter(Wedge::isLoseTurn)).hasSize(1);
        assertThat(round1.stream().filter(w -> w.getValue() != null)).hasSize(21);
    }

    @Test
    void theWildCardCoversAWholePurple500() {
        List<Wedge> plain = Wedge.buildSyndicatedWheel(1);
        List<Wedge> wheel = Wedge.buildSyndicatedWheel(1, true, false);

        Wedge wildCard = wheel.get(Wedge.WILD_CARD_HOST_INDEX);
        assertThat(wildCard.isWildCard()).isTrue();
        assertThat(wildCard.getValue()).isEqualTo(500);
        assertThat(wildCard.getSlotCount()).isEqualTo(3);
        assertThat(wildCard.getBackgroundColor()).isEqualTo(plain.get(Wedge.WILD_CARD_HOST_INDEX).getBackgroundColor());
        assertThat(wheel.stream().filter(Wedge::isWildCard)).hasSize(1);
        assertThat(WheelLayout.of(wheel).wedgeCount()).isEqualTo(24);
    }

    @Test
    void theMillionDollarWedgeIsAOnePegMillionBetweenTwoOnePegBankruptsOnThePink500() {
        Wedge host = Wedge.buildSyndicatedWheel(1).get(Wedge.MILLION_WEDGE_HOST_INDEX);
        assertThat(host.getLabel()).isEqualTo("$500");
        assertThat(host.getBackgroundColor()).isEqualTo("#FEA6BE");

        WheelLayout layout = WheelLayout.of(Wedge.buildSyndicatedWheel(1, false, true));

        assertThat(layout.wedgeCount()).isEqualTo(26);
        int million = Wedge.MILLION_WEDGE_HOST_INDEX + 1;
        assertThat(layout.wedgeAt(million).isMillion()).isTrue();
        assertThat(layout.wedgeAt(million).getValue()).isEqualTo(500);
        assertThat(layout.wedgeAt(million - 1).isBankrupt()).isTrue();
        assertThat(layout.wedgeAt(million + 1).isBankrupt()).isTrue();
        for (int i = million - 1; i <= million + 1; i++) {
            assertThat(layout.slotCountOf(i)).isEqualTo(1);
        }
        // Exactly the slots the $500 it covers used to have.
        assertThat(layout.startSlotOf(million - 1)).isEqualTo(Wedge.MILLION_WEDGE_HOST_INDEX * 3);
    }

    @Test
    void bothSpecialWedgesTogetherStillTileTheRimInEveryRound() {
        for (int round = 1; round <= 5; round++) {
            List<Wedge> wheel = Wedge.buildSyndicatedWheel(round, true, true);
            WheelLayout layout = WheelLayout.of(wheel); // throws unless the rim is covered exactly

            assertThat(layout.wedgeCount()).as("round %d", round).isEqualTo(26);
            assertThat(wheel.stream().filter(Wedge::isWildCard)).hasSize(1);
            assertThat(wheel.stream().filter(Wedge::isMillion)).hasSize(1);
            assertThat(wheel.stream().filter(Wedge::isBankrupt)).hasSize(4);
        }
    }

    @Test
    void rejectsWedgesThatDoNotCoverTheRimExactly() {
        List<Wedge> tooFew = Wedge.buildSyndicatedWheel(1).subList(0, 23);
        List<Wedge> tooMany = new ArrayList<>(Wedge.buildSyndicatedWheel(1));
        tooMany.add(new Wedge("$500", 500, "#23D478", "#111827"));

        assertThatThrownBy(() -> WheelLayout.of(tooFew)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WheelLayout.of(tooMany)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsSlotsOffTheRim() {
        WheelLayout layout = WheelLayout.of(Wedge.buildSyndicatedWheel(1));

        assertThatThrownBy(() -> layout.wedgeIndexAtSlot(-1)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> layout.wedgeIndexAtSlot(WheelLayout.TOTAL_SLOTS))
                .isInstanceOf(IndexOutOfBoundsException.class);
    }
}
