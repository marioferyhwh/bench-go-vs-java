# Herramienta de estrés

Genera carga contra `GET /data` en Go y en Java para obtener números medidos. Sin esto no se puede afirmar que una tecnología es más rápida que la otra: en este proyecto ninguna afirmación de rendimiento vale sin una corrida que la respalde.

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

`--requests` y `--duration` son excluyentes, y cada una responde una pregunta distinta:

- **`--requests`** fija el trabajo: ambas tecnologías atienden lo mismo, así que comparar cuánto tardaron y cuánto gastaron aísla el **coste por petición**.
- **`--duration`** fija el tiempo: ambas corren los mismos segundos bajo la misma presión, así que su gasto de CPU y de memoria compara el **coste de tenerlas en pie** durante esa ventana. Es también el modo que dice cuántas peticiones aguanta cada una.

Los dos comparan consumo, pero no el mismo consumo. Lo que `--duration` no permite es dividir ese gasto entre peticiones: si una atendió 90.000 y la otra 55.000, la que más gastó pudo hacerlo porque trabajó más, no porque sea más cara. Para eso está `--requests`.

## VUs

`--vus` es **por tecnología**, no un total que se reparta. `--vus 20 --targets go,java` lanza 20 usuarios virtuales contra Go y otros 20 contra Java a la vez: 40 en total.

Se hace así para que cada servicio reciba siempre la misma carga sin importar cuántos objetivos haya en la corrida, y para que dos corridas con el mismo `--vus` sean comparables entre sí.

## Apertura y cierre de la corrida

Cada corrida lleva una espera en cada extremo, y las dos existen por la misma razón: Prometheus raspa cada segundo y no lo hace justo en el instante en que la corrida empieza o termina. Sin esos márgenes, el tráfico de los extremos cae fuera de la franja que el dashboard usa para acumular.

**Al abrir**, k6 marca la corrida como activa y espera 3 segundos antes de lanzar la primera petición. Ese margen le da a Prometheus tiempo de raspar los contadores con la franja ya abierta y sin tráfico todavía, que es el punto de partida de la resta.

Sin esta espera el primer raspado llegaba con peticiones ya servidas, y esas se perdían: una corrida de 5000 peticiones que duraba 13 segundos aparecía en el acumulado como 3400, cuando el raspado iba todavía cada 5 s.

**Al cerrar**, k6 imprime el resumen y publica los resultados de inmediato, y después espera 3 segundos antes de marcar la corrida como apagada, para que el último tramo de tráfico y CPU entre en algún raspado.

Ninguna de las dos esperas retrasa los números: los resultados están en pantalla y en el Pushgateway en cuanto termina la carga. Los dos valores viven en un solo sitio, `ESPERA_APERTURA_SEGUNDOS` y `ESPERA_CIERRE_SEGUNDOS` en `scenarios/config.js`, y son 3 y 3: con el raspado cada segundo, eso da tres raspados de margen a cada lado.

Para leer el acumulado de **una sola** corrida hay que estrechar el rango de tiempo del dashboard hasta que solo la cubra a ella. Si abarca dos, las cifras las suman.

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

Al terminar, k6 imprime un resumen por tecnología —tiempo activo, peticiones por segundo, latencias p50/p95/p99 y fallos— y cierra con el total de la corrida: cuánto duró, cuántas peticiones generó y cuántas fallaron.

```
================================================================
  TOTAL DE LA CORRIDA
    duración   : 11.20 s
    GO         : 2000 peticiones, 0 fallos
    JAVA       : 2000 peticiones, 0 fallos
    TOTAL      : 4000 peticiones, 0 fallos
================================================================
```

Las peticiones van desglosadas por tecnología porque el total suelto no dice cuántas atendió cada una, que es lo que se compara. En modo `requests` las dos salen iguales por diseño; en modo `duration` cada una hace las que puede, y ahí la diferencia es la medición.

Esa duración es de reloj y no coincide con la suma de los tiempos activos de cada tecnología: corren a la vez, y además incluye la espera de apertura.

Después empuja las condiciones y los resultados al Pushgateway, de donde Prometheus los lee. Se ven en el dashboard "Comparativo Go vs Java" de Grafana (http://localhost:3000, admin/admin1), en la sección "Qué se midió".

Cada corrida se guarda con su propio identificador, así que una no borra a la anterior: la tabla del dashboard muestra todas las que caigan dentro del rango de tiempo que se esté viendo, con una fila por corrida y tecnología.

El Pushgateway **no las expira por su cuenta**. Se acumulan hasta que se vacían a mano:

```sh
# Lo más simple: se lleva por delante todo el estado del stack
docker compose down -v
```

Para borrar corridas sueltas hay que ir grupo por grupo, con el identificador de cada una. Un `DELETE` a `/metrics/job/k6_stress` **no sirve**: cada corrida vive en un subgrupo propio y el borrado del grupo padre no los alcanza.

```sh
# Ver qué corridas hay guardadas
curl -s http://localhost:9091/metrics | grep 'stress_run_active{'

# Borrar una por su identificador
curl -X DELETE http://localhost:9091/metrics/job/k6_stress/run_id/1789740547
```

Lo que mide cada panel del dashboard, y cómo interpretarlo, está en la propia descripción de cada panel en Grafana: el icono de información de la esquina.


## Repetir corridas y hacer pausas

Puedes ejecutar varias corridas consecutivas y dejar un intervalo entre ellas. Esto resulta útil para comparar resultados repetibles o para dar tiempo a que los servicios y el sistema recuperen su estado entre mediciones.

- `--repeat 5`: ejecuta la misma configuración cinco veces.
- `--pause 30s`: espera 30 segundos entre corridas. Acepta valores como `10s`, `1m` o `500ms`.

La configuración de cada repetición es idéntica. Cada corrida conserva su propio identificador en el Pushgateway, por lo que puedes analizarlas por separado en Grafana. La pausa no forma parte de la duración ni de las métricas de carga de ninguna corrida.

### Ejemplos

Cinco mediciones sin base de datos, con 30 segundos entre ellas:

```sh
./stress/stress.sh --requests 50000 --repeat 5 --pause 30s
```
```sh
./stress/stress.sh --requests 50000 --repeat 5 --pause 30s --database
```
```sh
./stress/stress.sh --requests 50000 --repeat 5 --pause 30s --database --cold
```