# java-service

La implementación en Spring Boot de `GET /data`. Su gemela en Go vive en [`../go-service/`](../go-service/) y responde exactamente lo mismo.

Java 21, Spring Boot 3.3.5, JDBC sobre Postgres y Micrometer para las métricas.

## Ejecutar

Normalmente se levanta con el resto del stack desde la carpeta padre:

```sh
docker compose up --build
```

Suelto, contra una base que ya esté corriendo:

```sh
cd java-service
DATABASE_PASSWORD=nueva_contraseña ./mvnw spring-boot:run
```

| Variable | Para qué | Defecto |
|---|---|---|
| `PORT` | Puerto donde escucha | 8081 |
| `DATABASE_URL` | URL JDBC | `jdbc:postgresql://localhost:5432/postgres` |
| `DATABASE_USER` | Usuario | `apidatatest` |
| `DATABASE_PASSWORD` | Contraseña | — |
| `DB_MAX_OPEN_CONNS` | Tamaño máximo del pool de Hikari | 10 |
| `DB_MAX_IDLE_CONNS` | Conexiones ociosas mínimas | 10 |

Los valores del pool se fijan en `compose.yml`, no en `application.yml`, para que las dos tecnologías usen el mismo.

`initialization-fail-timeout: -1` permite que el servicio arranque sin base: `/data?useDatabase=true` responde `503` en vez de impedir el arranque.

## Tests y comprobaciones

```sh
./mvnw test       # obligatorio antes de dar por terminado un cambio
./mvnw verify
```

## Cómo está organizado

```
DemoApplication.java         Arranque de Spring Boot
data/
  DataService.java           La lógica: espera 50 ms o consulta la base
  PostgresUserRepository.java  El repositorio
  UserRepository.java        La interfaz que permite testear sin base
  DataResult.java, User.java Los tipos de la respuesta
httpapi/
  DataController.java        Traduce request y response
  MetricsFilter.java         Cronometra y cuenta cada petición
  DataMetrics.java           Registro de métricas y cubos del histograma
  ProcessRuntimeMetrics.java CPU y memoria del proceso
  ApiExceptionHandler.java   Traduce excepciones a códigos HTTP
```

Igual que en Go, la lógica vive fuera del controlador: `DataService` se prueba sin levantar el contexto web. El controlador solo traduce request y response.

## Lo que Java tiene que hacer a mano

Aquí está casi todo lo que diferencia este servicio del de Go. Son cosas que Go trae de serie y que, sin ellas, romperían la comparación:

### Las métricas de proceso

Micrometer **no publica ninguna métrica con los nombres que usa Go**: expone la memoria como pools de la JVM y la CPU como `process_cpu_time_ns` en nanosegundos. Sin corregirlo, el dashboard compararía métricas distintas y el acumulado de CPU de Java saldría en cero.

`ProcessRuntimeMetrics` publica a mano las dos:

- `process_resident_memory_bytes`, leída de `/proc/self/status`, la misma fuente que usa Go.
- `process_cpu_seconds_total`, registrada con nombre base `process_cpu` y unidad `seconds`; el registro de Prometheus le añade el sufijo.

### La ventana de medición

El cronómetro vive en `MetricsFilter`, que envuelve la petición completa. **No puede vivir dentro del controlador**: ahí quedarían fuera el enrutado, el binding de parámetros y los filtros de Spring, y Java saldría sistemáticamente más rápido que Go por medir una ventana más corta.

Por la misma razón el filtro es el **único** sitio que registra métricas. Cuando `ApiExceptionHandler` también las registraba, cada 405 se contaba dos veces en Java y una en Go.

### Los cubos del histograma

Están fijados a mano en `DataMetrics.LATENCY_BUCKETS_SECONDS`, con la misma lista que Go, y un test falla si alguien la cambia solo aquí. `publishPercentileHistogram()` genera unos 70 cubos propios que no coinciden con los de Go.

### La validación del parámetro

`useDatabase` se recibe como `String` y se valida a mano. El binding automático de Spring aceptaría `on`, `yes` y `1`, que el contrato rechaza con `400`.

### La ruta de las métricas

`application.yml` remapea el endpoint de Actuator para que las métricas salgan en `/metrics`, igual que en Go, en vez de en `/actuator/prometheus`.

Los tres desalineamientos —cubos distintos, ventanas de medición distintas y respuestas contadas dos veces— se encontraron auditando por qué el p99 de Go parecía alto. Ninguno venía de las tecnologías: los tres venían de la instrumentación. La lista de cubos y su porqué están en el README de [`../go-service/`](../go-service/README.md).

## Frío y caliente

La JVM optimiza su código tras miles de ejecuciones, así que este servicio rinde muy distinto según lleve rato atendiendo peticiones o acabe de arrancar: en las mediciones del proyecto pasa de 1836 a 4577 peticiones por segundo.

**No se calienta antes de medir a propósito.** Si una tecnología necesita calentarse para rendir, ese coste forma parte de lo que se compara. Al leer una corrida hay que saber si fue en frío; `./stress/stress.sh --cold` reinicia los servicios para dejarlo en un estado conocido.

## El contrato

`GET /data?useDatabase=<true|false>` responde exactamente lo mismo que Go, hasta la última clave del JSON. El contrato está escrito en el README de [`../go-service/`](../go-service/README.md#el-contrato) y no se repite aquí a propósito: dos copias se desincronizan.

**Si cambia allí, cambia aquí en el mismo PR.** Dos implementaciones que responden distinto dejan de comparar la tecnología y pasan a comparar dos APIs diferentes.

Este servicio no sabe nada de las pruebas de carga: su superficie es `/data` y `/metrics`, y nada más.
