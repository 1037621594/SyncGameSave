package com.example.syncgamesave.storage;

import com.example.syncgamesave.model.PathMapping;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

public final class PathRules {
    private PathRules() { }

    public static String prefix(String input) {
        String value = input.trim().replace('\\', '/');
        while (value.startsWith("/")) value = value.substring(1);
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        validateRelative(value);
        return value + "/";
    }

    public static void validateRelative(String value) {
        if (value == null || value.isEmpty() || value.startsWith("/") || value.indexOf('\\') >= 0
                || value.indexOf('\0') >= 0) throw new IllegalArgumentException("路径为空或包含非法字符");
        for (int i = 0; i < value.length(); i++) if (Character.isISOControl(value.charAt(i)))
            throw new IllegalArgumentException("路径不能包含控制字符");
        for (String segment : value.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals(".."))
                throw new IllegalArgumentException("路径不能包含空段、. 或 ..");
        }
    }

    public static String formats(String input) {
        Set<String> values = new LinkedHashSet<>();
        for (String part : input.toLowerCase(Locale.ROOT).replace('，', ',').split("[,;\\s]+")) {
            String value = part.trim();
            if (value.startsWith(".")) value = value.substring(1);
            if (value.equals("*")) return "*";
            if (!value.isEmpty()) {
                if (!value.matches("[a-z0-9_-]+")) throw new IllegalArgumentException("文件格式只支持扩展名，例如 sav,srm,state 或 *");
                values.add(value);
            }
        }
        if (values.isEmpty()) throw new IllegalArgumentException("请填写文件格式");
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (result.length() > 0) result.append(',');
            result.append(value);
        }
        return result.toString();
    }

    public static boolean matches(PathMapping mapping, String relative) {
        validateRelative(relative);
        if (!mapping.isRecursive() && relative.contains("/")) return false;
        if (mapping.getFormats().equals("*")) return true;
        String name = relative.substring(relative.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return false;
        String extension = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        for (String format : mapping.getFormats().split(",")) if (format.equals(extension)) return true;
        return false;
    }

    public static boolean conflicts(PathMapping first, PathMapping second) {
        String a = first.getRemotePrefix();
        String b = second.getRemotePrefix();
        if (!a.startsWith(b) && !b.startsWith(a)) return false;
        if (a.length() != b.length()) {
            PathMapping outer = a.length() < b.length() ? first : second;
            if (!outer.isRecursive()) return false;
        }
        if (first.getFormats().equals("*") || second.getFormats().equals("*")) return true;
        for (String x : first.getFormats().split(","))
            for (String y : second.getFormats().split(",")) if (x.equals(y)) return true;
        return false;
    }
}

