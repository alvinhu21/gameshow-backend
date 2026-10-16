package com.gameShows.live.session;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Names for AI seats the host leaves blank. The scoreboard's AI badge marks them, so no suffix. */
final class AiNames {

    private static final List<String> POOL = List.of(
            "Watson", "Ada", "Turing", "Nova", "Cortex", "Byte", "Pixel", "Echo");

    private AiNames() {
    }

    /** A random pool name nobody at the table uses yet ({@code takenLowercase} holds lower-cased
     *  names); once the pool runs dry, "Bot 1", "Bot 2", ... */
    static String unused(Set<String> takenLowercase) {
        List<String> shuffled = new ArrayList<>(POOL);
        Collections.shuffle(shuffled);
        for (String name : shuffled) {
            if (!takenLowercase.contains(name.toLowerCase(Locale.ROOT))) {
                return name;
            }
        }
        for (int i = 1; ; i++) {
            String name = "Bot " + i;
            if (!takenLowercase.contains(name.toLowerCase(Locale.ROOT))) {
                return name;
            }
        }
    }
}
