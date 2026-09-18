import http from 'k6/http';
import { sleep } from 'k6';
import {
  ESPERA_APERTURA_SEGUNDOS,
  ESPERA_CIERRE_SEGUNDOS,
  mode,
  nuevoRunId,
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
//
// run_id viaja aquí además de en la ruta del grupo: la ruta separa las corridas
// para que no se pisen, la etiqueta permite agrupar por corrida en PromQL sin
// depender de cómo el Pushgateway nombre el grupo. La tabla del dashboard
// necesita esa etiqueta para dar una fila por corrida y tecnología.
function condiciones(runId, extra) {
  const pares = [
    `run_id="${escapar(runId)}"`,
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

const BASE_PUSHGATEWAY = __ENV.PUSHGATEWAY_URL || 'http://pushgateway:9091';

// Cada corrida tiene su propio grupo, así que un empuje nunca reemplaza a la
// corrida anterior: el dashboard puede listar todas las que caigan dentro del
// rango de tiempo que se esté viendo.
//
// El Pushgateway no expira grupos por su cuenta, y borrar el grupo padre
// k6_stress no alcanza a los subgrupos: hay que borrar cada run_id por
// separado, o tirar el stack con `docker compose down -v`.
function urlDelGrupo(runId) {
  return `${BASE_PUSHGATEWAY}/metrics/job/k6_stress/run_id/${runId}`;
}

function empujar(runId, lineas) {
  const res = http.post(urlDelGrupo(runId), lineas.join('\n') + '\n', {
    headers: { 'Content-Type': 'text/plain' },
    timeout: '5s',
  });

  if (res.status < 200 || res.status >= 300) {
    // No aborta la corrida: solo se pierde el registro en el dashboard.
    console.warn(`No se pudo empujar al Pushgateway: status ${res.status} ${res.body || ''}`);
  }
}

function lineasDeResultados(runId, data) {
  const lineas = [];

  for (const name of targets) {
    const r = resultadosDe(data, name);
    const etiqueta = `target="${escapar(name)}"`;

    lineas.push(
      `stress_run_requests_total${condiciones(runId, etiqueta)} ${r.total}`,
      `stress_run_failures_total${condiciones(runId, etiqueta)} ${r.failed}`,
      `stress_run_rps${condiciones(runId, etiqueta)} ${r.rps.toFixed(4)}`,
      `stress_run_duration_seconds${condiciones(runId, etiqueta)} ${r.seconds.toFixed(3)}`
    );

    for (const [q, valor] of [['0.5', r.p50], ['0.95', r.p95], ['0.99', r.p99]]) {
      if (valor !== undefined) {
        lineas.push(
          `stress_run_latency_ms${condiciones(runId, `${etiqueta},quantile="${q}"`)} ${valor.toFixed(4)}`
        );
      }
    }
  }

  return lineas;
}

// Deja constancia de cuándo empezó la corrida y marca la franja de actividad a
// la que se acotan los acumulados del dashboard.
//
// Genera el run_id y lo devuelve: es el único sitio donde nace. Quien cierra la
// corrida recibe ese mismo valor en vez de calcular otro, porque un run_id
// distinto al cerrar dejaría el grupo de apertura con stress_run_active colgado
// en 1 para siempre, y el gateado de todos los acumulados apuntando a una
// corrida que ya terminó.
//
// No borra nada antes de empujar: el grupo lleva el run_id de esta corrida, así
// que no hay nada suyo que limpiar y lo único que se borraría es la corrida
// anterior, que el dashboard necesita para listar varias en el mismo rango.
export function abrirCorrida() {
  const runId = nuevoRunId();
  const inicio = Math.floor(Date.now() / 1000);

  empujar(runId, [
    `stress_run_active${condiciones(runId)} 1`,
    `stress_run_start_seconds${condiciones(runId)} ${inicio}`,
  ]);

  // Espera a que Prometheus raspe los contadores con la franja ya abierta y sin
  // tráfico todavía. Ese raspado es el punto de partida de los acumulados: sin
  // él, el primero que entra en la franja llega con peticiones ya servidas y
  // esas quedan fuera de la resta. En una corrida de 13 s el acumulado daba
  // 3400 de 5000 peticiones.
  sleep(ESPERA_APERTURA_SEGUNDOS);

  return { runId, inicio };
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
export function publicarResultados(runId, data) {
  const resultados = lineasDeResultados(runId, data);

  empujar(runId, [`stress_run_active${condiciones(runId)} 1`, ...resultados]);

  return resultados;
}

export function apagarCorrida(runId, resultados) {
  sleep(ESPERA_CIERRE_SEGUNDOS);

  empujar(runId, [
    `stress_run_active${condiciones(runId)} 0`,
    `stress_run_end_seconds${condiciones(runId)} ${Math.floor(Date.now() / 1000)}`,
    ...resultados,
  ]);
}
