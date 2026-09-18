import http from 'k6/http';
import { check } from 'k6';
import {
  ESPERA_CIERRE_SEGUNDOS,
  descripcionDeCarga,
  duration,
  mode,
  requests,
  targets,
  urlDe,
  useDatabase,
  vusPerTarget,
  vusTotales,
} from './config.js';
import { registrarPeticion } from './metrics.js';
import { abrirCorrida, apagarCorrida, publicarResultados } from './pushgateway.js';
import { reportePorObjetivo } from './summary.js';

// En modo requests cada objetivo atiende exactamente las mismas peticiones: es
// la única forma de comparar cuánta CPU cuesta el mismo trabajo. En modo
// duration cada uno hace las que pueda, y eso mide capacidad, no coste.
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

export function setup() {
  console.log(
    `Modo ${mode} | objetivos: ${targets.join(', ')} | useDatabase=${useDatabase} | ` +
      `${vusPerTarget} VUs por objetivo (${vusTotales} en total) | ${descripcionDeCarga()}`
  );

  const { runId, inicio } = abrirCorrida();

  // El runId viaja por aquí y no como constante de módulo porque handleSummary
  // corre en otro contexto de k6: los módulos se evalúan una vez por contexto,
  // así que un identificador calculado allí sería distinto del que abrió la
  // corrida. Lo que devuelve setup() sí llega intacto.
  return { runId, inicio: new Date(inicio * 1000).toISOString() };
}

export function hitTarget() {
  const name = __ENV.TARGET_NAME;

  // timeout evita que un servicio caído deje la corrida colgada.
  const res = http.get(urlDe(name), {
    tags: { target: name },
    timeout: '10s',
  });

  const ok = check(res, {
    'status es 200': (r) => r.status === 200,
  });

  registrarPeticion(name, res, ok);
}

// El cierre va aquí y no en teardown: k6 no promete que teardown termine antes
// de handleSummary, así que repartirlo entre las dos dejaría la franja de
// actividad a merced de cuál corriera primero.
//
// El resumen se imprime con console.log y no por el stdout devuelto porque ese
// valor solo se escribe cuando handleSummary retorna, es decir después de la
// espera del apagado: el usuario se quedaría medio minuto mirando una consola
// vacía con los números ya calculados.
export function handleSummary(data) {
  for (const linea of reportePorObjetivo(data)) {
    console.log(linea);
  }

  // k6 entrega aquí lo que devolvió setup(), y de ahí sale el runId con el que
  // se abrió la corrida. Sin él los empujes irían a un grupo distinto del de
  // apertura, que quedaría con stress_run_active en 1 para siempre.
  const { runId } = data.setup_data;

  const resultados = publicarResultados(runId, data);

  console.log(
    `Resultados publicados. Esperando ${ESPERA_CIERRE_SEGUNDOS} s a que Prometheus ` +
      'raspe el tramo final antes de cerrar la franja de actividad.'
  );

  apagarCorrida(runId, resultados);

  return {};
}
