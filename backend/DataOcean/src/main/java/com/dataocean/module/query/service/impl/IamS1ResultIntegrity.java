package com.dataocean.module.query.service.impl;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Shared integrity checks for rows crossing the S1 result-protection boundary. */
final class IamS1ResultIntegrity {

    private IamS1ResultIntegrity() {}

    /**
     * Require every key returned in a row to have a declared output column.
     * Caller separately verifies that those columns have complete sourceTrace.
     */
    static boolean dataKeysAreCoveredByColumns(Object rawData, Object rawColumns) {
        if (!(rawData instanceof List<?> rows) || !(rawColumns instanceof List<?> columns)) return false;
        Set<String> knownColumns = new HashSet<>();
        for (Object rawColumn : columns) {
            if (!(rawColumn instanceof Map<?, ?> column) || column.get("name") == null) return false;
            knownColumns.add(String.valueOf(column.get("name")).toLowerCase(Locale.ROOT));
        }
        if (knownColumns.isEmpty()) return false;
        for (Object rawRow : rows) {
            if (!(rawRow instanceof Map<?, ?> row)) return false;
            for (Object rawKey : row.keySet()) {
                if (rawKey == null || !knownColumns.contains(String.valueOf(rawKey).toLowerCase(Locale.ROOT))) {
                    return false;
                }
            }
        }
        return true;
    }
}
