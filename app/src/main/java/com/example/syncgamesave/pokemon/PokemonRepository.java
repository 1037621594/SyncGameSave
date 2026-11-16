package com.example.syncgamesave.pokemon;

import android.content.Context;
import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

public final class PokemonRepository {
    private static final String ASSET = "pokemon.json";

    private PokemonRepository() { }

    public static PokemonData load(Context context) throws IOException {
        try (Reader reader = new InputStreamReader(context.getAssets().open(ASSET), StandardCharsets.UTF_8)) {
            PokemonData data = new Gson().fromJson(reader, PokemonData.class);
            if (data == null || data.getPokemon().isEmpty() || data.getTypes().isEmpty()
                    || data.getChart().length != data.getTypes().size())
                throw new IOException("pokemon.json 内容无效，请重新生成");
            return data;
        } catch (FileNotFoundException e) {
            throw new IOException("缺少 assets/pokemon.json，请先运行 tools\\generate-pokemon-data.ps1 并重新安装", e);
        } catch (JsonParseException e) {
            throw new IOException("pokemon.json 解析失败，请重新生成", e);
        }
    }
}
