import http from 'k6/http';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';

// Objetivos conocidos. Los nombres de host son los del compose.
const ENDPOINTS = {
  go: 'http://go-service:8080/data',
  java: 'http://java-service:8081/data',
};

// Estos defectos coinciden con los de compose.yml y stress.sh: se llegue por
// donde se llegue, una corrida sin parámetros mide lo mismo.
const DEFAULT_VUS = 20;
const DEFAULT_DURATION = '30s';
const DEFAULT_REQUESTS = 10000;
const DEFAULT_MODE = 'requests';
const MODES = ['duration', 'requests'];

// Aborta si TARGETS trae un nombre desconocido, en vez de estresar a medias.
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

const targets = parseTargets(__ENV.TARGETS);
const useDatabase = parseUseDatabase(__ENV.USE_DATABASE);
const totalVus = parseVus(__ENV.VUS);
const duration = __ENV.DURATION || DEFAULT_DURATION;
const mode = parseMode(__ENV.MODE);
const requests = parseRequests(__ENV.REQUESTS);

// Los VUs se reparten entre objetivos para que compitan por la misma CPU:
// medir Go solo y después Java solo compararía estados distintos de la máquina.
const vusPerTarget = Math.max(1, Math.floor(totalVus / targets.length));

// En modo requests cada objetivo atiende exactamente las mismas peticiones:
// es la única forma de comparar cuánta CPU cuesta el mismo trabajo.
// En modo duration cada uno hace las que pueda, y eso mide capacidad, no coste.
function scenarioFor(name) {
  const base = {
    exec: 'hitTarget',
    env: { TARGET_NAME: name },
    tags: { target: name },
  };

  if (mode === 'requests') {
    return {
      ...base,
      executor: 'shared-iterations',
      vus: vusPerTarget,
      iterations: requests,
      // Sin tope de tiempo la corrida no termina si un servicio va muy lento.
      maxDuration: '30m',
    };
  }

  return { ...base, executor: 'constant-vus', vus: vusPerTarget, duration: duration };
}

export const options = {
  scenarios: Object.fromEntries(targets.map((name) => [name, scenarioFor(name)])),
  // Separa las latencias por objetivo en el resumen final.
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
};

// Métricas propias por objetivo: las de k6 llegan agregadas al resumen final.
const latency = {};
const requestCount = {};
const failures = {};
const activeSeconds = {};
for (const name of targets) {
  latency[name] = new Trend(`latencia_${name}`, true);
  requestCount[name] = new Counter(`peticiones_${name}`);
  failures[name] = new Counter(`fallos_${name}`);
  activeSeconds[name] = new Counter(`tiempo_${name}`);
}

export function hitTarget() {
  const name = __ENV.TARGET_NAME;
  const url = `${ENDPOINTS[name]}?useDatabase=${useDatabase}`;

  // timeout evita que un servicio caído deje la corrida colgada.
  const res = http.get(url, {
    tags: { target: name },
    timeout: '10s',
  });

  const ok = check(res, {
    'status es 200': (r) => r.status === 200,
  });

  latency[name].add(res.timings.duration);
  requestCount[name].add(1);
  activeSeconds[name].add(res.timings.duration / 1000);
  if (!ok) {
    failures[name].add(1);
  }
}

// Extrae los resultados de un objetivo. Lo usan tanto el resumen de consola
// como el empuje al Pushgateway, para no duplicar las fórmulas.
//
// El tiempo de cada objetivo se calcula con su propia latencia acumulada
// repartida entre sus VUs, no con la duración total de la corrida: en modo
// requests un objetivo puede terminar mucho antes que el otro y quedarse
// esperando, y dividir por el total le daría un rps falsamente bajo.
function resultadosDe(data, name) {
  const total = data.metrics[`peticiones_${name}`]?.values.count ?? 0;
  const failed = data.metrics[`fallos_${name}`]?.values.count ?? 0;
  const t = data.metrics[`latencia_${name}`]?.values ?? {};
  const busy = data.metrics[`tiempo_${name}`]?.values.count ?? 0;
  const seconds = busy / vusPerTarget;

  return {
    total: total,
    failed: failed,
    seconds: seconds,
    rps: seconds > 0 ? total / seconds : 0,
    failRate: total > 0 ? (failed / total) * 100 : 0,
    p50: t['p(50)'],
    p95: t['p(95)'],
    p99: t['p(99)'],
  };
}

// Resumen separado por objetivo: un total único no permite comparar.
export function handleSummary(data) {
  const lines = [
    '',
    '='.repeat(64),
    `  RESULTADOS POR OBJETIVO`,
    `  modo=${mode}  useDatabase=${useDatabase}  ` +
      (mode === 'requests' ? `peticiones=${requests}` : `duración=${duration}`),
    '='.repeat(64),
  ];

  for (const name of targets) {
    const r = resultadosDe(data, name);

    lines.push(
      '',
      `  ${name.toUpperCase()}`,
      `    tiempo activo: ${r.seconds.toFixed(2)} s`,
      `    peticiones/s : ${r.rps.toFixed(2)}`,
      `    latencia p50 : ${fmt(r.p50)}`,
      `    latencia p95 : ${fmt(r.p95)}`,
      `    latencia p99 : ${fmt(r.p99)}`,
      `    fallos       : ${r.failRate.toFixed(2)}%  (${r.failed} de ${r.total})`
    );
  }

  lines.push('', '='.repeat(64), '');

  // Cierra la corrida en el Pushgateway con condiciones y resultados juntos.
  // Va aquí y no en teardown porque esta es la función que tiene los números.
  empujarCierre(data);

  return { stdout: lines.join('\n') };
}

function fmt(value) {
  return value === undefined ? 'sin datos' : `${value.toFixed(2)} ms`;
}

// Abre la corrida en el Pushgateway con sus condiciones. Los servicios no
// participan: k6 es quien sabe qué se está midiendo y bajo qué parámetros.
export function setup() {
  const carga = mode === 'requests' ? `${requests} peticiones por objetivo` : `duración ${duration}`;
  console.log(
    `Modo ${mode} | objetivos: ${targets.join(', ')} | useDatabase=${useDatabase} | ` +
      `${vusPerTarget} VUs por objetivo | ${carga}`
  );

  empujar([`stress_run_active${condiciones()} 1`]);
  return { inicio: new Date().toISOString() };
}

// Etiquetas que describen la corrida: van en todas las métricas empujadas para
// que un resultado nunca quede separado de las condiciones que lo produjeron.
function condiciones(extra) {
  const load = mode === 'requests' ? String(requests) : duration;
  const pares = [
    `mode="${mode}"`,
    `load="${load}"`,
    `use_database="${useDatabase}"`,
    `vus="${vusPerTarget}"`,
  ];
  if (extra) {
    pares.push(extra);
  }
  return `{${pares.join(',')}}`;
}

// Cierra la corrida: repite las condiciones con active=0 y añade los resultados.
// El empuje reemplaza al del inicio, así que queda un único conjunto coherente.
function empujarCierre(data) {
  const lineas = [`stress_run_active${condiciones()} 0`];

  for (const name of targets) {
    const r = resultadosDe(data, name);
    const etiqueta = `target="${name}"`;

    lineas.push(
      `stress_run_requests_total${condiciones(etiqueta)} ${r.total}`,
      `stress_run_failures_total${condiciones(etiqueta)} ${r.failed}`,
      `stress_run_rps${condiciones(etiqueta)} ${r.rps.toFixed(4)}`,
      `stress_run_duration_seconds${condiciones(etiqueta)} ${r.seconds.toFixed(3)}`
    );

    for (const [q, valor] of [['0.5', r.p50], ['0.95', r.p95], ['0.99', r.p99]]) {
      if (valor !== undefined) {
        lineas.push(
          `stress_run_latency_ms${condiciones(`${etiqueta},quantile="${q}"`)} ${valor.toFixed(4)}`
        );
      }
    }
  }

  empujar(lineas);
}

// El Pushgateway espera texto plano y exige un salto de línea final.
function empujar(lineas) {
  const url = `${__ENV.PUSHGATEWAY_URL || 'http://pushgateway:9091'}/metrics/job/k6_stress`;
  const res = http.post(url, lineas.join('\n') + '\n', {
    headers: { 'Content-Type': 'text/plain' },
    timeout: '5s',
  });

  if (res.status < 200 || res.status >= 300) {
    // No aborta la corrida: solo se pierde el registro en el dashboard.
    console.warn(`No se pudo empujar al Pushgateway: status ${res.status} ${res.body || ''}`);
  }
}
