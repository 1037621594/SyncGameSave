package com.example.syncgamesave.pokemon;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;

/** Type information of one Pokemon. All lists hold type indexes into {@code typeNames}. */
@Data
@AllArgsConstructor
public class MatchupReport {
    private PokemonEntry pokemon;
    private List<String> typeNames;
    /** The Pokemon's own types. */
    private List<Integer> types;
    /** Types hit super effectively by moves of the Pokemon's own types. */
    private List<Integer> strongAgainst;
    private List<Integer> weak4;
    private List<Integer> weak2;
    private List<Integer> resist2;
    private List<Integer> resist4;
    private List<Integer> immune;
}
