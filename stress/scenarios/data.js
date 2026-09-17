import http from 'k6/http';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';

// Objetivos conocidos. Los nombres de host son los del compose.
const ENDPOINTS = {
  go: 'http://go-service:8080/data',
  java: 'http://java-service:8081/data',
};

const DEFAULT_VUS = 10;
const DEFAULT_DURATION = '30s';

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

const targets = parseTargets(__ENV.TARGETS);
const useDatabase = parseUseDatabase(__ENV.USE_DATABASE);
const totalVus = parseVus(__ENV.VUS);
const duration = __ENV.DURATION || DEFAULT_DURATION;

// Los VUs se reparten entre objetivos para que compitan por la misma CPU:
// medir Go solo y después Java solo compararía estados distintos de la máquina.
const vusPerTarget = Math.max(1, Math.floor(totalVus / targets.length));

export const options = {
  scenarios: Object.fromEntries(
    targets.map((name) => [
      name,
      {
        executor: 'constant-vus',
        vus: vusPerTarget,
        duration: duration,
        exec: 'hitTarget',
        env: { TARGET_NAME: name },
        tags: { target: name },
      },
    ])
  ),
  // Separa las latencias por objetivo en el resumen final.
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
};

// Métricas propias por objetivo: las de k6 llegan agregadas al resumen final.
const latency = {};
const requests = {};
const failures = {};
for (const name of targets) {
  latency[name] = new Trend(`latencia_${name}`, true);
  requests[name] = new Counter(`peticiones_${name}`);
  failures[name] = new Counter(`fallos_${name}`);
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
  requests[name].add(1);
  if (!ok) {
    failures[name].add(1);
  }
}

// Resumen separado por objetivo: un total único no permite comparar.
export function handleSummary(data) {
  const seconds = data.state.testRunDurationMs / 1000;
  const lines = [
    '',
    '='.repeat(64),
    `  RESULTADOS POR OBJETIVO  (useDatabase=${useDatabase}, ${duration})`,
    '='.repeat(64),
  ];

  for (const name of targets) {
    const total = data.metrics[`peticiones_${name}`]?.values.count ?? 0;
    const failed = data.metrics[`fallos_${name}`]?.values.count ?? 0;
    const t = data.metrics[`latencia_${name}`]?.values ?? {};
    const rps = seconds > 0 ? total / seconds : 0;
    const failRate = total > 0 ? (failed / total) * 100 : 0;

    lines.push(
      '',
      `  ${name.toUpperCase()}`,
      `    peticiones/s : ${rps.toFixed(2)}`,
      `    latencia p50 : ${fmt(t['p(50)'])}`,
      `    latencia p95 : ${fmt(t['p(95)'])}`,
      `    latencia p99 : ${fmt(t['p(99)'])}`,
      `    fallos       : ${failRate.toFixed(2)}%  (${failed} de ${total})`
    );
  }

  lines.push('', '='.repeat(64), '');

  return { stdout: lines.join('\n') };
}

function fmt(value) {
  return value === undefined ? 'sin datos' : `${value.toFixed(2)} ms`;
}

export function setup() {
  console.log(
    `Objetivos: ${targets.join(', ')} | useDatabase=${useDatabase} | ` +
      `${vusPerTarget} VUs por objetivo | duración ${duration}`
  );
}
