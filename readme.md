# Comparativo Go vs Java

Banco de pruebas para medir qué cuesta atender la misma carga en Go y en Spring Boot.

La idea es simple: **la misma API, implementada dos veces**, bajo la misma carga, en la misma máquina, con la misma instrumentación. Lo que cambia es la tecnología; todo lo demás se mantiene igual a propósito, porque si no, los números no comparan nada.

## Levantar el entorno

```sh
docker compose up --build
```

Un solo comando deja todo funcionando: base sembrada, los dos servicios en pie, Prometheus recogiendo y el dashboard de Grafana ya provisionado. **Si hace falta un paso manual, es un fallo**, no una instrucción que falte documentar.

Para empezar de cero:

```sh
docker compose down -v && docker compose up --build
```

## Qué corre

| Servicio | Puerto | Qué hace |
|---|---|---|
| `go-service` | 8080 | La API en Go |
| `java-service` | 8081 | La misma API en Spring Boot |
| `postgres` | 5432 | Datos de prueba, sembrados desde `.sql` versionados |
| `prometheus` | 9090 | Recoge las métricas de los dos servicios |
| `pushgateway` | 9091 | Recibe los resultados de cada corrida de k6 |
| `grafana` | 3000 | Dashboard "Comparativo Go vs Java" (admin/admin1) |
| `k6` | — | No arranca con `up`: se lanza con `./stress/stress.sh` |

## Medir

```sh
./stress/stress.sh --requests 10000 --database
```

Los resultados salen por consola al terminar y quedan en el dashboard de Grafana.

Las opciones y qué mide cada modo están en [`stress/README.md`](stress/README.md).

## Qué hace comparable la medición

Tres decisiones sostienen todo lo demás. Romper cualquiera invalida las cifras:

- **Mismo contrato.** `GET /data?useDatabase=<true|false>` responde el mismo JSON en las dos. Si cambia en una, cambia en las dos en el mismo PR.
- **Mismo techo de máquina.** Los dos servicios tienen el mismo límite de CPU y memoria en `compose.yml`. Sin eso se mide quién acapara más máquina, no quién es más eficiente.
- **Misma instrumentación.** Mismos nombres de métrica, mismos cubos de histograma y la misma ventana de medición. Una diferencia aquí se confunde con una diferencia de rendimiento.

## Dónde está cada cosa

| Carpeta | Qué hay |
|---|---|
| [`go-service/`](go-service/) | La implementación en Go |
| [`java-service/`](java-service/) | La implementación en Spring Boot |
| [`database/`](database/) | Esquema y semilla de Postgres |
| [`stress/`](stress/) | La herramienta de carga (k6) |
| `analitics/` | Configuración de Prometheus y del dashboard |

Cada carpeta tiene su propio README con lo que hay que saber para trabajar en ella.

## Reglas de la casa

Las que más sorprenden a quien llega nuevo:

- **El contrato de `/data` es idéntico en las dos tecnologías.** Si cambia en una, cambia en la otra en el mismo PR. Un contrato que se separa deja de comparar nada.
- **La lógica no vive en el handler HTTP.** Ni la espera de 50 ms ni el acceso a la base: van en una capa aparte, que se prueba sin levantar servidor.
- **Los `.sql` ya mergeados no se editan.** Los cambios van en un archivo nuevo. La razón está en [`database/`](database/README.md).
- **Nada es "más rápido" sin números.** Cualquier afirmación sobre rendimiento se respalda con una corrida de la herramienta de estrés.
- **Un comando levanta el entorno.** Si hace falta un paso manual, está roto.
- **No se añaden dependencias ni servicios sin justificarlo** en el PR. El stack es deliberadamente mínimo.

Al terminar un cambio: ejecuta los tests de las tecnologías que tocaste (`go test ./...`, `./mvnw test`) y pega el resultado. Si tocaste el contrato, comprueba que Go y Java responden igual con la flag encendida y apagada.
