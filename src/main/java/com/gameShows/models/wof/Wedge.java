package com.gameShows.models.wof;

import java.util.ArrayList;
import java.util.List;

public class Wedge {
    /** Every wedge on the syndicated wheel covers three of the {@link WheelLayout#TOTAL_SLOTS} rim slots. */
    public static final int SLOTS_PER_WEDGE = 3;

    public static final String EFFECT_WILD_CARD = "WILD_CARD";
    public static final String EFFECT_MILLION = "MILLION";

    /** The Wild Card sits on the purple $500 between the $650 and the $800. */
    static final int WILD_CARD_HOST_INDEX = 5;
    /** The Million Dollar Wedge sits on the pink $500 between the $500 and the $700. */
    static final int MILLION_WEDGE_HOST_INDEX = 16;
    private static final int SPECIAL_WEDGE_HOST_VALUE = 500;
    private static final String MILLION_GREEN = "#0BA84A";
    private static final String MINI_BANKRUPT_BLACK = "#16171D";

    public String label = "";
    public Integer value = 0;
    public String backgroundColor = "#ffffff";
    public String color = "#000000";
    public String effect = "";
    public int slotCount = SLOTS_PER_WEDGE;

    public Wedge(String label, Integer value, String backgroundColor, String color) {
        this(label, value, backgroundColor, color, "");
    }

    public Wedge(String label, Integer value, String backgroundColor, String color, String effect) {
        this(label, value, backgroundColor, color, effect, SLOTS_PER_WEDGE);
    }

    public Wedge(String label, Integer value, String backgroundColor, String color, String effect, int slotCount) {
        this.label = label;
        this.value = value;
        this.backgroundColor = backgroundColor;
        this.color = color;
        this.effect = effect;
        this.slotCount = slotCount;
    }

    public String getLabel() {
        return this.label;
    }

    public Integer getValue() {
        return this.value;
    }

    public String getBackgroundColor() {
        return this.backgroundColor;
    }

    public String getColor() {
        return this.color;
    }

    public String getEffect() {
        return this.effect;
    }

    public int getSlotCount() {
        return this.slotCount;
    }

    /** Derived from the slot count; kept because the REST wheel endpoint has always exposed it. */
    public double getAngleDegrees() {
        return this.slotCount * WheelLayout.DEGREES_PER_SLOT;
    }

    public boolean isLoseTurn() {
        return "LOSE A TURN".equalsIgnoreCase(this.label);
    }

    public boolean isBankrupt() {
        return "BANKRUPT".equalsIgnoreCase(this.label);
    }

    public boolean isWildCard() {
        return EFFECT_WILD_CARD.equals(this.effect);
    }

    /** The one-slot "ONE MILLION" centre of the Million Dollar Wedge - not its Bankrupt flanks. */
    public boolean isMillion() {
        return EFFECT_MILLION.equals(this.effect);
    }

    /**
     * The round's wheel with whichever special wedges are still on it. Both sit on top of a $500
     * wedge, and landing on either plays for that $500:
     * <ul>
     *   <li>The Wild Card covers its host wedge's full three slots.</li>
     *   <li>The Million Dollar Wedge splits its host into three one-slot (one-peg-wide) pieces: a
     *       shiny green "ONE MILLION" with a Bankrupt on either side. Only the centre is the
     *       million; the flanks are ordinary Bankrupts.</li>
     * </ul>
     * Either way the wheel still covers exactly {@link WheelLayout#TOTAL_SLOTS} slots.
     */
    public static List<Wedge> buildSyndicatedWheel(int roundNumber, boolean wildCardOnWheel, boolean millionWedgeOnWheel) {
        List<Wedge> wedges = buildSyndicatedWheel(roundNumber);
        // The Wild Card's host comes first on the rim, so splitting the Million wedge's host
        // afterwards can't shift it.
        if (wildCardOnWheel) {
            Wedge host = requireSpecialHost(wedges, WILD_CARD_HOST_INDEX);
            wedges.set(WILD_CARD_HOST_INDEX, new Wedge("WILD CARD", host.getValue(), host.getBackgroundColor(),
                    host.getColor(), EFFECT_WILD_CARD, host.getSlotCount()));
        }
        if (millionWedgeOnWheel) {
            Wedge host = requireSpecialHost(wedges, MILLION_WEDGE_HOST_INDEX);
            wedges.remove(MILLION_WEDGE_HOST_INDEX);
            wedges.addAll(MILLION_WEDGE_HOST_INDEX, List.of(
                    miniBankrupt(),
                    new Wedge("ONE MILLION", host.getValue(), MILLION_GREEN, textColorFor(MILLION_GREEN), EFFECT_MILLION, 1),
                    miniBankrupt()));
        }
        return wedges;
    }

    private static Wedge requireSpecialHost(List<Wedge> wedges, int index) {
        Wedge host = wedges.get(index);
        if (host.getValue() == null || host.getValue() != SPECIAL_WEDGE_HOST_VALUE || host.getSlotCount() != SLOTS_PER_WEDGE) {
            throw new IllegalStateException("Wedge " + index + " (" + host.getLabel()
                    + ") can't host a special wedge - it must be a full-width $" + SPECIAL_WEDGE_HOST_VALUE + ".");
        }
        return host;
    }

    private static Wedge miniBankrupt() {
        return new Wedge("BANKRUPT", null, MINI_BANKRUPT_BLACK, textColorFor(MINI_BANKRUPT_BLACK), "BANKRUPT", 1);
    }

    /** The plain wheel for a round, with no special wedges on it. */
    public static List<Wedge> buildSyndicatedWheel(int roundNumber) {
        List<Wedge> wedges = new ArrayList<>();

        if(roundNumber == 1){
            addDollarWedge(wedges, 2500, "#05F4FD");
        }else if(roundNumber == 2 || roundNumber == 3){
            addDollarWedge(wedges, 3500, "#C600C6");
        }else if(roundNumber >= 4){
            addDollarWedge(wedges, 5000, "#E2DFDF");
        }
        addSpecialWedge(wedges, "BANKRUPT", "#2E3039");
        addDollarWedge(wedges, 900, "#FC6008");
        addDollarWedge(wedges, 500, "#23D478");
        addDollarWedge(wedges, 650, "#FEA6BE");
        addDollarWedge(wedges, 500, "#BC71E7");
        addDollarWedge(wedges, 800, "#E41F17");
        addSpecialWedge(wedges, "LOSE A TURN", "#FFFFFF");
        addDollarWedge(wedges, 700, "#42B3D6");
        addDollarWedge(wedges, 850, "#FDE308");
        addDollarWedge(wedges, 650, "#BC71E7");
        addSpecialWedge(wedges, "BANKRUPT", "#2B272E");
        addDollarWedge(wedges, 600, "#FEA6BE");
        addDollarWedge(wedges, 500, "#23D478");
        addDollarWedge(wedges, 550, "#42B3D6");
        addDollarWedge(wedges, 600, "#E41F17");
        addDollarWedge(wedges, 500, "#FEA6BE");
        addDollarWedge(wedges, 700, "#FDE308");
        addDollarWedge(wedges, 500, "#BC71E7");
        addDollarWedge(wedges, 650, "#FC6008");
        addDollarWedge(wedges, 600, "#42B3D6");
        addDollarWedge(wedges, 700, "#E41F17");
        addDollarWedge(wedges, 900, "#FDE308");
        addDollarWedge(wedges, 500, "#52EBA7");

        return wedges;
    }

    private static void addDollarWedge(List<Wedge> wedges, int value, String colorHex) {
        String label = "$" + value;
        String effect = value == 2500 ? "GLITTER" : "";
        wedges.add(new Wedge(label, value, colorHex, textColorFor(colorHex), effect, SLOTS_PER_WEDGE));
    }

    private static void addSpecialWedge(List<Wedge> wedges, String label, String colorHex) {
        String effect;
        if (label != null && label.matches(".*[^0-9].*")) {
            effect = label.replace(" ", "_");
        } else {
            effect = "";
        }

        wedges.add(new Wedge(label, null, colorHex, textColorFor(colorHex), effect, SLOTS_PER_WEDGE));
    }

    private static String textColorFor(String colorHex) {
        return isLightColor(colorHex) ? "#111827" : "#FFFFFF";
    }

    private static boolean isLightColor(String colorHex) {
        String normalized = colorHex.replace("#", "");
        if (normalized.length() != 6) {
            return false;
        }

        int r = Integer.parseInt(normalized.substring(0, 2), 16);
        int g = Integer.parseInt(normalized.substring(2, 4), 16);
        int b = Integer.parseInt(normalized.substring(4, 6), 16);

        int luminance = (int) Math.round((0.2126 * r) + (0.7152 * g) + (0.0722 * b));
        return luminance > 170;
    }
}
