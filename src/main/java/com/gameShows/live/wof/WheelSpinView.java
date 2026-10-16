package com.gameShows.live.wof;

import java.time.Instant;

/**
 * The most recent spin of the physical wheel. The server picks where it lands and how long it
 * turns; clients animate the wheel to match. Stays in the snapshot after the wheel stops (and
 * across rounds), so a screen that joins late still draws the wheel where it came to rest.
 *
 * @param spinId      goes up by one on every spin, so two spins in a row that land on the same slot
 *                    still count as two separate spins.
 * @param landingSlot the rim slot (0-71) that stops under the pointer.
 * @param durationMs  how long the wheel turns before it comes to rest.
 * @param settlesAt   when the server applies the result. This is a short grace period after the
 *                    wheel stops, so the result never shows up before the wheel has stopped on
 *                    screen. A late-joining screen uses it to work out how much spin is left.
 * @param inMotion    true until the server applies the result. While it's true, no turn action is
 *                    accepted, and {@code lastSpinResult} stays null so the outcome isn't revealed
 *                    early.
 */
public record WheelSpinView(long spinId, int landingSlot, long durationMs, Instant settlesAt, boolean inMotion) {
}
