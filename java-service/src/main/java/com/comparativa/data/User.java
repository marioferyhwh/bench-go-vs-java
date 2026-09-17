package com.comparativa.data;

import java.time.OffsetDateTime;

/**
 * Proyección pública de datatest.user. No incluye la columna password.
 */
public record User(String id, String email, boolean active, OffsetDateTime createdAt) {
}
