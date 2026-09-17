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
  --vus N             Usuarios virtuales          (defecto: 20)
  --cold              Reinicia los servicios y espera antes de lanzar
  --help              Muestra esta ayuda

--requests y --duration son excluyentes: una fija el trabajo, la otra el tiempo.

Ejemplos:
  stress.sh --requests 10000 --database     Comparar eficiencia contra Postgres
  stress.sh --duration 60s --vus 40         Ver cuánto aguanta cada tecnología
  stress.sh --requests 10000 --cold         Medir con los servicios recién arrancados

Las variables de entorno (MODE, REQUESTS, DURATION, VUS, TARGETS,
USE_DATABASE) se respetan si están puestas; las banderas tienen prioridad.
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

# Lo que pidan las banderas. Vacío significa "no se pidió".
flag_mode=
flag_requests=
flag_duration=
flag_vus=
flag_targets=
flag_use_database=
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
      shift 2
      ;;
    --database)
      flag_use_database=true
      shift
      ;;
    --no-database)
      flag_use_database=false
      shift
      ;;
    --cold)
      cold=true
      shift
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

if [ "$cold" = true ]; then
  echo "Reiniciando servicios para medir en frío..."
  docker compose -f "$ROOT/compose.yml" restart go-service java-service

  case $targets in
    *go*) esperar_servicio go-service 8080 ;;
  esac
  case $targets in
    *java*) esperar_servicio java-service 8081 ;;
  esac

  echo "Servicios listos."
fi

echo "Lanzando: modo=$mode | $carga | base de datos=$use_database | objetivos=$targets | $vus VUs"

docker compose -f "$ROOT/compose.yml" run --rm \
  -e MODE="$mode" \
  -e REQUESTS="$requests" \
  -e DURATION="$duration" \
  -e VUS="$vus" \
  -e TARGETS="$targets" \
  -e USE_DATABASE="$use_database" \
  k6
