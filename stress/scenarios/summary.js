import { descripcionDeCarga, mode, targets, useDatabase } from './config.js';
import { resultadosDe } from './metrics.js';

const SEPARADOR = '='.repeat(64);

function fmt(value) {
  return value === undefined ? 'sin datos' : `${value.toFixed(2)} ms`;
}

// Separado por objetivo: un total único no permite comparar.
//
// Devuelve las líneas sueltas, no un bloque: quien lo imprime usa console.log,
// que escapa los saltos de línea y dejaría el informe en una sola línea con
// "\n" literales. Una llamada por línea lo mantiene legible.
export function reportePorObjetivo(data) {
  const lines = [
    '',
    SEPARADOR,
    `  RESULTADOS POR OBJETIVO`,
    `  modo=${mode}  useDatabase=${useDatabase}  ${descripcionDeCarga()}`,
    SEPARADOR,
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

  lines.push('', SEPARADOR, '');

  return lines;
}
