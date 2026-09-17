package com.comparativa.data;

import java.time.Duration;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Lógica del endpoint /data, aislada del transporte HTTP para poder probarla
 * sin levantar el servidor.
 */
@Service
public class DataService {

    /** Espera que reemplaza a la consulta real cuando useDatabase está apagada. */
    public static final Duration SIMULATED_DELAY = Duration.ofMillis(50);

    /** Límite de filas devueltas por la consulta. */
    public static final int MAX_USERS = 100;

    private final UserRepository repository;

    public DataService(UserRepository repository) {
        this.repository = repository;
    }

    /**
     * Devuelve los usuarios de la base cuando useDatabase está prendida, o
     * espera SIMULATED_DELAY y devuelve una lista vacía cuando está apagada.
     */
    public DataResult get(boolean useDatabase) {
        if (!useDatabase) {
            sleepSimulatedDelay();
            return DataResult.simulation();
        }

        try {
            List<User> users = repository.listUsers(MAX_USERS);
            return DataResult.database(users == null ? List.of() : users);
        } catch (RuntimeException e) {
            throw new DatabaseUnavailableException("no se pudo consultar la base de datos", e);
        }
    }

    private void sleepSimulatedDelay() {
        try {
            Thread.sleep(SIMULATED_DELAY.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("simulación interrumpida", e);
        }
    }
}
