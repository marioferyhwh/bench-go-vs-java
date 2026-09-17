package com.comparativa.data;

import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Lee usuarios de datatest.user. La consulta nunca selecciona password.
 */
@Repository
public class PostgresUserRepository implements UserRepository {

    private static final String LIST_USERS_QUERY = """
            SELECT id, email, active, created_at
            FROM datatest.user
            WHERE deleted_at IS NULL
            ORDER BY created_at
            LIMIT ?""";

    private final JdbcTemplate jdbcTemplate;

    public PostgresUserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<User> listUsers(int limit) {
        return jdbcTemplate.query(
                LIST_USERS_QUERY,
                (rs, rowNum) -> new User(
                        rs.getString("id"),
                        rs.getString("email"),
                        rs.getBoolean("active"),
                        rs.getObject("created_at", OffsetDateTime.class)),
                limit);
    }
}
