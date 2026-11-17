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
    /** Raise this whenever {@link #defaults} changes so existing installs receive the new entries once. */
    private static final int DEFAULTS_VERSION = 4;
    /** Installs older than this version had the whole saved list replaced by the presets; newer bumps only add. */
    private static final int REPLACED_IN_VERSION = 4;
    private static final Type MAP_TYPE = new TypeToken<LinkedHashMap<String, Integer>>() { }.getType();

    private PokemonAliasStore() { }

    public static LinkedHashMap<String, Integer> load(Context context, PokemonData data) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String json = prefs.getString(KEY_ALIASES, null);
        int version = prefs.getInt(KEY_DEFAULTS_VERSION, 0);
        if (json != null && version >= REPLACED_IN_VERSION) {
            try {
                LinkedHashMap<String, Integer> saved = new Gson().fromJson(json, MAP_TYPE);
                if (saved != null) {
                    LinkedHashMap<String, Integer> result = clean(saved, data);
                    if (version < DEFAULTS_VERSION) {
                        // Add newly shipped presets once; aliases the user already has are never overwritten.
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

    /** Preset aliases: older official/community translations, listed per generation. Targets are the names in pokemon.json. */
    private static LinkedHashMap<String, Integer> defaults(PokemonData data) {
        LinkedHashMap<String, Integer> result = new LinkedHashMap<>();
        // Generation 1
        addDefaults(result, data, "巴大蝶", "巴大蝴");
        addDefaults(result, data, "大比鸟", "比雕");
        addDefaults(result, data, "摩鲁蛾", "末入蛾");
        addDefaults(result, data, "蚊香君", "蚊香蛙");
        addDefaults(result, data, "蚊香泳士", "快泳蛙");
        addDefaults(result, data, "勇基拉", "勇吉拉");
        addDefaults(result, data, "呆壳兽", "呆河马");
        addDefaults(result, data, "刺甲贝", "铁甲贝");
        addDefaults(result, data, "催眠貘", "素利普");
        addDefaults(result, data, "引梦貘人", "素利拍");
        addDefaults(result, data, "霹雳电球", "雷电球");
        addDefaults(result, data, "顽皮雷弹", "顽皮弹");
        addDefaults(result, data, "卡拉卡拉", "可拉可拉");
        addDefaults(result, data, "嘎啦嘎啦", "嘎拉嘎拉");
        addDefaults(result, data, "飞腿郎", "沙瓦郎");
        addDefaults(result, data, "快拳郎", "艾比郎");
        addDefaults(result, data, "独角犀牛", "铁甲犀牛");
        addDefaults(result, data, "钻角犀兽", "铁甲暴龙");
        addDefaults(result, data, "袋兽", "袋龙");
        addDefaults(result, data, "魔墙人偶", "吸盘魔偶");
        addDefaults(result, data, "鸭嘴火兽", "鸭嘴火龙");
        addDefaults(result, data, "凯罗斯", "大甲");
        addDefaults(result, data, "拉普拉斯", "乘龙");
        addDefaults(result, data, "水伊布", "水精灵");
        addDefaults(result, data, "雷伊布", "雷精灵");
        addDefaults(result, data, "火伊布", "火精灵");
        // Generation 2
        addDefaults(result, data, "大竺葵", "大菊花");
        addDefaults(result, data, "圆丝蛛", "线球");
        addDefaults(result, data, "茸茸羊", "绵绵");
        addDefaults(result, data, "树才怪", "胡说树");
        addDefaults(result, data, "蚊香蛙皇", "牛蛙君");
        addDefaults(result, data, "大奶罐", "奶牛坦克");
        addDefaults(result, data, "太阳伊布", "光伊布");
        addDefaults(result, data, "月亮伊布", "月伊布");
        // Generation 3
        addDefaults(result, data, "巨金怪", "钢螃蟹");
        addDefaults(result, data, "暴飞龙", "血翼飞龙");
        addDefaults(result, data, "巨沼怪", "大水怪");
        addDefaults(result, data, "蜥蜴王", "针叶王");
        addDefaults(result, data, "火焰鸡", "火鸡战士");
        addDefaults(result, data, "乐天河童", "荷叶鸭");
        addDefaults(result, data, "优雅猫", "优雅仙子");
        addDefaults(result, data, "美纳斯", "美丽龙");
        addDefaults(result, data, "烈空坐", "裂空座");
        addDefaults(result, data, "固拉多", "古拉顿");
        addDefaults(result, data, "盖欧卡", "海皇牙");
        // Generation 4
        addDefaults(result, data, "花岩怪", "鬼盆栽", "鬼影盆");
        addDefaults(result, data, "罗丝雷朵", "假面玫瑰");
        addDefaults(result, data, "姆克鹰", "椋鹰");
        addDefaults(result, data, "土台龟", "地壳龟");
        addDefaults(result, data, "帝王拿波", "钢帝企鹅");
        addDefaults(result, data, "烈焰猴", "豪火猴");
        addDefaults(result, data, "帕路奇亚", "帕路奇犽");
        addDefaults(result, data, "帝牙卢卡", "迪亚鲁加");
        addDefaults(result, data, "阿尔宙斯", "阿尔修斯", "创世神");
        // Generation 5
        addDefaults(result, data, "炒炒猪", "朝阳猪");
        addDefaults(result, data, "炎武王", "烈焰猪");
        addDefaults(result, data, "大剑鬼", "大剑海獭");
        addDefaults(result, data, "君主蛇", "叶皇蛇");
        addDefaults(result, data, "路卡利欧", "鲁卡力欧");
        addDefaults(result, data, "捷克罗姆", "黑英雄");
        addDefaults(result, data, "莱希拉姆", "白英雄");
        addDefaults(result, data, "酋雷姆", "冰龙");
        // Generation 6
        addDefaults(result, data, "妖火红狐", "魔法狐");
        addDefaults(result, data, "甲贺忍蛙", "忍者蛙");
        addDefaults(result, data, "布里卡隆", "胖胖哈力");
        addDefaults(result, data, "坚盾剑怪", "剑盾怪");
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
