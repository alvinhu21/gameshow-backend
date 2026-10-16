package com.gameShows.live.jeopardy;

import java.util.List;

/**
 * Lenient response matching so players don't have to type an exact "What is ..." response: case,
 * punctuation, a leading "What is"/"Who are" and article, and a typo or two are all forgiven.
 *
 * <p>A clue that needs several responses is answered in one go, the way a contestant would say it -
 * "St. Joseph and Sacramento", "Sacramento &amp; St. Joseph", "St. Joseph, Sacramento" - in any
 * order. Every part has to be there exactly once and nothing else may be: leaving one out, naming
 * one twice, or adding a third is wrong, as it would be on the show.
 */
public final class AnswerMatcher {

    /** The word that joins a multi-part response; "&amp;" is read as it too. */
    private static final String CONNECTOR = "and";
    /** The most a multi-part guess can spend between two parts: " and the ". */
    private static final int MAX_JOIN_LENGTH = " and the ".length();

    private AnswerMatcher() {
    }

    public static boolean isCorrect(String guess, String officialResponse) {
        return isCorrect(guess, AnswerKey.of(officialResponse));
    }

    public static boolean isCorrect(String guess, AnswerKey key) {
        String normalizedGuess = normalize(guess);
        if (normalizedGuess.isEmpty()) {
            return false;
        }
        List<List<String>> parts = key.parts().stream()
                .map(spellings -> spellings.stream().map(AnswerMatcher::normalize).toList())
                .toList();
        if (parts.size() == 1) {
            return matchesAny(normalizedGuess, parts.get(0));
        }
        // The split below is quadratic in the guess's length, and the guess is whatever a client
        // sent. Anything longer than every part at its longest plus the joins between them can't be
        // right anyway, so it's turned away before any of that work.
        if (normalizedGuess.length() > longestPossibleGuess(parts)) {
            return false;
        }
        return namesEveryPart(normalizedGuess.split(" "), parts);
    }

    /**
     * Whether the words divide into runs, one per part, each matching a different part. Every cut
     * between words is tried, so a part may itself hold spaces or an "and" ("Romeo and Juliet").
     */
    private static boolean namesEveryPart(String[] words, List<List<String>> parts) {
        int wordCount = words.length;
        int allParts = (1 << parts.size()) - 1;
        // matching[i][j]: bitmask of the parts that words[i..j) names, worked out on first use.
        int[][] matching = new int[wordCount + 1][wordCount + 1];
        boolean[][] matchingKnown = new boolean[wordCount + 1][wordCount + 1];
        // covers[i][parts]: words[0..i) can be split into exactly that set of parts.
        boolean[][] covers = new boolean[wordCount + 1][allParts + 1];
        covers[0][0] = true;
        for (int start = 0; start < wordCount; start++) {
            for (int used = 0; used < allParts; used++) {
                if (!covers[start][used]) {
                    continue;
                }
                for (int end = start + 1; end <= wordCount; end++) {
                    if (!matchingKnown[start][end]) {
                        matching[start][end] = partsNamedBy(run(words, start, end), parts);
                        matchingKnown[start][end] = true;
                    }
                    int unusedMatches = matching[start][end] & ~used;
                    for (int part = 0; part < parts.size(); part++) {
                        int bit = 1 << part;
                        if ((unusedMatches & bit) != 0) {
                            covers[end][used | bit] = true;
                        }
                    }
                }
            }
        }
        return covers[wordCount][allParts];
    }

    /**
     * The words from start to end as one response. A run after the first drops the "and" joining it
     * to the one before, then its own leading article - the first run's was already dropped by
     * {@link #normalize} along with the "What are".
     */
    private static String run(String[] words, int start, int end) {
        int from = start;
        if (start > 0) {
            if (from < end && words[from].equals(CONNECTOR)) {
                from++;
            }
            if (from < end && isArticle(words[from])) {
                from++;
            }
        }
        return String.join(" ", List.of(words).subList(from, end));
    }

    private static int partsNamedBy(String run, List<List<String>> parts) {
        if (run.isEmpty()) {
            return 0;
        }
        int named = 0;
        for (int part = 0; part < parts.size(); part++) {
            if (matchesAny(run, parts.get(part))) {
                named |= 1 << part;
            }
        }
        return named;
    }

    private static int longestPossibleGuess(List<List<String>> parts) {
        int longest = (parts.size() - 1) * MAX_JOIN_LENGTH;
        for (List<String> spellings : parts) {
            longest += spellings.stream().mapToInt(s -> s.length() + toleranceFor(s)).max().orElse(0);
        }
        return longest;
    }

    private static boolean matchesAny(String guess, List<String> normalizedSpellings) {
        return normalizedSpellings.stream().anyMatch(spelling -> matches(guess, spelling));
    }

    private static boolean matches(String guess, String normalizedSpelling) {
        if (guess.equals(normalizedSpelling)) {
            return true;
        }
        int tolerance = toleranceFor(normalizedSpelling);
        // An edit distance is never less than the difference in length, so most mismatches stop here.
        if (Math.abs(guess.length() - normalizedSpelling.length()) > tolerance) {
            return false;
        }
        return levenshtein(guess, normalizedSpelling) <= tolerance;
    }

    private static int toleranceFor(String normalizedResponse) {
        if (normalizedResponse.length() <= 3) {
            return 0;
        }
        if (normalizedResponse.length() <= 7) {
            return 1;
        }
        return 2;
    }

    private static boolean isArticle(String word) {
        return word.equals("a") || word.equals("an") || word.equals("the");
    }

    private static String normalize(String s) {
        if (s == null) {
            return "";
        }
        String lower = s.toLowerCase().trim();
        lower = lower.replace("&", " " + CONNECTOR + " ");
        // Whatever can separate two responses becomes a space, so "St. Joseph,Sacramento" still
        // reads as two words - full-width commas too, as a Chinese or Japanese keyboard types them.
        // Any other punctuation just goes ("O'Neill" and "ONeill" agree).
        lower = lower.replaceAll("[,;/+，、]", " ");
        lower = lower.replaceAll("[^a-z0-9 ]", "");
        lower = lower.replaceAll("\\s+", " ").trim();
        lower = lower.replaceAll("^((what|who)\\s+(is|are|was|were)|whats|whos)\\s+", "");
        lower = lower.replaceAll("^(an?|the)\\s+", "");
        return lower;
    }

    private static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] curr = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            curr[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }
        return prev[b.length()];
    }
}
