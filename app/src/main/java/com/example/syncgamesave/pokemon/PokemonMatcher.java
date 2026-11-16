package com.example.syncgamesave.pokemon;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.regex.Pattern;
import lombok.AllArgsConstructor;

/** Matches OCR text against the Pokemon name list, tolerating a single misread character. */
public class PokemonMatcher {
    private static final Pattern NON_CJK = Pattern.compile("[^\\u4e00-\\u9fff]");
    private static final int MAX_CANDIDATES = 5;

    @AllArgsConstructor
    private static class Indexed {
        private final PokemonEntry entry;
        private final String name;
    }

    private final List<Indexed> officialIndex = new ArrayList<>();
    private final List<Indexed> index = new ArrayList<>();
    private final PokemonData data;

    public PokemonMatcher(PokemonData data) {
        this(data, new LinkedHashMap<>());
    }

    public PokemonMatcher(PokemonData data, Map<String, Integer> aliases) {
        this.data = data;
        for (PokemonEntry entry : data.getPokemon()) {
            String name = normalize(entry.getName());
            if (!name.isEmpty()) officialIndex.add(new Indexed(entry, name));
        }
        setAliases(aliases);
    }

    public synchronized void setAliases(Map<String, Integer> aliases) {
        index.clear();
        index.addAll(officialIndex);
        for (Map.Entry<String, Integer> alias : aliases.entrySet()) {
            PokemonEntry entry = PokemonAliasStore.findById(data.getPokemon(), alias.getValue());
            String name = normalize(alias.getKey());
            if (entry != null && !name.isEmpty()) index.add(new Indexed(entry, name));
        }
    }

    public synchronized List<MatchCandidate> match(List<String> lines) {
        List<String> texts = new ArrayList<>();
        for (String line : lines) {
            String text = normalize(line);
            if (!text.isEmpty()) texts.add(text);
        }
        if (texts.isEmpty()) return new ArrayList<>();

        List<MatchCandidate> exact = new ArrayList<>();
        List<MatchCandidate> fuzzy = new ArrayList<>();
        for (Indexed item : index) {
            int length = item.name.length();
            int allowed = length >= 3 ? 1 : 0;
            int best = Integer.MAX_VALUE;
            for (String text : texts) {
                best = Math.min(best, distance(item.name, text));
                if (best == 0) break;
            }
            if (best == 0) exact.add(new MatchCandidate(item.entry, 1.0, true));
            else if (best <= allowed) fuzzy.add(new MatchCandidate(item.entry, 1.0 - (double) best / length, false));
        }
        List<MatchCandidate> result = exact.isEmpty() ? fuzzy : dropContained(exact);
        result = uniqueByPokemon(result);
        Collections.sort(result, (a, b) -> {
            int byScore = Double.compare(b.getScore(), a.getScore());
            if (byScore != 0) return byScore;
            int byLength = Integer.compare(normalize(b.getPokemon().getName()).length(),
                    normalize(a.getPokemon().getName()).length());
            return byLength != 0 ? byLength : Integer.compare(a.getPokemon().getId(), b.getPokemon().getId());
        });
        return result.size() > MAX_CANDIDATES ? new ArrayList<>(result.subList(0, MAX_CANDIDATES)) : result;
    }

    private static List<MatchCandidate> uniqueByPokemon(List<MatchCandidate> candidates) {
        Map<Integer, MatchCandidate> unique = new LinkedHashMap<>();
        for (MatchCandidate candidate : candidates) {
            MatchCandidate old = unique.get(candidate.getPokemon().getId());
            if (old == null || candidate.getScore() > old.getScore()
                    || (candidate.isExact() && !old.isExact())) unique.put(candidate.getPokemon().getId(), candidate);
        }
        return new ArrayList<>(unique.values());
    }

    /** A name that is only a substring of another matched name is a false hit, so the longer name wins. */
    private static List<MatchCandidate> dropContained(List<MatchCandidate> exact) {
        List<MatchCandidate> kept = new ArrayList<>();
        for (MatchCandidate candidate : exact) {
            String name = normalize(candidate.getPokemon().getName());
            boolean contained = false;
            for (MatchCandidate other : exact) {
                String otherName = normalize(other.getPokemon().getName());
                if (otherName.length() > name.length() && otherName.contains(name)) {
                    contained = true;
                    break;
                }
            }
            if (!contained) kept.add(candidate);
        }
        return kept;
    }

    /** Keeps only Chinese characters, so symbols, digits and Latin text such as HP or Lv are dropped. */
    public static String normalize(String value) {
        return value == null ? "" : NON_CJK.matcher(value).replaceAll("");
    }

    /** Smallest edit distance between the pattern and any substring of the text. */
    private static int distance(String pattern, String text) {
        int m = pattern.length();
        int n = text.length();
        int[] previous = new int[n + 1];
        int[] current = new int[n + 1];
        for (int i = 1; i <= m; i++) {
            current[0] = i;
            for (int j = 1; j <= n; j++) {
                int cost = pattern.charAt(i - 1) == text.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(previous[j] + 1, current[j - 1] + 1), previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        int best = previous[0];
        for (int j = 1; j <= n; j++) best = Math.min(best, previous[j]);
        return best;
    }
}
