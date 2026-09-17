/*
 mario robayo
 Fecha: 17/09/2026
 Descripción: Inserción de usuarios adicionales para las pruebas de carga
 */
INSERT INTO datatest.user (email, password, active, created_at)
SELECT
  format('load-user-%s@datatest.com', lpad(users.user_number::text, 3, '0')),
  'PLACEHOLDER_NOT_A_HASH',
  TRUE,
  TIMESTAMPTZ '2026-09-17 10:00:00Z'
    + ((users.user_number - 1) * INTERVAL '1 minute')
FROM generate_series(1, 100) AS users(user_number);