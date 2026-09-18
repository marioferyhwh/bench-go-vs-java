const ENDPOINTS = {
  go: 'http://go-service:8080/data',
  java: 'http://java-service:8081/data',
};

// Coinciden con los de compose.yml y stress.sh: se llegue por donde se llegue,
// una corrida sin parámetros mide lo mismo.
const DEFAULT_VUS = 20;
const DEFAULT_DURATION = '30s';
const DEFAULT_REQUESTS = 10000;
const DEFAULT_MODE = 'requests';
const MODES = ['duration', 'requests'];

// Único sitio donde vive el número. Margen de sobra sobre el scrape_interval de
// analitics/prometheus.yml para que el último tramo de tráfico y CPU entre en
// algún raspado antes de que la franja de actividad se cierre.
//
// No bloquea al usuario: los resultados se publican y se imprimen en cuanto
// termina la carga, y esta espera solo retrasa el empuje del apagado.
export const ESPERA_CIERRE_SEGUNDOS = 30;

function parseTargets(raw) {
  const names = (raw || 'go,java')
    .split(',')
    .map((n) => n.trim())
    .filter((n) => n.length > 0);

  if (names.length === 0) {
    throw new Error('TARGETS está vacío. Objetivos válidos: go, java');
  }

  const unknown = names.filter((n) => !(n in ENDPOINTS));
  if (unknown.length > 0) {
    throw new Error(
      `Objetivo desconocido: ${unknown.join(', ')}. Objetivos válidos: ${Object.keys(ENDPOINTS).join(', ')}`
    );
  }

  return names;
}

function parseUseDatabase(raw) {
  if (raw === undefined || raw === '') {
    return false;
  }
  if (raw !== 'true' && raw !== 'false') {
    throw new Error(`USE_DATABASE debe ser "true" o "false", recibido: "${raw}"`);
  }
  return raw === 'true';
}

function parseVus(raw) {
  if (raw === undefined || raw === '') {
    return DEFAULT_VUS;
  }
  const vus = Number(raw);
  if (!Number.isInteger(vus) || vus <= 0) {
    throw new Error(`VUS debe ser un entero mayor que 0, recibido: "${raw}"`);
  }
  return vus;
}

function parseMode(raw) {
  if (raw === undefined || raw === '') {
    return DEFAULT_MODE;
  }
  if (!MODES.includes(raw)) {
    throw new Error(`MODE debe ser uno de: ${MODES.join(', ')}. Recibido: "${raw}"`);
  }
  return raw;
}

// k6 acepta "30s", "2m", "1h30m", "500ms". Se valida aquí porque el valor viaja
// además como etiqueta al Pushgateway: una comilla o una llave sueltas romperían
// el formato de exposición y el empuje se perdería con un 400 silencioso.
function parseDuration(raw) {
  if (raw === undefined || raw === '') {
    return DEFAULT_DURATION;
  }
  if (!/^(\d+(\.\d+)?(ns|us|ms|s|m|h))+$/.test(raw)) {
    throw new Error(
      `DURATION debe ser una duración de k6 (30s, 2m, 1h30m), recibido: "${raw}"`
    );
  }
  return raw;
}

function parseRequests(raw) {
  if (raw === undefined || raw === '') {
    return DEFAULT_REQUESTS;
  }
  const n = Number(raw);
  if (!Number.isInteger(n) || n <= 0) {
    throw new Error(`REQUESTS debe ser un entero mayor que 0, recibido: "${raw}"`);
  }
  return n;
}

export const targets = parseTargets(__ENV.TARGETS);
export const useDatabase = parseUseDatabase(__ENV.USE_DATABASE);
export const duration = parseDuration(__ENV.DURATION);
export const mode = parseMode(__ENV.MODE);
export const requests = parseRequests(__ENV.REQUESTS);

// Identifica la corrida frente a las anteriores: el Pushgateway le da un grupo
// propio, así que una corrida ya no borra a la que la precedió y el dashboard
// puede listar varias dentro del mismo rango de tiempo.
//
// Se genera una sola vez, en setup(), y viaja de ahí al resto de la corrida
// como dato. No puede ser una constante de módulo: k6 evalúa los módulos una
// vez por contexto de ejecución, y setup() y handleSummary() son contextos
// distintos, así que cada uno calcularía su propio instante. La corrida
// quedaría partida en dos grupos: uno que abre y nunca cierra, dejando
// stress_run_active colgado en 1 para siempre, y otro que cierra sin haber
// abierto.
//
// Es el mismo número que viaja como stress_run_start_seconds: el instante de
// arranque en segundos unix. Entero, así que es seguro dentro de una ruta URL
// sin escapar.
export function nuevoRunId() {
  return String(Math.floor(Date.now() / 1000));
}

// VUS es por objetivo, no un total que se reparta: con dos objetivos y VUS=20
// cada uno recibe 20 y corren a la vez, compitiendo por la misma CPU. Repartir
// cambiaría la carga que recibe cada servicio según cuántos objetivos haya, y
// dos corridas con el mismo VUS dejarían de ser comparables.
export const vusPerTarget = parseVus(__ENV.VUS);
export const vusTotales = vusPerTarget * targets.length;

export function urlDe(name) {
  return `${ENDPOINTS[name]}?useDatabase=${useDatabase}`;
}

export function descripcionDeCarga() {
  return mode === 'requests' ? `${requests} peticiones por objetivo` : `duración ${duration}`;
}

export function valorDeCarga() {
  return mode === 'requests' ? String(requests) : duration;
}
