package com.example.syncgamesave.pokemon;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Content of assets/pokemon.json. {@code chart[attacker][defender]} is the damage multiplier. */
@Data
@NoArgsConstructor
public class PokemonData {
    private List<String> types = new ArrayList<>();
    private float[][] chart = new float[0][];
    private List<PokemonEntry> pokemon = new ArrayList<>();
}
