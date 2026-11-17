package com.dataocean.common;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class TextSearch {
    private TextSearch() {}
    public static String key(String value) { return Normalizer.normalize(value.trim(), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT); }
    public static String pattern(String value) { return "%" + key(value).replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%"; }
    public static List<String> terms(String value) {
        return value == null || value.isBlank() ? List.of() : Arrays.stream(value.trim().split("\\s+")).map(TextSearch::pattern).toList();
    }
}
