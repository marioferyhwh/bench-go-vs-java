#!/bin/sh
# Lanza una prueba de carga contra los servicios Go y Java.
#
# Traduce banderas legibles a las variables de entorno que lee el compose.
# Para cada parámetro se toma el primer valor que exista: la bandera, la
# variable ya exportada en el entorno, y por último el defecto.

set -eu

# Los defectos viven también en compose.yml y en scenarios/data.js: los tres
# caminos de entrada deben coincidir.
DEFAULT_MODE=requests
DEFAULT_REQUESTS=10000
DEFAULT_DURATION=30s
DEFAULT_VUS=20
DEFAULT_TARGETS=go,java
DEFAULT_USE_DATABASE=false
# Solo los usa este script: k6 no sabe que hay repeticiones, cada una es una
# corrida completa con su propio run_id.
DEFAULT_REPEAT=1
DEFAULT_PAUSE=0

# Raíz del compose, a partir de la ubicación del script y no del directorio
# actual, para poder lanzarlo desde cualquier sitio.
ROOT=$(cd "$(dirname "$0")/.." && pwd)

uso() {
  cat <<'FIN'
Uso: stress.sh [opciones]

Lanza una prueba de carga contra GET /data en Go y Java.
Sin opciones: 10.000 peticiones por tecnología, sin base de datos.

Opciones:
  --requests N        Cada tecnología atiende N peticiones (mide eficiencia)
  --duration T        La carga dura T, p. ej. 30s o 2m (mide capacidad)
  --database          Las peticiones consultan Postgres
  --no-database       Las peticiones esperan 50 ms simulando la consulta
  --targets LISTA     go, java o go,java          (defecto: go,java)
  --vus N             Usuarios virtuales por tecnología (defecto: 20)
  --cold              Reinicia los servicios y espera antes de lanzar
  --warm              No reinicia nada             (defecto)
  --repeat N          Lanza la misma corrida N veces seguidas (defecto: 1)
  --pause T           Espera T entre una corrida y la siguiente, en
                      segundos o con unidad: 30, 30s, 2m (defecto: 0)
  --help              Muestra esta ayuda

--vus es por tecnología, no un total que se reparta: --vus 20 --targets go,java
lanza 20 contra Go y 20 contra Java a la vez, 40 en total.

--requests y --duration son excluyentes: una fija el trabajo, la otra el tiempo.

--repeat repite la corrida con los mismos parámetros; cada repetición es una
corrida aparte, con su propio run_id y su fila en el dashboard. Con --cold los
servicios se reinician antes de cada repetición, no solo antes de la primera.

Ejemplos:
  stress.sh --requests 10000 --database     Comparar eficiencia contra Postgres
  stress.sh --duration 60s --vus 40         Ver cuánto aguanta cada tecnología
  stress.sh --requests 10000 --cold         Medir con los servicios recién arrancados
  stress.sh --requests 50000 --database --repeat 5 --pause 30s
                                            Cinco corridas iguales, 30 s entre ellas

Las variables de entorno (MODE, REQUESTS, DURATION, VUS, TARGETS,
USE_DATABASE, REPEAT, PAUSE) se respetan si están puestas; las banderas tienen prioridad.
FIN
}

error() {
  echo "Error: $1" >&2
  echo "Prueba con: $(basename "$0") --help" >&2
  exit 1
}

# Comprueba que una bandera recibió su valor y que no es otra bandera.
valor_de() {
  bandera=$1
  valor=${2:-}
  if [ -z "$valor" ]; then
    error "$bandera necesita un valor."
  fi
  case $valor in
    --*) error "$bandera necesita un valor, pero recibió la bandera $valor." ;;
  esac
  echo "$valor"
}

entero_positivo() {
  bandera=$1
  valor=$2
  case $valor in
    '' | *[!0-9]*) error "$bandera debe ser un entero mayor que 0, recibido: \"$valor\"." ;;
  esac
  if [ "$valor" -le 0 ]; then
    error "$bandera debe ser un entero mayor que 0, recibido: \"$valor\"."
  fi
}

# Se valida aquí y no solo en k6 porque el valor viaja como etiqueta al
# Pushgateway: un carácter suelto rompería el formato y el empuje se perdería.
duracion_valida() {
  bandera=$1
  valor=$2
  if ! echo "$valor" | grep -Eq '^([0-9]+(\.[0-9]+)?(ns|us|ms|s|m|h))+$'; then
    error "$bandera debe ser una duración de k6 (30s, 2m, 1h30m), recibido: \"$valor\"."
  fi
}

# Acepta segundos a secas (30) o con unidad s o m (30s, 2m) y devuelve
# segundos, que es lo que entiende sleep en cualquier sistema POSIX.
a_segundos() {
  bandera=$1
  valor=$2
  case $valor in
    *m) numero=${valor%m}; factor=60 ;;
    *s) numero=${valor%s}; factor=1 ;;
    *) numero=$valor; factor=1 ;;
  esac
  case $numero in
    '' | *[!0-9]*) error "$bandera debe ser un tiempo en segundos o con unidad (30, 30s, 2m), recibido: \"$valor\"." ;;
  esac
  echo $((numero * factor))
}

# Los mismos objetivos que conoce scenarios/config.js. Validarlos aquí evita
# arrancar el contenedor para que falle dentro.
objetivos_validos() {
  bandera=$1
  lista=$2
  IFS=,
  for nombre in $lista; do
    case $nombre in
      go | java) ;;
      '') error "$bandera tiene un objetivo vacío: \"$lista\"." ;;
      *) error "$bandera: objetivo desconocido \"$nombre\". Objetivos válidos: go, java." ;;
    esac
  done
  unset IFS
}

# Lo que pidan las banderas. Vacío significa "no se pidió".
flag_mode=
flag_requests=
flag_duration=
flag_vus=
flag_targets=
flag_use_database=
flag_repeat=
flag_pause=
cold=false

while [ $# -gt 0 ]; do
  case $1 in
    --requests)
      if [ "$flag_mode" = duration ]; then
        error "--requests y --duration son excluyentes: elige fijar el trabajo o el tiempo."
      fi
      flag_requests=$(valor_de "$1" "${2:-}")
      entero_positivo --requests "$flag_requests"
      flag_mode=requests
      shift 2
      ;;
    --duration)
      if [ "$flag_mode" = requests ]; then
        error "--requests y --duration son excluyentes: elige fijar el trabajo o el tiempo."
      fi
      flag_duration=$(valor_de "$1" "${2:-}")
      duracion_valida --duration "$flag_duration"
      flag_mode=duration
      shift 2
      ;;
    --vus)
      flag_vus=$(valor_de "$1" "${2:-}")
      entero_positivo --vus "$flag_vus"
      shift 2
      ;;
    --targets)
      flag_targets=$(valor_de "$1" "${2:-}")
      objetivos_validos --targets "$flag_targets"
      shift 2
      ;;
    --database)
      if [ "$flag_use_database" = false ]; then
        error "--database y --no-database son excluyentes."
      fi
      flag_use_database=true
      shift
      ;;
    --no-database)
      if [ "$flag_use_database" = true ]; then
        error "--database y --no-database son excluyentes."
      fi
      flag_use_database=false
      shift
      ;;
    --cold)
      cold=true
      shift
      ;;
    --warm)
      cold=false
      shift
      ;;
    --repeat)
      flag_repeat=$(valor_de "$1" "${2:-}")
      entero_positivo --repeat "$flag_repeat"
      shift 2
      ;;
    --pause)
      flag_pause=$(valor_de "$1" "${2:-}")
      a_segundos --pause "$flag_pause" >/dev/null
      shift 2
      ;;
    --help | -h)
      uso
      exit 0
      ;;
    *)
      error "Bandera desconocida: $1"
      ;;
  esac
done

# Precedencia: bandera, entorno, defecto.
mode=${flag_mode:-${MODE:-$DEFAULT_MODE}}
requests=${flag_requests:-${REQUESTS:-$DEFAULT_REQUESTS}}
duration=${flag_duration:-${DURATION:-$DEFAULT_DURATION}}
vus=${flag_vus:-${VUS:-$DEFAULT_VUS}}
targets=${flag_targets:-${TARGETS:-$DEFAULT_TARGETS}}
use_database=${flag_use_database:-${USE_DATABASE:-$DEFAULT_USE_DATABASE}}
repeat=${flag_repeat:-${REPEAT:-$DEFAULT_REPEAT}}
pause=${flag_pause:-${PAUSE:-$DEFAULT_PAUSE}}

# Un valor que llegó por el entorno no pasó por la validación de su bandera, y
# aun así acaba en el contenedor y en las etiquetas del Pushgateway.
entero_positivo VUS "$vus"
entero_positivo REPEAT "$repeat"
pausa_segundos=$(a_segundos PAUSE "$pause")
objetivos_validos TARGETS "$targets"
if [ "$mode" = requests ]; then
  entero_positivo REQUESTS "$requests"
else
  duracion_valida DURATION "$duration"
fi

case $use_database in
  true | false) ;;
  *) error "USE_DATABASE debe ser \"true\" o \"false\", recibido: \"$use_database\"." ;;
esac

case $mode in
  requests | duration) ;;
  *) error "MODE debe ser \"requests\" o \"duration\", recibido: \"$mode\"." ;;
esac

if [ "$mode" = requests ]; then
  carga="$requests peticiones por tecnología"
else
  carga="duración $duration"
fi

# Espera a que un servicio responda, en vez de dormir un tiempo fijo que en una
# máquina lenta se queda corto y en una rápida sobra.
esperar_servicio() {
  nombre=$1
  puerto=$2
  intentos=60

  while [ "$intentos" -gt 0 ]; do
    if curl -sf -o /dev/null "http://localhost:$puerto/data"; then
      return 0
    fi
    intentos=$((intentos - 1))
    sleep 1
  done

  error "$nombre no respondió tras 60 segundos. Revisa: docker compose logs $nombre"
}

# Reinicia los servicios estresados y espera a que respondan.
reiniciar_en_frio() {
  echo "Reiniciando servicios para medir en frío..."
  docker compose -f "$ROOT/compose.yml" restart go-service java-service

  # Nombre exacto y no substring: un objetivo futuro que contuviera "go"
  # dispararía la espera del servicio equivocado.
  IFS=,
  for nombre in $targets; do
    case $nombre in
      go) esperar_servicio go-service 8080 ;;
      java) esperar_servicio java-service 8081 ;;
    esac
  done
  unset IFS

  echo "Servicios listos."
}

hora() {
  date -u +%Y-%m-%dT%H:%M:%SZ
}

echo "Lanzando: modo=$mode | $carga | base de datos=$use_database | objetivos=$targets | $vus VUs por tecnología"
if [ "$repeat" -gt 1 ]; then
  echo "Repeticiones: $repeat | pausa entre corridas: ${pausa_segundos} s | en frío: $cold"
fi

# Se construye una vez para toda la tanda: reconstruir en cada repetición solo
# añadiría espera entre corridas.
echo "Construyendo imágenes antes del run..."
docker compose -f "$ROOT/compose.yml" build k6

inicio_tanda=$(hora)
corrida=1
while [ "$corrida" -le "$repeat" ]; do
  if [ "$corrida" -gt 1 ] && [ "$pausa_segundos" -gt 0 ]; then
    echo "Pausa de ${pausa_segundos} s antes de la siguiente corrida..."
    sleep "$pausa_segundos"
  fi

  if [ "$cold" = true ]; then
    reiniciar_en_frio
  fi

  if [ "$repeat" -gt 1 ]; then
    echo "=== Corrida $corrida de $repeat ($(hora)) ==="
  fi

  # Si una corrida falla, la tanda se corta (set -e): seguir mezclaría
  # corridas buenas con un estado que no sabemos cuál es.
  docker compose -f "$ROOT/compose.yml" run --rm \
    -e MODE="$mode" \
    -e REQUESTS="$requests" \
    -e DURATION="$duration" \
    -e VUS="$vus" \
    -e TARGETS="$targets" \
    -e USE_DATABASE="$use_database" \
    k6

  corrida=$((corrida + 1))
done

if [ "$repeat" -gt 1 ]; then
  echo "Tanda terminada: $repeat corridas entre $inicio_tanda y $(hora) (UTC)."
  echo "En Grafana, ajusta el rango a esa franja: la tabla de la sección 1 muestra una fila por corrida y tecnología."
fi
