# Migración de la biblioteca: iCloud Drive → Google Drive

La app lee los cómics de la carpeta «Cómics» del Google Drive de su usuario. Esta
herramienta los lleva hasta allí desde iCloud Drive, **por tandas**, ordenados y
con nombres uniformes, y deja preparado el índice (con portadas) que la app
descarga para no tener que recorrer Drive entero.

Código: `tools/migracion/`. Datos: fuera del repo (el repo es público), en
`COMICS_DATA` (por defecto `~/comic-reader-data`).

## Órdenes

| Orden | Qué hace |
|---|---|
| `make mig-plan` | Solo lectura (lstat; nada se descarga ni se sube). Cuántos ficheros y GB hay, qué entra en la primera tanda, cuántos nombres no se interpretan, cuántas series van a revisión, cuánto cabe en Drive. Guarda el plan completo en `logs/plan-AAAAMMDD-HHMM.md`. |
| `make migrar` | Sube **la siguiente tanda** (~15 GB) en orden alfabético por ruta. Una orden = una tanda. Se reanuda sola si se cortó. |
| `make mig-humo HUMO='Carpeta/Serie'` | Prueba de humo completa sobre una sola carpeta, con informe en `logs/humo-AAAA-MM-DD.md`. Sin `HUMO` usa la carpeta escrita en `estado/humo.txt`. |
| `make mig-verificar` | Compara el md5 de cada fichero subido con el que da Drive (`rclone lsjson --hash`: no se descarga nada). |
| `make mig-indice` | Regenera y resube `Cómics/.indice/` sin volver a subir cómics (por ejemplo, cuando llega la taxonomía o los metadatos). |
| `make mig-estado` | Cuántos ficheros hay en cada estado, las tandas hechas y el espacio libre en Drive. |
| `make mig-test` | Pruebas (`python3 -m unittest`) con ficheros sintéticos y un rclone falso: no tocan la red ni iCloud. |
| `make mig-bin` | Compila `tools/migracion/bin/evict` (Swift). Lo hacen solas `migrar` y `mig-humo`. |

Variables (en la línea de `make` o en el entorno):

| Variable | Por defecto | Para qué |
|---|---|---|
| `COMICS_DATA` | `~/comic-reader-data` | Estado, registros, portadas, índice, lista de revisión |
| `TANDA_GB` | 15 | Tamaño de una tanda |
| `RESERVA_GIB` | 15 | Espacio que siempre se deja libre en Drive |
| `MIN_LIBRE_MAC_GB` | 40 | Espacio mínimo que se deja libre en la Mac al materializar |
| `LOTE_GB` | 2 | Una tanda se procesa en lotes: materializar → subir → verificar → desalojar |
| `HILOS` | 4 | Ficheros que se materializan y analizan a la vez |
| `COMICS_ORIGEN` | la carpeta «Cómics » de iCloud Drive | Raíz de origen (la de verdad tiene un espacio final) |
| `COMICS_REMOTO` | `gdrive:Cómics` | Raíz de destino en rclone |

## Cómo se lanza una tanda

```sh
make mig-plan        # mirar qué va a pasar (opcional, no toca nada)
make migrar          # una tanda; se puede cortar con Ctrl-C y relanzar
make mig-estado      # cómo va
```

Todo corre bajo `caffeinate -dimsu`, así que la Mac no se duerme a mitad. Si se
corta (Ctrl-C, apagón, cuota, disco, Drive sin conexión), basta con volver a lanzar
`make migrar`: el estado se guarda fichero a fichero en una base SQLite y la tanda
sigue donde se quedó. Dos migraciones a la vez no pueden correr (hay un cerrojo).

Una tanda **para sola, y queda abierta para reanudarse**, cuando seguir solo
serviría para bajar de iCloud cosas que no se pueden subir:

- antes de materializar cada lote, `rclone about` no responde (sin red, token
  caducado) o el lote dejaría Drive por debajo de la reserva;
- tras subir un lote, no se puede listar Drive para comprobarlo, Google corta por
  el límite diario de subida, o van **dos lotes seguidos** sin nada verificado.

Si en un lote falla solo algún fichero suelto (y Drive responde), la tanda sigue:
ese fichero se reintenta en la siguiente.

### Qué hace una tanda, paso a paso

1. **Censo** de toda la biblioteca con `lstat` (no descarga nada).
2. **Plan**: clasifica cada carpeta, calcula el nombre de destino de cada fichero
   y elige los siguientes ~15 GB en orden alfabético por ruta, cortando entre
   series cuando puede.
3. **Cuota**: `rclone about gdrive: --json`. Si a Drive le quedan menos de 15 GiB,
   o la tanda le dejaría menos de 15 GiB, **para** y lo dice.
4. Por lotes de ~2 GB:
   1. **Materializar**: leer un fichero desalojado de iCloud lo descarga entero.
      Se lee una sola vez y en esa lectura se calcula el md5 y se mira la cabecera.
   2. **Detectar** el formato real por cabecera (ZIP, RAR4, RAR5, 7z, TAR, PDF) y
      **comprobar** la integridad (`bsdtar -tf`). Se distingue «roto» de «no pude
      comprobarlo»:
      - `ok`: bsdtar lista sus entradas.
      - `parcial`: RAR sólido (4 o 5, o de solidez desconocida); libarchive falla a
        partir de la 2.ª entrada. Se sube y se anota «comprobación parcial».
      - `dudosa`: no se pudo comprobar, pero no hay prueba de que esté roto —
        bsdtar no terminó en 10 min, RAR o 7z cifrado, parte de un RAR
        multivolumen, cabecera desconocida que bsdtar sí lee (autoextraíble…),
        PDF sin `%%EOF`. **Se sube** y va a la lista de revisión.
      - `rota`: 0 bytes, cabecera desconocida que bsdtar tampoco lee, o archivo de
        formato conocido que bsdtar no puede listar (o que se corta a media lista).
   3. **Apartar** (no subir) los duplicados por md5 (se queda el primero en orden
      alfabético), los rotos y los de 0 bytes.
   4. **Portada**: la primera imagen en orden natural (`bsdtar -xOqf`), o la 1.ª
      página del PDF (`sips`), a WebP de 300 px de ancho (`cwebp -q 70`) en
      `portadas/<md5>.webp`.
   5. **Subir** con `rclone copy --files-from-raw` desde una carpeta de enlaces
      simbólicos que ya tiene la estructura y los nombres de destino.
   6. **Verificar**: el md5 que da Drive (`rclone lsjson --hash`) tiene que ser
      el md5 local. Si no cuadra, se resube ese fichero una vez; si sigue sin
      cuadrar, queda «subido» y la siguiente tanda lo vuelve a intentar.
   7. **Desalojar** la copia local de iCloud (`bin/evict`, que llama a
      `FileManager.evictUbiquitousItem`): libera el disco de la Mac. El fichero
      sigue en iCloud, intacto.
5. **Índice**: se rehace `indice.jsonl`, `portadas.zip` y la lista de revisión,
   y se sube `Cómics/.indice/`.

## Estructura en Drive

```
Cómics/
  <Categoría>/<Editorial>/<Serie canónica>/<Serie canónica> 001 (1999).cbz
  Manga/<Serie canónica>/<Serie canónica> 012.cbz        (manga, manhwa y manhua: sin editorial)
  .indice/
    indice.jsonl     una línea por cómic subido
    portadas.zip     <md5>.webp de cada cómic
    resumen.json     cuántos, cuándo
    taxonomia.json   si existe en COMICS_DATA/metadatos/
    series.jsonl     si existe en COMICS_DATA/metadatos/ (metadatos de nivel 2)
  _sonda/            carpeta de pruebas de la app: la migración no la toca
```

Categorías: Americano (Marvel, DC, Image, Dark Horse, Otros) · Europeo
(Franco-belga, Español) · Latinoamericano (Novaro, Memín, Condorito, Mafalda) ·
Manga · Manhwa · Manhua.

Una línea de `indice.jsonl`:

```json
{"md5": "…", "ruta": "Americano/Marvel/Serie/Serie 001.cbr", "nombre": "Serie 001.cbr",
 "drive_id": "…", "categoria": "Americano", "editorial": "Marvel", "serie": "Serie",
 "numero": "001", "anio": null, "formato": "RAR4", "tamano": 6797134, "paginas": 28,
 "solido": false, "portada": "<md5>.webp", "original": "ruta/en/iCloud.cbr",
 "meta_serie": {"…": "si hay metadatos de nivel 2 para la serie"}}
```

## Clasificación y nombres

**Clasificación.** Manda `COMICS_DATA/metadatos/taxonomia.json` (ruta de la
carpeta → `{categoria, editorial_carpeta, serie_canonica}`; se aceptan también
una lista de objetos con `ruta`, o el diccionario dentro de `series`). Si una
carpeta no tiene entrada, se usa una heurística por carpeta de primer nivel y la
serie va a la lista de revisión. La heurística de casos propios de la biblioteca
vive fuera del repo, en `COMICS_DATA/estado/heuristica-local.json`.

**Normalización.** Nombres en NFC; sin espacios al principio ni al final de cada
componente, ni dobles; «Cómics» sin espacio; las palabras disfrazadas con
dígitos se devuelven a letras (`C0M1CS` → `COMICS`, `4zul` → `Azul`) sin
tocar números de verdad (`Vol1`, `nº06`, `1de4`, `2099`).

**Patrón.**

| Caso | Nombre |
|---|---|
| Número | `Serie 012 (1999).cbz` (año solo si el nombre lo trae) |
| Tomo | `Serie 012.cbz` |
| Número único | `Título (1999).cbz` |
| Miniserie | el arco va en la serie: `Personaje - Arco 001.cbr` |

El número sale de `#12`, `nº12`, `Tomo 12`, `Capítulo 12`, `1 de 4`, `1de4`, `Vol 12`
o de un número al principio o al final; el año, de `(1999)` o de `012 1999`. La
regla es conservadora: **solo se renombra si lo que queda del nombre es la serie**
(o no queda nada). Si hay dos números, un rango (`01-09`), cifras entre
paréntesis o un título distinto, se conserva el nombre limpio y el fichero va a
la lista de revisión. Si dos ficheros acabarían con el mismo nombre, el segundo
conserva el suyo limpio.

**Extensión.** Se corrige al formato real: ZIP → `.cbz`, RAR → `.cbr`,
7z → `.cb7`, TAR → `.cbt`, PDF → `.pdf` (hay ZIP y 7z disfrazados de `.cbr`). Si
la cabecera es desconocida pero bsdtar lo lee (`dudosa`), se conserva la extensión
que traía.

## Estados de cada fichero

| Estado | Significa |
|---|---|
| `pendiente` | Aún no se ha subido (puede tener md5 y portada si ya se analizó, o un error que se reintenta) |
| `apartado_duplicado` | Mismo md5 que otro que sí se sube; no se sube |
| `apartado_roto` | Integridad `rota`: 0 bytes, cabecera desconocida que `bsdtar` tampoco lee, o `bsdtar` no puede listarlo; no se sube |
| `subido` | Está en Drive pero el md5 aún no cuadra; la siguiente tanda lo resube (como «rezagado») |
| `verificado` | El md5 de Drive es el local; si el desalojo falló, la siguiente tanda lo reintenta |
| `desalojado` | Verificado y la copia local de iCloud ya está liberada (estado final) |
| `ignorado` | `.DS_Store`, ocultos e imágenes sueltas: no son cómics |

## La lista de revisión

`COMICS_DATA/revisar/REVISION.md` (y `revision.tsv`) se rehace en cada tanda:

- apartados por duplicado (y de cuál son copia) y por rotos;
- ficheros que no son cómics;
- series sin entrada en la taxonomía (clasificadas por heurística);
- nombres que no se interpretaron, con el motivo;
- comprobación parcial o dudosa (RAR sólidos, sin comprobar por tiempo, cifrados,
  multivolumen, cabecera desconocida, PDF sin `%%EOF`) y archivos sin imágenes;
- cómics sin portada (la app mostrará una tarjeta vacía);
- errores pendientes: se reintentan solos en la siguiente `make migrar`. Los
  `pendiente` vuelven a la cola; los `subido` y `verificado` de una tanda ya
  cerrada entran en la siguiente como **rezagados** (primero, antes de los
  nuevos); y lo que `make mig-verificar` no encuentra bien en Drive vuelve a
  `subido`, se resube y se verifica antes de desalojarse otra vez.

Nada de lo apartado se pierde: sigue en iCloud. Para subir algo apartado o
cambiar un nombre, se corrige en iCloud o en la taxonomía y se vuelve a lanzar.

## Cuándo ampliar Google One

`make mig-plan` dice cuánto cabe: espacio libre en Drive menos la reserva de
15 GiB, y cuántas tandas son. Cuando una tanda ya no cabe, `make migrar` para
con un mensaje que dice cuántos GB cabrían (`make migrar TANDA_GB=n` sube una
tanda más pequeña). Para decidir:

- La cuota de Google One es compartida con Gmail y Fotos: la reserva de 15 GiB
  existe para no dejar el correo sin sitio.
- Necesario en total ≈ uso actual de Drive + lo pendiente + 15 GiB. El plan lo
  calcula. Si la biblioteca entera no cabe en el plan siguiente (200 GB), el
  salto es a 2 TB.
- Los duplicados reales (mismo md5) se descuentan solos al subir: el plan solo
  puede estimarlos por tamaño.
- Ampliar no exige tocar nada: la siguiente `make migrar` ve el espacio nuevo.

## Lo que NO hace nunca

- **Borrar, mover ni renombrar nada en iCloud.** Solo lee y, tras verificar,
  desaloja la copia local (el fichero sigue en iCloud). Los nombres nuevos solo
  existen en Drive.
- **`rclone sync`, `delete`, `purge` ni `move` en Drive.** Solo `copy`/`copyto`
  (que nunca borran), `lsjson`, `md5sum`, `lsl` y `about`.
- **Tocar `Cómics/_sonda`.**
- **Leer las credenciales** de rclone o del cliente OAuth: rclone las usa solo.
- **Descargar de Drive para comparar**: el md5 lo da Drive gratis.
- **Leer ficheros desalojados fuera de la tanda en curso**: el plan solo usa
  `lstat` (y la cabecera de los que ya estaban en la Mac).

## Límites conocidos

- Si la taxonomía cambia el nombre de una serie que ya se subió, lo subido se
  queda donde está; los ficheros nuevos de esa serie irían a la carpeta nueva.
  Conviene tener la taxonomía antes de las tandas de esa parte del alfabeto.
- `bsdtar -tf` lista entradas, no descomprime: un archivo con datos corruptos
  dentro pero cabeceras sanas pasa la comprobación.
- El límite diario de subida de Google (750 GB) corta rclone
  (`--drive-stop-on-upload-limit`); se relanza al día siguiente.

## Pendientes de la revisión

Hallazgos de severidad baja de la revisión del 30-oct. No se han tocado; se dejan
aquí para decidir.

- **F5 · portadas.py, `extraer()`.** bsdtar recibe el nombre de la entrada como
  patrón sin `--` delante: una entrada que empiece por `-` se leería como opción
  de bsdtar (en el peor caso, `--use-compress-program=…` ejecutaría un programa).
  Arreglo: `["bsdtar", "-xOqf", ruta, "--", escapar_patron(entrada)]` y una prueba
  con un CBZ sintético cuya entrada empiece por `-`. Es de una línea; conviene
  hacerlo antes de las tandas con cómics bajados de internet.
- **F6 · migrar.py, `procesar()`.** `estaba_local` se recalcula al reanalizar: un
  fichero que la propia herramienta materializó en una ejecución interrumpida pasa
  a contar como «ya estaba en la Mac» y, si acaba apartado, no se desaloja. Sin
  pérdida de datos; solo no se libera ese disco. Arreglo: conservar el valor del
  censo (`MIN(fila, nuevo)`) en vez de sobrescribirlo.
- **F7 · migrar.py, `cmd_verificar` / `cmd_plan`.** No toman el cerrojo: un
  `make mig-verificar` lanzado mientras corre `make migrar` puede apuntar errores
  falsos («no está en Drive») sobre ficheros que la tanda está a punto de
  verificar. Se curan solos (la tanda o el siguiente `mig-verificar` los limpian;
  y si llegan a una tanda, se resuben sin coste porque rclone ve el mismo tamaño y
  fecha), pero mientras tanto ensucian la lista de revisión. Arreglo: `with
  Cerrojo(cfg)` en `cmd_verificar` y abrir la base en solo lectura en `plan` y
  `estado`. Mientras tanto: no lanzar `mig-verificar` con una tanda en marcha.
- **F9 · docs/ (privacidad del repo público).** Dos documentos anteriores a la
  migración (`docs/INFORME-ARCHIVOS-LOCALES.md` y `docs/CONSOLA-GOOGLE.md`)
  contienen rutas reales de la biblioteca y una dirección de correo personal.
  Propuesta: mover el informe a `COMICS_DATA/logs/`, dejar en `docs/` solo las
  conclusiones y quitar el correo. Ya están en el historial público: si hace falta
  reescribirlo es decisión de Jano.
- **F10 · nombres.py.** La regla conservadora deja sin patrón dos casos
  frecuentes: un tomo con el rango de capítulos entre paréntesis
  («Serie - Tomo 54 (#505-514)») y un prefijo de orden al principio
  («00 Serie 07»). No hay pérdida ni colisión: van a la lista de revisión. Si se
  quiere: tratar `(#a-b)` tras `Tomo NN` como anotación y aceptar el prefijo
  `NN ` cuando además hay un número final que casa con la serie.
- **F11 · Makefile y formatos.py.** `COMICS_KEYSTORE_PASS` se exporta a todas las
  recetas (también a las `mig-*`) y `formatos.ENV_UTF8` copia el entorno entero a
  bsdtar, cwebp y sips. Ninguno la registra, pero no la necesitan. Arreglo:
  exportarla solo en `deploy`/`sha1` y dar a los subprocesos un entorno mínimo
  (PATH, HOME, LANG, LC_ALL).
- **F12 · informativo.** Lo que la revisión comprobó y está bien: nada borra ni
  mueve en iCloud; en Drive solo `copy`, `copyto`, `lsjson`, `lsl`, `md5sum` y
  `about`; `_sonda` y `.indice` no pueden ser destino de un cómic; solo se desaloja
  lo verificado (o lo apartado que materializó la herramienta); dedupe estable;
  estado fichero a fichero; colisiones de destino controladas; NFC en todo.

### Encontrado al aplicar los arreglos (sin tocar)

- **Contrato del índice con la app.** `app/…/metadata/Metadata.kt` (`IndexEntry`)
  espera `size`, `tipo` (`ZIP`, `RAR4`, `RAR4_SOLID`, `RAR5`, `SEVEN_ZIP`, `PDF`) y
  `ruta_original`; `indice.jsonl` escribe `tamano`, `formato` (`7Z`, y ahora
  también `TAR`) + `solido`, y `original`. Como la app tolera campos ausentes, no
  falla: lee `size = 0` y `tipo = null`. Hay que alinear un lado u otro (lo más
  barato: que el índice escriba también `size`, `tipo` y `ruta_original`) y luego
  `make mig-indice`.
- **Taxonomía.** `taxonomia.json` usa la categoría `Otro` en algunas entradas (no
  está entre las seis categorías: esas carpetas caen a la heurística) y un campo
  `subcarpeta` que la migración no lee (las subcarpetas de una serie van a la misma
  carpeta de Drive; los choques de nombre se resuelven conservando el nombre limpio).
