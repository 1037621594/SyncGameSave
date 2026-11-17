package com.example.syncgamesave.pokemon;

import android.content.Context;
import android.content.SharedPreferences;
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
    private static final String KEY_DEFAULTS_VERSION = "name_aliases_defaults_version";
    /** Raise this whenever {@link #defaults} gains entries so existing installs receive them once. */
    private static final int DEFAULTS_VERSION = 2;
    private static final Type MAP_TYPE = new TypeToken<LinkedHashMap<String, Integer>>() { }.getType();

    private PokemonAliasStore() { }

    public static LinkedHashMap<String, Integer> load(Context context, PokemonData data) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String json = prefs.getString(KEY_ALIASES, null);
        if (json != null) {
            try {
                LinkedHashMap<String, Integer> saved = new Gson().fromJson(json, MAP_TYPE);
                if (saved != null) {
                    LinkedHashMap<String, Integer> result = clean(saved, data);
                    if (prefs.getInt(KEY_DEFAULTS_VERSION, 0) < DEFAULTS_VERSION) {
                        // Add newly shipped defaults once; aliases the user already has are never overwritten.
                        for (Map.Entry<String, Integer> item : defaults(data).entrySet()) {
                            if (!result.containsKey(item.getKey())) result.put(item.getKey(), item.getValue());
                        }
                        save(context, result);
                        prefs.edit().putInt(KEY_DEFAULTS_VERSION, DEFAULTS_VERSION).apply();
                    }
                    return result;
                }
            } catch (JsonParseException ignored) { }
        }
        LinkedHashMap<String, Integer> defaults = defaults(data);
        save(context, defaults);
        prefs.edit().putInt(KEY_DEFAULTS_VERSION, DEFAULTS_VERSION).apply();
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
        LinkedHashMap<String, Integer> result = new LinkedHashMap<>();
        // Community nicknames, old translations and common misspellings. Targets use the official names in pokemon.json.
        addDefaults(result, data, "皮卡丘", "皮神", "电耗子");
        addDefaults(result, data, "喷火龙", "老喷");
        addDefaults(result, data, "妙蛙花", "蛙花");
        addDefaults(result, data, "水箭龟", "水箭");
        addDefaults(result, data, "耿鬼", "老耿");
        addDefaults(result, data, "班基拉斯", "老班", "班班");
        addDefaults(result, data, "烈咬陆鲨", "地龙");
        addDefaults(result, data, "甲贺忍蛙", "水蛙", "忍蛙", "小智蛙");
        addDefaults(result, data, "路卡利欧", "卢卡利欧", "卢卡", "路卡");
        addDefaults(result, data, "沙奈朵", "沙奈");
        addDefaults(result, data, "艾路雷朵", "艾路");
        addDefaults(result, data, "百变怪", "百变");
        addDefaults(result, data, "闪电鸟", "电鸟");
        addDefaults(result, data, "火焰鸟", "火鸟");
        addDefaults(result, data, "急冻鸟", "冰鸟");
        addDefaults(result, data, "水伊布", "水精灵");
        addDefaults(result, data, "雷伊布", "雷精灵");
        addDefaults(result, data, "火伊布", "火精灵");
        addDefaults(result, data, "太阳伊布", "太阳精灵");
        addDefaults(result, data, "月亮伊布", "月亮精灵");
        addDefaults(result, data, "叶伊布", "叶精灵");
        addDefaults(result, data, "冰伊布", "冰精灵");
        addDefaults(result, data, "仙子伊布", "仙子精灵");
        addDefaults(result, data, "洛奇亚", "洛奇");
        addDefaults(result, data, "凤王", "凤凰");
        addDefaults(result, data, "盖欧卡", "海皇牙");
        addDefaults(result, data, "固拉多", "古拉顿", "固拉");
        addDefaults(result, data, "烈空坐", "烈空座", "天空龙");
        addDefaults(result, data, "骑拉帝纳", "鬼龙", "骑拉");
        addDefaults(result, data, "代欧奇希斯", "代欧");
        addDefaults(result, data, "帕路奇亚", "帕路");
        addDefaults(result, data, "阿尔宙斯", "羊驼", "创世神");
        addDefaults(result, data, "无极汰那", "无极");
        addDefaults(result, data, "苍响", "剑狗");
        addDefaults(result, data, "藏玛然特", "盾狗");
        addDefaults(result, data, "太乐巴戈斯", "太乐");
        addDefaults(result, data, "桃歹郎", "桃歹");
        return result;
    }

    /** Adds aliases for the named Pokemon, skipping any alias that is, or is part of, another Pokemon's official name. */
    private static void addDefaults(Map<String, Integer> result, PokemonData data, String officialName, String... aliases) {
        PokemonEntry target = findByName(data.getPokemon(), officialName);
        if (target == null) return;
        for (String alias : aliases) {
            String normalized = PokemonMatcher.normalize(alias);
            boolean clashes = normalized.isEmpty();
            for (PokemonEntry other : data.getPokemon()) {
                if (other.getId() != target.getId() && PokemonMatcher.normalize(other.getName()).contains(normalized)) {
                    clashes = true;
                    break;
                }
            }
            if (!clashes) result.put(normalized, target.getId());
        }
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
