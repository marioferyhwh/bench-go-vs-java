package com.comparativa.data;

/**
 * La consulta a Postgres no pudo realizarse.
 */
public class DatabaseUnavailableException extends RuntimeException {

    public DatabaseUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
