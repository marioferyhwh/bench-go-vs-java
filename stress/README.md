# Herramienta de estrés

Genera carga contra `GET /data` en Go y en Java para obtener los números que exige el principio 7 de la constitución. Sin esto no se puede afirmar que una tecnología es más rápida que la otra.

Usa [k6](https://k6.io/), que corre como un servicio más del compose. El escenario está en `scenarios/data.js`.

## Uso

Con el stack levantado (`docker compose up -d`):

```sh
./stress/stress.sh
```

Sin opciones: 10.000 peticiones por tecnología, sin base de datos.

## Opciones

| Bandera | Qué hace | Defecto |
|---|---|---|
| `--requests N` | Cada tecnología atiende N peticiones | 10000 |
| `--duration T` | La carga dura T (`30s`, `2m`) | — |
| `--database` | Las peticiones consultan Postgres | apagada |
| `--no-database` | Las peticiones esperan 50 ms simulados | — |
| `--targets LISTA` | `go`, `java` o `go,java` | `go,java` |
| `--vus N` | Usuarios virtuales **por tecnología** | 20 |
| `--cold` | Reinicia los servicios y espera antes de lanzar | apagada |
| `--warm` | No reinicia nada | defecto |
| `--help` | Muestra la ayuda | — |

`--requests` y `--duration` son excluyentes, y cada una elige qué se mide:

- **`--requests`** fija el trabajo: ambas tecnologías atienden lo mismo y se compara cuánto tardan y cuánto gastan. Es el modo para medir **eficiencia**.
- **`--duration`** fija el tiempo: cada una hace las peticiones que puede. Es el modo para medir **capacidad**, pero su consumo de CPU no es comparable porque no hicieron el mismo trabajo.

## VUs

`--vus` es **por tecnología**, no un total que se reparta. `--vus 20 --targets go,java` lanza 20 usuarios virtuales contra Go y otros 20 contra Java a la vez: 40 en total.

Se hace así para que cada servicio reciba siempre la misma carga sin importar cuántos objetivos haya en la corrida, y para que dos corridas con el mismo `--vus` sean comparables entre sí.

## Cierre de la corrida

Al terminar la carga, k6 imprime el resumen y publica los resultados de inmediato. Después espera 30 segundos antes de marcar la corrida como apagada, para que Prometheus alcance a raspar el último tramo de tráfico y CPU y el acumulado del dashboard no se quede corto.

Esa espera no retrasa los números: ya están en pantalla y en el Pushgateway cuando empieza. El valor vive en un solo sitio, `ESPERA_CIERRE_SEGUNDOS` en `scenarios/config.js`.

## Ejemplos

```sh
# Comparar eficiencia contra Postgres
./stress/stress.sh --requests 10000 --database

# Ver cuánto aguanta cada tecnología bajo presión
./stress/stress.sh --duration 60s --vus 40

# Medir con los servicios recién arrancados
./stress/stress.sh --requests 10000 --database --cold

# Estresar solo una tecnología
./stress/stress.sh --requests 5000 --targets go
```

## Frío y caliente

La JVM optimiza su código tras miles de ejecuciones, así que Java rinde muy distinto según lleve rato atendiendo peticiones o acabe de arrancar. La diferencia no es pequeña: en las mediciones del proyecto pasa de 1836 a 4577 peticiones por segundo.

No se calienta antes de medir a propósito. Si una tecnología necesita calentarse para rendir, ese coste forma parte de lo que se compara: el escenario real es que no hay tráfico y de golpe llegan N peticiones.

`--cold` reinicia los servicios y espera a que respondan, para que la corrida empiece en un estado conocido en vez de depender de cuándo se reinició por última vez.

## Variables de entorno

El script traduce las banderas a `MODE`, `REQUESTS`, `DURATION`, `VUS`, `TARGETS` y `USE_DATABASE`. Se pueden usar directamente:

```sh
docker compose run --rm -e MODE=requests -e REQUESTS=5000 k6
```

Si una variable ya está en el entorno, el script la respeta; una bandera tiene prioridad sobre ella.

```sh
REQUESTS=3000 ./stress/stress.sh                # 3000 peticiones
REQUESTS=3000 ./stress/stress.sh --requests 500 # 500, gana la bandera
```

Los defectos están escritos en tres sitios que deben coincidir: `stress.sh`, `compose.yml` y `scenarios/data.js`. Al cambiar uno hay que cambiar los tres.

## Resultados

Al terminar, k6 imprime un resumen por tecnología y empuja las condiciones y los resultados al Pushgateway, de donde Prometheus los lee. Se ven en el dashboard "Comparativo Go vs Java" de Grafana (http://localhost:3000, admin/admin), en la sección "Qué se midió".

El contrato completo está en `docs/specs/stress-tool/spec.md`.
