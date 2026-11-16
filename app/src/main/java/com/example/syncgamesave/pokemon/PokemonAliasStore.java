package com.example.syncgamesave.pokemon;

import android.content.Context;
import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Stores user editable names used in addition to the official Pokemon names. */
public final class PokemonAliasStore {
    private static final String PREFS = "pokemon_tool";
    private static final String KEY_ALIASES = "name_aliases";
    private static final Type MAP_TYPE = new TypeToken<LinkedHashMap<String, Integer>>() { }.getType();

    private PokemonAliasStore() { }

    public static LinkedHashMap<String, Integer> load(Context context, PokemonData data) {
        String json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ALIASES, null);
        if (json != null) {
            try {
                LinkedHashMap<String, Integer> saved = new Gson().fromJson(json, MAP_TYPE);
                if (saved != null) return clean(saved, data);
            } catch (JsonParseException ignored) { }
        }
        LinkedHashMap<String, Integer> defaults = defaults(data);
        save(context, defaults);
        return defaults;
    }

    public static void save(Context context, Map<String, Integer> aliases) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_ALIASES, new Gson().toJson(aliases, MAP_TYPE)).apply();
    }

    private static LinkedHashMap<String, Integer> clean(Map<String, Integer> source, PokemonData data) {
        LinkedHashMap<String, Integer> result = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> item : source.entrySet()) {
            String alias = PokemonMatcher.normalize(item.getKey());
            if (alias.isEmpty() || item.getValue() == null || findById(data.getPokemon(), item.getValue()) == null) continue;
            result.put(alias, item.getValue());
        }
        return result;
    }

    private static LinkedHashMap<String, Integer> defaults(PokemonData data) {
        LinkedHashMap<String, String> names = new LinkedHashMap<>();
        names.put("皮神", "皮卡丘");
        names.put("老喷", "喷火龙");
        names.put("老班", "班基拉斯");
        names.put("地龙", "烈咬陆鲨");
        names.put("水蛙", "甲贺忍蛙");
        names.put("忍蛙", "甲贺忍蛙");
        names.put("小智蛙", "甲贺忍蛙");
        names.put("鬼龙", "骑拉帝纳");
        names.put("海皇牙", "盖欧卡");
        names.put("古拉顿", "固拉多");
        names.put("天空龙", "烈空座");
        names.put("电鸟", "闪电鸟");
        names.put("火鸟", "火焰鸟");
        names.put("冰鸟", "急冻鸟");
        names.put("羊驼", "阿尔宙斯");
        names.put("创世神", "阿尔宙斯");
        names.put("剑狗", "苍响");
        names.put("盾狗", "藏玛然特");
        LinkedHashMap<String, Integer> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> item : names.entrySet()) {
            PokemonEntry target = findByName(data.getPokemon(), item.getValue());
            if (target != null) result.put(item.getKey(), target.getId());
        }
        return result;
    }

    public static PokemonEntry findById(List<PokemonEntry> pokemon, int id) {
        for (PokemonEntry item : pokemon) if (item.getId() == id) return item;
        return null;
    }

    public static PokemonEntry findByName(List<PokemonEntry> pokemon, String name) {
        String wanted = PokemonMatcher.normalize(name);
        for (PokemonEntry item : pokemon) if (PokemonMatcher.normalize(item.getName()).equals(wanted)) return item;
        return null;
    }
}
