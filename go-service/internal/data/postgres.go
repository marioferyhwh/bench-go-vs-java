package data

import (
	"context"
	"database/sql"
	"fmt"
)

// PostgresRepository lee usuarios de datatest.user.
type PostgresRepository struct {
	db *sql.DB
}

// NewPostgresRepository envuelve un pool de conexiones ya abierto.
func NewPostgresRepository(db *sql.DB) *PostgresRepository {
	return &PostgresRepository{db: db}
}

// listUsersQuery nunca selecciona la columna password.
const listUsersQuery = `
SELECT id, email, active, created_at
FROM datatest.user
WHERE deleted_at IS NULL
ORDER BY created_at
LIMIT $1`

// ListUsers devuelve hasta limit usuarios no borrados.
func (r *PostgresRepository) ListUsers(ctx context.Context, limit int) ([]User, error) {
	rows, err := r.db.QueryContext(ctx, listUsersQuery, limit)
	if err != nil {
		return nil, fmt.Errorf("consultando usuarios: %w", err)
	}
	defer rows.Close()

	users := make([]User, 0, limit)
	for rows.Next() {
		var u User
		if err := rows.Scan(&u.ID, &u.Email, &u.Active, &u.CreatedAt); err != nil {
			return nil, fmt.Errorf("leyendo fila de usuario: %w", err)
		}
		users = append(users, u)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("recorriendo usuarios: %w", err)
	}

	return users, nil
}
