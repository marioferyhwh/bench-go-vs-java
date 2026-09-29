# go-service

La implementación en Go de `GET /data`. Su gemela en Spring Boot vive en [`../java-service/`](../java-service/) y responde exactamente lo mismo.

Go 1.27, biblioteca estándar para el servidor HTTP. Solo dos dependencias directas: `pgx` para Postgres y `client_golang` para las métricas.

## Ejecutar

Normalmente se levanta con el resto del stack desde la carpeta padre:

```sh
docker compose up --build
```

Suelto, contra una base que ya esté corriendo:

```sh
cd go-service
DATABASE_URL="postgres://apidatatest:nueva_contraseña@localhost:5432/postgres?sslmode=disable" go run .
```

| Variable | Para qué | Defecto |
|---|---|---|
| `PORT` | Puerto donde escucha | 8080 |
| `DATABASE_URL` | Cadena de conexión de pgx | — |
| `DB_MAX_OPEN_CONNS` | Tope del pool | 10 |
| `DB_MAX_IDLE_CONNS` | Conexiones ociosas que mantiene | 10 |

Los valores del pool se fijan en `compose.yml`, no en el código, para que las dos tecnologías usen el mismo.

## Tests y comprobaciones

```sh
go test ./...     # obligatorio antes de dar por terminado un cambio
gofmt -l .        # no debe listar nada
go vet ./...
```

## Cómo está organizado

```
main.go                      Arranque: lee el entorno, abre el pool, monta el servidor
internal/data/
  service.go                 La lógica: espera 50 ms o consulta la base
  postgres.go                El repositorio contra Postgres
internal/httpapi/
  handler.go                 Traduce request y response, y registra las métricas
```

La separación entre `data` y `httpapi` no es decorativa: **la espera de 50 ms y el acceso a la base no pueden vivir en el handler.** El handler solo traduce request y response. `data.Service` se prueba sin levantar servidor, y así es como está testeado.

`data.Repository` es una interfaz, de modo que los tests del servicio no necesitan Postgres.

## Detalles que importan para que la comparación sea justa

Estas decisiones existen para que Go y Java midan lo mismo. Cambiarlas en un solo lado reabre un sesgo que costó encontrar:

- **El cronómetro arranca en la primera línea de `ServeHTTP`** y para al terminar de atender, antes de serializar. Java mide la misma ventana desde su filtro. Medir dentro del handler dejaría fuera el enrutado y Java saldría artificialmente más rápido.
- **Los cubos del histograma están fijados a mano** en `LatencyBuckets`, con la misma lista que Java. Un test falla si alguien la cambia solo aquí. Con los cubos por defecto de Prometheus el p99 de Go salía inflado por interpolar sobre cubos anchos.
- **El handler es el único sitio que registra métricas.** Dos registros de la misma respuesta la cuentan dos veces.

Los cubos, en segundos, son estos:

```
0.001  0.002  0.003  0.005  0.0075  0.010  0.015  0.025
0.050  0.075  0.100  0.250  0.500   1.0    2.5    10.0
```

Densos entre 1 y 100 ms, que es donde viven estas latencias, con cola hasta 10 s para los arranques en frío de la JVM.

Que la lista importe tanto tiene una razón concreta: `histogram_quantile` interpola linealmente dentro del cubo donde cae el percentil, así que el error posible es el ancho de ese cubo. Con los once cubos por defecto de Prometheus, un p99 de 6 ms caía en el cubo de 5 a 10 ms —cinco milisegundos de error sobre una latencia de seis— mientras Java resolvía esa zona con cubos de un milisegundo. El p99 de Go salía inflado y el de Java fiel, y la diferencia no venía de las tecnologías.

## Métricas que expone

`GET /metrics` en formato Prometheus:

| Métrica | Qué es |
|---|---|
| `http_requests_total{status,mode}` | Contador de peticiones |
| `http_request_duration_seconds{status,mode}` | Histograma de latencia |
| `process_cpu_seconds_total` | Segundos de CPU del proceso |
| `process_resident_memory_bytes` | Memoria residente |

Las dos últimas las trae `client_golang` de serie. Java tiene que publicarlas a mano para exponer los mismos nombres.

## El contrato

```
GET /data?useDatabase=<true|false>
```

`useDatabase` es opcional; ausente o vacío equivale a `false`. Solo acepta `true` y `false` en minúsculas.

| Modo | Qué hace |
|---|---|
| `false` | Espera 50 ms y responde sin tocar la base, simulando el coste de la consulta |
| `true` | Consulta Postgres y devuelve hasta 100 usuarios |

```json
{
  "source": "database",
  "count": 4,
  "users": [
    { "id": "0f9c...", "email": "admin@datatest.com", "active": true, "createdAt": "2026-09-17T10:00:00Z" }
  ]
}
```

`source` es `"simulation"` o `"database"`. En simulación `count` es `0` y `users` es `[]`, nunca `null`. Las claves van en `camelCase` y `createdAt` en ISO 8601 UTC.

| Caso | Código | Cuerpo |
|---|---|---|
| `useDatabase` con valor inválido | `400` | `{"error":"invalid useDatabase value"}` |
| Método distinto de GET | `405` | `{"error":"method not allowed"}` |
| Base no disponible con `useDatabase=true` | `503` | `{"error":"database unavailable"}` |

El modo simulación nunca devuelve `503`: no depende de la base.

**Este contrato es idéntico al de Java. Si cambia aquí, cambia allí en el mismo PR.** Dos implementaciones que responden distinto dejan de comparar la tecnología y pasan a comparar dos APIs diferentes.

El campo `password` nunca se selecciona ni se devuelve.

Este servicio no sabe nada de las pruebas de carga: su superficie es `/data` y `/metrics`, y nada más.
