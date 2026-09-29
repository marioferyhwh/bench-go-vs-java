# database

Postgres 18 con los datos contra los que miden los dos servicios cuando `useDatabase=true`.

No tiene código: es una imagen de Postgres con los scripts de semilla copiados donde el contenedor los ejecuta al inicializarse.

## La regla que manda aquí

**La base se siembra sola desde los `.sql` versionados, y de ninguna otra forma.** Tiene una consecuencia práctica:

```sh
docker compose down -v && docker compose up --build
```

Esto debe dejar exactamente el mismo estado que había antes. Si no lo deja, hay datos creados a mano en algún sitio y las mediciones dejan de ser reproducibles: una corrida contra 100 filas no compara con otra contra 5.000.

Por eso **no se inserta nada a mano en la base**, ni siquiera "para probar".

## Cómo se versionan los scripts

Los archivos de `sql/init/` se numeran `vX.YY_TIPO_NOMBRE.sql` y Postgres los ejecuta en orden alfabético al crear el volumen:

| Script | Qué hace |
|---|---|
| `v0.01_DDL_schema.sql` | Crea el usuario `apidatatest`, los esquemas `datatest` y `common`, y los permisos |
| `v0.02_DDL_USER.sql` | Crea `datatest.user` |
| `v0.03_DML_USER.sql` | Siembra los usuarios iniciales |
| `v0.04_DML_USER_MORE_DATA.sql` | Añade el resto de filas para las pruebas de carga |

**Un script mergeado no se edita nunca.** Los cambios van en un archivo nuevo con el número siguiente.

La razón no es ceremonia: Postgres solo ejecuta estos scripts **cuando el volumen está vacío**. Editar uno ya aplicado no cambia nada en una base existente, así que el archivo y la base real se desincronizan sin que nadie lo note, y el siguiente que levante el entorno de cero obtendrá algo distinto a lo que tú tienes.

## La tabla

`datatest.user`, unas 104 filas tras la semilla.

| Columna | Tipo | Nota |
|---|---|---|
| `id` | UUID | Generado por la base |
| `created_at`, `updated_at` | TIMESTAMPTZ | |
| `deleted_at` | TIMESTAMPTZ | Borrado lógico: las consultas filtran `IS NULL` |
| `email` | varchar(63) | Único |
| `password` | varchar(256) | **Nunca se selecciona ni se devuelve** |
| `active` | bool | |

Los dos servicios hacen exactamente esta consulta, y devuelve como mucho 100 filas:

```sql
SELECT id, email, active, created_at
FROM datatest.user
WHERE deleted_at IS NULL
ORDER BY created_at
LIMIT 100;
```

Dos detalles con los que es fácil tropezar:

- **`user` es palabra reservada en SQL.** Todas las consultas la califican como `datatest.user`.
- **`password` está fuera del contrato.** Ningún `SELECT` la incluye, y es un criterio de aceptación que la respuesta no la lleve.

## Conexión

| | |
|---|---|
| Host y puerto | `localhost:5432` (o `postgres:5432` dentro de la red de compose) |
| Base | `postgres` |
| Usuario de los servicios | `apidatatest` |
| Superusuario | `postgres` / `postgres` |

`apidatatest` se crea sin privilegios de superusuario y con permisos solo sobre los dos esquemas. Los servicios nunca se conectan como `postgres`.

## Comprobar el estado

```sh
docker exec postgres psql -U postgres -c "SELECT count(*) FROM datatest.user;"
```

## Lo que no se mide aquí

El consumo de CPU y de memoria de Postgres queda fuera de la comparación a propósito: los dos servicios hablan con la misma base, así que su coste afecta igual a ambos y no distingue entre tecnologías.
