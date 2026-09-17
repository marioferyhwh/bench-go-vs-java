/*
 mario robayo
 Fecha: 17/09/2026
 Descripción: Creación de la tabla de usuarios en el esquema datatest
 */
-- DROP TABLE datatest.user ;
CREATE TABLE datatest.user (
    id UUID NOT NULL DEFAULT gen_random_uuid (),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now (),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now (),
    deleted_at TIMESTAMPTZ,
    email varchar(63) NOT NULL,
    password varchar(256) NOT NULL,
    active bool NOT NULL DEFAULT TRUE,
    CONSTRAINT user_pk PRIMARY KEY (id),
    CONSTRAINT user_uk_email UNIQUE (email)
);

COMMENT ON TABLE datatest.user IS 'Información de usuarios';

-- Column comments
COMMENT ON COLUMN datatest.user.id IS 'Identificador único del usuario';

COMMENT ON COLUMN datatest.user.email IS 'Correo electrónico único del usuario';

COMMENT ON COLUMN datatest.user.password IS 'Contraseña del usuario';

COMMENT ON COLUMN datatest.user.active IS 'Indica si el usuario está activo';