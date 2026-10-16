package com.gameShows.live.jeopardy.choices;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.InputStream;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Every response pool, indexed so a clue's response can be looked up however the clue happens to
 * write it - any case, accents or none, "Harry S Truman" or "Harry S. Truman", "Gambia" or
 * "The Gambia". Immutable once built, so one instance safely serves every game at once.
 *
 * <p>The data is validated up front: a pool that couldn't deal a full set of choices, or two
 * entries that would read as the same response, stops the application from starting rather than
 * surfacing mid-game.
 */
public final class ResponsePoolCatalog {

    /** The {@code responsePool} override that keeps a clue a typed answer even when its response matches a pool. */
    public static final String FREE_TEXT = "none";
    /** Options dealt per list clue: the right answer plus four distractors. */
    public static final int CHOICE_COUNT = 5;

    static final String POOL_LOCATION = "classpath*:jeopardy/response-pools/*.json";

    private static final Logger log = LoggerFactory.getLogger(ResponsePoolCatalog.class);

    /** One pool's claim on a response. */
    public record Match(ResponsePool pool, ResponsePool.Entry entry) {
    }

    private final Map<String, ResponsePool> poolsById = new LinkedHashMap<>();
    private final Map<String, Map<String, ResponsePool.Entry>> entriesByKeyByPool = new HashMap<>();
    private final Map<String, List<Match>> matchesByKey = new HashMap<>();

    public ResponsePoolCatalog(List<ResponsePool> pools) {
        for (ResponsePool pool : pools) {
            requireValidId(pool);
            Map<String, ResponsePool.Entry> entriesByKey = indexEntries(pool);
            poolsById.put(pool.id(), pool);
            entriesByKeyByPool.put(pool.id(), entriesByKey);
            entriesByKey.forEach((key, entry) ->
                    matchesByKey.computeIfAbsent(key, k -> new ArrayList<>()).add(new Match(pool, entry)));
        }
    }

    /** Reads every {@code jeopardy/response-pools/*.json} on the classpath - adding a pool is just adding a file. */
    public static ResponsePoolCatalog fromClasspath(ObjectMapper objectMapper) throws IOException {
        Resource[] resources = new PathMatchingResourcePatternResolver().getResources(POOL_LOCATION);
        // Strict on purpose: a misspelt field in a hand-edited data file should fail loudly.
        ObjectReader reader = objectMapper.readerFor(ResponsePool.class)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        List<ResponsePool> pools = new ArrayList<>();
        for (Resource resource : sortedByFilename(resources)) {
            try (InputStream in = resource.getInputStream()) {
                pools.add(reader.readValue(in));
            } catch (IOException e) {
                throw new IOException("Couldn't read Jeopardy response pool " + resource.getFilename(), e);
            }
        }
        if (pools.isEmpty()) {
            log.warn("No Jeopardy response pools found at {} - every clue will be answered by typing.", POOL_LOCATION);
        }
        ResponsePoolCatalog catalog = new ResponsePoolCatalog(pools);
        log.info("Loaded {} Jeopardy response pools: {}", pools.size(), catalog.poolsById.keySet());
        return catalog;
    }

    /**
     * The pool a response belongs to, if exactly one claims it. A response more than one pool claims
     * ("Georgia": a state and a country) is deliberately not guessed at - it stays a typed answer
     * unless the clue names its pool.
     */
    public Optional<Match> match(String response) {
        List<Match> matches = matchesByKey.getOrDefault(key(response), List.of());
        return matches.size() == 1 ? Optional.of(matches.get(0)) : Optional.empty();
    }

    public Optional<ResponsePool> pool(String id) {
        return Optional.ofNullable(poolsById.get(id));
    }

    /** This pool's entry for a response, matched by name or alias. */
    public Optional<ResponsePool.Entry> entry(ResponsePool pool, String response) {
        return Optional.ofNullable(entriesByKeyByPool.getOrDefault(pool.id(), Map.of()).get(key(response)));
    }

    public Collection<ResponsePool> pools() {
        return poolsById.values();
    }

    /**
     * The form two spellings of one response share: accents folded, case and punctuation dropped,
     * "&amp;" read as "and", and a leading "The" ignored.
     */
    static String key(String text) {
        if (text == null) {
            return "";
        }
        String unaccented = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        String words = unaccented.toLowerCase(Locale.ROOT)
                // Apostrophes join the letters around them, so "Sana'a" and "Sanaa" agree.
                .replaceAll("['`’ʻ]", "")
                .replace("&", " and ")
                .replaceAll("[^a-z0-9]+", " ")
                .strip();
        return words.startsWith("the ") ? words.substring(4) : words;
    }

    private void requireValidId(ResponsePool pool) {
        String id = pool.id();
        if (id == null || id.isBlank() || !id.equals(id.strip())) {
            throw new IllegalStateException("A Jeopardy response pool needs an id without surrounding spaces.");
        }
        if (FREE_TEXT.equalsIgnoreCase(id)) {
            throw new IllegalStateException("'" + FREE_TEXT + "' is reserved for clues answered by typing.");
        }
        if (poolsById.containsKey(id)) {
            throw new IllegalStateException("Duplicate Jeopardy response pool id '" + id + "'.");
        }
        if (pool.entries().size() < CHOICE_COUNT) {
            throw new IllegalStateException("Response pool '" + id + "' needs at least " + CHOICE_COUNT
                    + " entries to deal a full set of choices, but has " + pool.entries().size() + ".");
        }
    }

    private static Map<String, ResponsePool.Entry> indexEntries(ResponsePool pool) {
        Map<String, ResponsePool.Entry> entriesByKey = new HashMap<>();
        for (ResponsePool.Entry entry : pool.entries()) {
            if (entry.name() == null || entry.name().isBlank()) {
                throw new IllegalStateException("Response pool '" + pool.id() + "' has an entry with no name.");
            }
            List<String> spellings = new ArrayList<>(entry.aliases());
            spellings.add(0, entry.name());
            for (String spelling : spellings) {
                String key = key(spelling);
                if (key.isEmpty()) {
                    throw new IllegalStateException("Response pool '" + pool.id() + "' has a blank alias for "
                            + entry.name() + ".");
                }
                ResponsePool.Entry existing = entriesByKey.putIfAbsent(key, entry);
                // Identity, not equals(): an entry listed twice is exactly the duplicate to catch.
                if (existing != null && existing != entry) {
                    throw new IllegalStateException("Response pool '" + pool.id() + "' can't tell " + existing.name()
                            + " from " + entry.name() + " - both read as '" + spelling + "'.");
                }
            }
        }
        return entriesByKey;
    }

    private static List<Resource> sortedByFilename(Resource[] resources) {
        return Arrays.stream(resources)
                .sorted(Comparator.comparing(resource -> String.valueOf(resource.getFilename())))
                .toList();
    }
}
