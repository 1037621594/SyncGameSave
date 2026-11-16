package com.example.syncgamesave.pokemon;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

public final class TypeMatchups {
    private TypeMatchups() { }

    public static MatchupReport analyze(PokemonData data, PokemonEntry entry) {
        float[][] chart = data.getChart();
        int count = data.getTypes().size();
        List<Integer> own = new ArrayList<>();
        for (int type : entry.getTypes()) if (type >= 0 && type < count) own.add(type);

        Set<Integer> strong = new TreeSet<>();
        for (int attack : own)
            for (int target = 0; target < count; target++)
                if (chart[attack][target] > 1f) strong.add(target);

        List<Integer> weak4 = new ArrayList<>();
        List<Integer> weak2 = new ArrayList<>();
        List<Integer> resist2 = new ArrayList<>();
        List<Integer> resist4 = new ArrayList<>();
        List<Integer> immune = new ArrayList<>();
        for (int attacker = 0; attacker < count; attacker++) {
            float multiplier = 1f;
            for (int defend : own) multiplier *= chart[attacker][defend];
            if (multiplier == 0f) immune.add(attacker);
            else if (multiplier >= 4f) weak4.add(attacker);
            else if (multiplier >= 2f) weak2.add(attacker);
            else if (multiplier <= 0.25f) resist4.add(attacker);
            else if (multiplier < 1f) resist2.add(attacker);
        }
        return new MatchupReport(entry, new ArrayList<>(data.getTypes()), own, new ArrayList<>(strong),
                weak4, weak2, resist2, resist4, immune);
    }
}
