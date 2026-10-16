package com.gameShows.live.wof;

import com.gameShows.models.wof.Wedge;
import com.gameShows.models.wof.WheelLayout;

/**
 * One wedge as clients see it. {@code startSlot}/{@code slotCount} place it on the wheel's
 * {@link WheelLayout#TOTAL_SLOTS}-slot rim, so clients can work out every wedge's angles from this
 * data alone.
 */
public record WedgeView(String label, Integer value, String effect, String backgroundColor, String textColor,
        int startSlot, int slotCount) {

    public static WedgeView of(Wedge wedge, int startSlot) {
        return new WedgeView(wedge.getLabel(), wedge.getValue(), wedge.getEffect(), wedge.getBackgroundColor(),
                wedge.getColor(), startSlot, wedge.getSlotCount());
    }

    public static WedgeView from(WheelLayout layout, int wedgeIndex) {
        return of(layout.wedgeAt(wedgeIndex), layout.startSlotOf(wedgeIndex));
    }
}
