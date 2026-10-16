package com.gameShows.live.jeopardy.choices;

import java.util.List;

/**
 * A closed set of possible responses - the 50 states, the world's countries, the presidents - that
 * a matching clue's options are drawn from. Loaded from {@code jeopardy/response-pools/*.json}.
 *
 * @param id          stable identifier, also what a clue's {@code responsePool} override names.
 * @param description what the pool holds, for whoever maintains the data file.
 * @param entries     every member, each written the way it should appear as an option.
 */
public record ResponsePool(String id, String description, List<Entry> entries) {

    public ResponsePool {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    /**
     * @param name    how the option reads on screen - the correct option uses this too, so it never
     *                stands out from the distractors by formatting alone.
     * @param group   optional neighbourhood within the pool (a region, an era); distractors come from
     *                the right answer's group first so they're plausible, not giveaways.
     * @param aliases other ways a clue's response may name this entry ("FDR", "Burma").
     */
    public record Entry(String name, String group, List<String> aliases) {

        public Entry {
            aliases = aliases == null ? List.of() : List.copyOf(aliases);
        }
    }
}
