import { Counter, Trend } from 'k6/metrics';
import { targets, vusPerTarget } from './config.js';

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

export function registrarPeticion(name, res, ok) {
  latency[name].add(res.timings.duration);
  requestCount[name].add(1);
  activeSeconds[name].add(res.timings.duration / 1000);
  if (!ok) {
    failures[name].add(1);
  }
}

// El tiempo de cada objetivo se calcula con su propia latencia acumulada
// repartida entre sus VUs, no con la duración total de la corrida: en modo
// requests un objetivo puede terminar mucho antes que el otro y quedarse
// esperando, y dividir por el total le daría un rps falsamente bajo.
export function resultadosDe(data, name) {
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
