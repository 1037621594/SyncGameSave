package com.example.syncgamesave.pokemon;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class MatchCandidate {
    private PokemonEntry pokemon;
    /** 1.0 for an exact name hit, lower for fuzzy hits. */
    private double score;
    private boolean exact;
}
