import http from 'k6/http';
import { sleep } from 'k6';
import {
  ESPERA_CIERRE_SEGUNDOS,
  mode,
  targets,
  useDatabase,
  valorDeCarga,
  vusPerTarget,
} from './config.js';
import { resultadosDe } from './metrics.js';

// El formato de exposición de Prometheus delimita el valor de una etiqueta con
// comillas dobles y no admite saltos de línea dentro. Un valor sin escapar
// cerraría la comilla antes de tiempo y el Pushgateway rechazaría el empuje
// entero con un 400 que aquí solo se vería como una advertencia.
function escapar(valor) {
  return String(valor)
    .replace(/\\/g, '\\\\')
    .replace(/"/g, '\\"')
    .replace(/\n/g, '\\n');
}

// Van en todas las métricas empujadas para que un resultado nunca quede separado
// de las condiciones que lo produjeron.
function condiciones(extra) {
  const pares = [
    `mode="${escapar(mode)}"`,
    `load="${escapar(valorDeCarga())}"`,
    `use_database="${escapar(useDatabase)}"`,
    `vus="${escapar(vusPerTarget)}"`,
  ];
  if (extra) {
    pares.push(extra);
  }
  return `{${pares.join(',')}}`;
}

const URL_GRUPO = `${__ENV.PUSHGATEWAY_URL || 'http://pushgateway:9091'}/metrics/job/k6_stress`;

function empujar(lineas) {
  const res = http.post(URL_GRUPO, lineas.join('\n') + '\n', {
    headers: { 'Content-Type': 'text/plain' },
    timeout: '5s',
  });

  if (res.status < 200 || res.status >= 300) {
    // No aborta la corrida: solo se pierde el registro en el dashboard.
    console.warn(`No se pudo empujar al Pushgateway: status ${res.status} ${res.body || ''}`);
  }
}

// Las condiciones de la corrida viajan como etiquetas, así que dos corridas con
// parámetros distintos son series distintas y un empuje no reemplaza al
// anterior: la corrida vieja quedaría colgada y max(stress_run_start_seconds)
// podría devolver su sello en vez del de la corrida en curso.
function borrarGrupo() {
  const res = http.del(URL_GRUPO, null, { timeout: '5s' });

  if (res.status < 200 || res.status >= 300) {
    console.warn(`No se pudo limpiar el Pushgateway: status ${res.status} ${res.body || ''}`);
  }
}

function lineasDeResultados(data) {
  const lineas = [];

  for (const name of targets) {
    const r = resultadosDe(data, name);
    const etiqueta = `target="${escapar(name)}"`;

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

  return lineas;
}

// El dashboard ancla aquí sus acumulados: sin este sello no puede saber desde
// cuándo contar, porque PromQL no admite rangos calculados.
export function abrirCorrida() {
  borrarGrupo();

  const inicio = Math.floor(Date.now() / 1000);

  empujar([
    `stress_run_active${condiciones()} 1`,
    `stress_run_start_seconds${condiciones()} ${inicio}`,
  ]);

  return inicio;
}

// Cierra en dos tiempos. La carga ya terminó, pero Prometheus todavía no ha
// raspado el último tramo de tráfico y CPU. Si el apagado saliera junto con los
// resultados, la franja de actividad moriría en el mismo instante que la carga y
// ese raspado quedaría fuera, con el acumulado del dashboard corto en las
// peticiones finales.
//
// Lo único que espera es el apagado: los resultados se publican de inmediato,
// así que el dashboard y la consola ya tienen las cifras mientras corre la
// pausa. Viajan también en el segundo empuje porque el Pushgateway reemplaza el
// grupo entero en cada POST: uno final que solo llevara el apagado borraría las
// cifras de la corrida.
export function publicarResultados(data) {
  const resultados = lineasDeResultados(data);

  empujar([`stress_run_active${condiciones()} 1`, ...resultados]);

  return resultados;
}

export function apagarCorrida(resultados) {
  sleep(ESPERA_CIERRE_SEGUNDOS);

  empujar([
    `stress_run_active${condiciones()} 0`,
    `stress_run_end_seconds${condiciones()} ${Math.floor(Date.now() / 1000)}`,
    ...resultados,
  ]);
}
