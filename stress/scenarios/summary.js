import { descripcionDeCarga, mode, targets, useDatabase } from './config.js';
import { resultadosDe, totalesDe } from './metrics.js';

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

  const t = totalesDe(data);

  // Cierra con lo que costó la corrida en conjunto. El tiempo es de reloj, así
  // que no coincide con la suma de los tiempos activos de arriba: los objetivos
  // corren a la vez, y además aquí entra la espera de apertura.
  //
  // Las peticiones van desglosadas por tecnología además del total: el total
  // suelto no dice cuántas atendió cada una, que es lo que se compara.
  lines.push('', SEPARADOR, `  TOTAL DE LA CORRIDA`, `    duración   : ${t.seconds.toFixed(2)} s`);

  for (const name of targets) {
    const r = resultadosDe(data, name);
    lines.push(`    ${name.toUpperCase().padEnd(10)} : ${r.total} peticiones, ${r.failed} fallos`);
  }

  lines.push(
    `    ${'TOTAL'.padEnd(10)} : ${t.peticiones} peticiones, ${t.fallos} fallos`,
    SEPARADOR,
    ''
  );

  return lines;
}
