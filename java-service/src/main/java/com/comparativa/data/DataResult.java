package com.comparativa.data;

import java.util.List;

/**
 * Respuesta del endpoint /data.
 */
public record DataResult(String source, int count, List<User> users) {

    public static DataResult simulation() {
        return new DataResult("simulation", 0, List.of());
    }

    public static DataResult database(List<User> users) {
        return new DataResult("database", users.size(), users);
    }
}
