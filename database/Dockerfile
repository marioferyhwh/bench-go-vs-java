FROM postgres:18-alpine3.22

# Copia las semillas SQL en la ruta que PostgreSQL ejecuta al inicializarse
COPY sql/init /docker-entrypoint-initdb.d/