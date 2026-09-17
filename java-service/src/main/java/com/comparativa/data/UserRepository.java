package com.comparativa.data;

import java.util.List;

/**
 * Obtiene usuarios de la base. El servicio depende de esta interfaz y no de
 * una conexión concreta, para poder probarlo sin Postgres.
 */
public interface UserRepository {

    List<User> listUsers(int limit);
}
