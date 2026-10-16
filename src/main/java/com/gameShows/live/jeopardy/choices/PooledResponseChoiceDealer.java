package com.gameShows.live.jeopardy.choices;

import com.gameShows.models.jeopardy.Clue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.stream.Stream;

/**
 * Turns a clue into a pick-one-of-five when its response belongs to a {@link ResponsePool}: the
 * right answer plus four distractors from the same pool, favouring the right answer's own region or
 * era so the wrong options are plausible rather than giveaways.
 *
 * <p>Which pool applies is normally detected from the response itself. A clue can override that via
 * {@link Clue#getResponsePool()}: a pool id forces that pool (settling a response like "Georgia" that
 * two pools claim), and {@value ResponsePoolCatalog#FREE_TEXT} keeps it a typed answer.
 */
public final class PooledResponseChoiceDealer implements ResponseChoiceDealer {

    private static final Logger log = LoggerFactory.getLogger(PooledResponseChoiceDealer.class);

    private final ResponsePoolCatalog catalog;

    public PooledResponseChoiceDealer(ResponsePoolCatalog catalog) {
        this.catalog = catalog;
    }

    @Override
    public Optional<ResponseChoices> deal(Clue clue, Random random) {
        String response = clue.getResponse();
        if (response == null || response.isBlank()) {
            return Optional.empty();
        }
        String override = clue.getResponsePool();
        if (override == null || override.isBlank()) {
            return catalog.match(response)
                    .map(match -> deal(match.pool(), match.entry(), match.entry().name(), random));
        }
        String poolId = override.strip();
        if (ResponsePoolCatalog.FREE_TEXT.equalsIgnoreCase(poolId)) {
            return Optional.empty();
        }
        Optional<ResponsePool> pool = catalog.pool(poolId);
        if (pool.isEmpty()) {
            log.warn("Clue {} names unknown response pool '{}'; it will be answered by typing.", clue.getId(), poolId);
            return Optional.empty();
        }
        // Whoever forced the pool knows the response belongs in it, so a spelling the pool doesn't
        // list is offered exactly as the clue wrote it (add an alias to have it read like the rest).
        ResponsePool.Entry entry = catalog.entry(pool.get(), response).orElse(null);
        String correct = entry != null ? entry.name() : response.strip();
        return Optional.of(deal(pool.get(), entry, correct, random));
    }

    /**
     * @param answer  the right answer's pool entry, or null when the pool doesn't list it.
     * @param correct the right answer as it will read on screen.
     */
    private static ResponseChoices deal(ResponsePool pool, ResponsePool.Entry answer, String correct,
            Random random) {
        String correctKey = ResponsePoolCatalog.key(correct);
        String group = answer == null ? null : answer.group();
        List<String> sameGroup = new ArrayList<>();
        List<String> elsewhere = new ArrayList<>();
        for (ResponsePool.Entry entry : pool.entries()) {
            boolean isTheAnswer = answer != null ? entry == answer
                    : ResponsePoolCatalog.key(entry.name()).equals(correctKey);
            if (isTheAnswer) {
                continue;
            }
            if (group != null && group.equals(entry.group())) {
                sameGroup.add(entry.name());
            } else {
                elsewhere.add(entry.name());
            }
        }
        Collections.shuffle(sameGroup, random);
        Collections.shuffle(elsewhere, random);

        List<String> options = new ArrayList<>(ResponsePoolCatalog.CHOICE_COUNT);
        options.add(correct);
        Stream.concat(sameGroup.stream(), elsewhere.stream())
                .limit(ResponsePoolCatalog.CHOICE_COUNT - 1)
                .forEach(options::add);
        Collections.shuffle(options, random);
        return new ResponseChoices(options, correct);
    }
}
