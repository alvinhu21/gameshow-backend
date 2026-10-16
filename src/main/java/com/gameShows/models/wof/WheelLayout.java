package com.gameShows.models.wof;

import java.util.List;

/**
 * The wheel's physical geometry. The rim is divided into {@link #TOTAL_SLOTS} equal slots, and each
 * wedge covers a run of neighboring slots ({@link Wedge#SLOTS_PER_WEDGE} for every wedge today). A
 * spin lands on one slot, not just on a wedge, so clients can draw the wheel stopping in the left,
 * middle or right third of the wedge.
 *
 * <p>Slot 0 starts at the 12 o'clock mark of the unrotated wheel, and slots count clockwise in the
 * same order as the wedge list.
 */
public final class WheelLayout {

    public static final int TOTAL_SLOTS = 72;
    public static final double DEGREES_PER_SLOT = 360.0 / TOTAL_SLOTS;

    private final List<Wedge> wedges;
    private final int[] startSlots;
    /** One entry per slot, so looking up the wedge under a slot is a single array read. */
    private final int[] wedgeIndexBySlot;

    private WheelLayout(List<Wedge> wedges) {
        this.wedges = List.copyOf(wedges);
        this.startSlots = new int[wedges.size()];
        this.wedgeIndexBySlot = new int[TOTAL_SLOTS];

        int slot = 0;
        for (int i = 0; i < wedges.size(); i++) {
            int span = wedges.get(i).getSlotCount();
            if (span < 1) {
                throw new IllegalArgumentException("Wedge " + i + " must span at least one slot.");
            }
            if (slot + span > TOTAL_SLOTS) {
                throw new IllegalArgumentException("Wedges span more than " + TOTAL_SLOTS + " slots.");
            }
            startSlots[i] = slot;
            for (int s = slot; s < slot + span; s++) {
                wedgeIndexBySlot[s] = i;
            }
            slot += span;
        }
        if (slot != TOTAL_SLOTS) {
            throw new IllegalArgumentException(
                    "Wedges span " + slot + " slots, but the wheel has exactly " + TOTAL_SLOTS + ".");
        }
    }

    /** @throws IllegalArgumentException unless the wedges exactly cover all {@link #TOTAL_SLOTS} slots. */
    public static WheelLayout of(List<Wedge> wedges) {
        return new WheelLayout(wedges);
    }

    public int wedgeCount() {
        return wedges.size();
    }

    public Wedge wedgeAt(int wedgeIndex) {
        return wedges.get(wedgeIndex);
    }

    public int startSlotOf(int wedgeIndex) {
        return startSlots[wedgeIndex];
    }

    public int slotCountOf(int wedgeIndex) {
        return wedges.get(wedgeIndex).getSlotCount();
    }

    public int wedgeIndexAtSlot(int slot) {
        if (slot < 0 || slot >= TOTAL_SLOTS) {
            throw new IndexOutOfBoundsException("Slot " + slot + " is outside 0.." + (TOTAL_SLOTS - 1) + ".");
        }
        return wedgeIndexBySlot[slot];
    }
}
