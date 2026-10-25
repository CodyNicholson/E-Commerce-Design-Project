package com.example.mromirror.api;

import java.util.LinkedHashMap;
import java.util.Map;

final class Json {
    private Json() {}

    /** An ordered JSON object from key/value pairs; unlike Map.of it allows null values. */
    static Map<String, Object> obj(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}
