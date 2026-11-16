package com.example.syncgamesave.pokemon;

import lombok.Data;
import lombok.NoArgsConstructor;

/** One Pokemon from the bundled data file. {@code types} are indexes into {@link PokemonData#getTypes()}. */
@Data
@NoArgsConstructor
public class PokemonEntry {
    private int id;
    private String name;
    private int[] types = new int[0];
}
