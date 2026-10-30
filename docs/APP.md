# La app: pantallas, flujos y ajustes (MVP, 30-oct-2026)

«Cómics» lee la carpeta «Cómics» de tu Google Drive en la TCL NXTPAPER 11 Plus. La biblioteca es un espejo de las
carpetas de Drive, con estantes en pilas de portadas, y el lector tiene los cuatro modos: una página, doble automática
en horizontal, vertical continuo y manga de derecha a izquierda. Lo que no está en la tablet se baja al abrirlo y se
guarda en una caché de 20 GB que siempre deja 15 GB libres. Un número o una serie se pueden «fijar» para leerlos sin
conexión.

La sonda del 30-oct sigue dentro de la app, en **Ajustes → Diagnóstico** (`docs/SONDA.md`).

## Instalar y arrancar

Antes de nada, la consola de Google tiene que estar hecha (`docs/CONSOLA-GOOGLE.md`): un cliente Android por paquete
(`mx.jano.comics.debug` y `mx.jano.comics`) con la SHA-1 de `make sha1`, la app publicada y tu correo como usuario.

```sh
cd ~/comic-reader
make connect   # empareja o conecta la tablet por Wi-Fi
make dev       # build debug «Cómics dev» (mx.jano.comics.debug) firmada con ~/.android/comics.jks + instalar + abrir
make deploy    # build diaria con R8 (mx.jano.comics) + instalar + abrir
make logs      # logcat de la app
make test      # pruebas JVM
```

Las dos builds conviven en la tablet, cada una con sus datos. Instala siempre con `make dev` o `make deploy`: un APK
firmado con otra clave no pasa el consentimiento de Google (`DEVELOPER_ERROR`).

**Primer arranque.** La app comprueba el token sin pantalla. Si hace falta permiso, enseña «Conectar Google Drive» y
abre la hoja nativa de Play Services, que pide `drive.readonly` y `drive.appdata`. Después busca «Cómics» en Mi unidad
(con o sin tilde, en NFC o NFD) y la propone con «Usar esta carpeta». Si no está, se abre un explorador que solo
enseña carpetas. La carpeta elegida se guarda en los ajustes, y desde ese momento la app arranca siempre en la
biblioteca.

## Pantallas

### Biblioteca

Es una rejilla de estantes y números con el mismo orden y los mismos nombres que tiene Drive.

- **Columnas.** Van según el tamaño de la ventana: 3 en compacto, 4-5 en vertical y 7-8 en horizontal.
- **Estantes.** Cada carpeta es una pila de 2-3 portadas desplazadas, o una tarjeta tipográfica si no hay portadas.
  Debajo lleva el resumen («30 cómics · 12 leídos») con una marca: un visto si están todos leídos, medio círculo si
  hay alguno en curso. El primer nivel de Drive (Americano, Europeo, Latinoamericano, Manga, Manhwa, Manhua y Otro) se ve
  como los estantes de la raíz.
- **Números.** Cada número enseña:
  - Su portada.
  - Su nombre corto: «Tomo 54 (#505-514)» dentro de «Naruto», «01» dentro de «Marvel 2099».
  - Su estado: «No leído», «Pág. X de Y» con barra, o «Leído».
  - Dónde está: nube, anillo con el % mientras baja, tablet o chincheta si está fijado.

  Ningún estado depende solo del color: siempre hay un icono o un texto.
- **Fondo.** Es la primera portada de la carpeta, difuminada bajo un velo oscuro. En alto contraste el fondo es negro
  liso.
- **Navegación.** Las migas de pan son pulsables. «Atrás» sube una carpeta, y en la raíz sale de la app con el gesto
  predictivo del sistema.
- **Tirar hacia abajo** vuelve a escanear Drive con un contador en vivo, y después relee `.indice`.
- **Filtro.** Las opciones son Todos, Por leer, Leyendo, Leídos y Sin conexión. Se recuerda entre sesiones.
- **Pulsación larga** sobre un número o una carpeta entera abre un menú con:
  - Marcar como leído o como no leído.
  - Fijar para leer sin conexión, o quitar el fijado. Con una carpeta, «Fijar la serie».
  - Borrar de la tablet. Quita antes el fijado.
  - Sentido de lectura: manga, occidental o heredar. La opción elegida lleva una marca de visto.
  - Ver la ficha de la serie.
- **«Continuar».** En la raíz hay una fila con lo que estás leyendo.
- **Sin conexión.** Aparece un aviso arriba y lo que no está en la tablet sale atenuado con una nube tachada. Si
  intentas abrirlo, un diálogo explica por qué no se puede.

### Leyendo ahora

- Tarjetas grandes con los números a medias («Abierto hace 2 h»).
- «Siguientes»: en cada serie empezada, el primer número sin leer después del último que abriste.

### Ficha de la serie

Enseña estos datos:

- Título, título original, tipo, categoría, país, guion, dibujo, editorial original y de la edición, idioma, años,
  géneros, estado, sinopsis y notas.
- Cuántos números hay en la carpeta y cuántos se publicaron, y cuáles faltan («Faltan los números 1–3, 5»).
- Las fuentes, como enlaces.
- Un aviso si la ficha tiene confianza baja.

Sin datos dice «Sin ficha todavía». Todo esto sale de `Cómics/.indice` (ver «Metadatos»).

### Búsqueda

Busca en toda la biblioteca sin distinguir acentos ni mayúsculas, y cada palabra tiene que aparecer. Salen primero
las carpetas y después los números agrupados por carpeta, como mucho 400 con el total a la vista.

### Ajustes

| Sección | Ajuste | Por defecto |
|---|---|---|
| Apariencia | Tema: Cover oscuro / Alto contraste / Automático (NXTPAPER) | Automático |
| Lectura | Ajuste: Ancho / Alto / Ambos | Ambos |
| | Páginas: Una página / Doble en horizontal / Vertical continuo | Doble en horizontal |
| | Manga (derecha a izquierda) para todo | No |
| | Teclas de volumen, pantalla encendida, tocar los lados para pasar página | Sí, sí, sí |
| Descargas y caché | Tope de la caché: 10 / 20 / 40 / 100 GB | 20 GB |
| | Dejar siempre libre: 5 / 10 / 15 / 20 GB | 15 GB |
| | Descargar el siguiente número mientras lees | Sí |
| | Solo con Wi-Fi | Sí |
| Biblioteca | Carpeta raíz (Cambiar), Volver a escanear | |
| Progreso | Copiar a Drive, Restaurar de Drive, fecha de la última copia | |
| Avanzado | Diagnóstico (la sonda), versión | |

- **Estado de la caché.** Ajustes enseña el estado: usado, tope y libre en la tablet, en GB decimales como los
  Ajustes de Android, más cuántos números hay descargados y cuántos fijados.
- **«Vaciar caché».** Borra lo descargado salvo lo fijado.
- **Tema automático.** Enseña las claves NXTPAPER que lee ahora («Modo detectado: …»). Pon la tablet en Ink Paper y
  pulsa **«Este es el modo Ink Paper»**: desde entonces el alto contraste se pone y se quita solo al cambiar de modo
  con el botón de la tablet. «Olvidar» borra lo guardado.

### Lector

1. **Descargando.** Si el número no está en la tablet, se enseña un anillo con el %, «12,3 de 40,0 MB · 1.840 KB/s ·
   quedan 25 s» y «Cancelar».
2. **Abriendo.** Abre ZIP, RAR4 (también sólido), RAR5 y PDF.
3. **Leyendo.** Empieza en la página donde lo dejaste. Un número terminado se abre por el principio.
4. **Fin.** Después de la última página hay un pliego más con «Siguiente: <nombre>», su portada, «Leer» y «Volver».
   Si no hay siguiente, dice «Has terminado la carpeta».

**Modos y ajuste:**

- **Una página.**
- **Doble en horizontal.** La portada va sola, toda página apaisada va sola y después se vuelve a emparejar. Con la
  tablet en vertical se ve una página. En manga, la página menor va a la derecha.
- **Vertical continuo.** Las páginas van a lo ancho, una debajo de otra. El pellizco llega a 3× y el doble toque
  vuelve a 1×.
- **Manga.** El sentido va por libro, por carpeta o global: manda el libro, luego la carpeta más cercana y luego el
  ajuste global. Invierte el paso de página, los toques laterales y la barra.
- **Ajuste.** Ancho empieza arriba. Alto empieza por el lado de lectura. Ambos es la página entera.

**Gestos y teclas:**

- **Toques.** Un toque en el tercio izquierdo o derecho pasa página. Si la página desborda, primero la recorre en
  «Z» y después pasa. Un toque en el centro abre la superposición:
  - Arriba: Cerrar, el título, Ajuste, Páginas y Manga (sí, no o heredar para este libro).
  - Abajo: «5-6 / 36», «Pliego 3 de 20» y la barra.

  Ajuste y Páginas cambian el valor por defecto.
- **Teclas de volumen.** Arriba avanza y abajo retrocede, mientras lees y si el ajuste está activo.
- **«Atrás».** El borde de la pantalla está excluido del gesto «atrás» en la franja central de 200 dp de cada lado.
  Un «atrás» abre la superposición con el aviso «Atrás otra vez para salir del libro», y el segundo sale. Arregla el
  fallo UI-4 de la sonda.
- **Pantalla.** Va a pantalla completa con las barras del sistema ocultas mientras no está la superposición, y queda
  encendida mientras lees.

## Flujos

**Abrir un número.** La caché asegura la copia local con una descarga reanudable por `Range` y la comprobación de
versión, y verifica tamaño y md5. Luego aplica su tope sin tocar este número ni el siguiente, y el lector abre el
archivo. El progreso se guarda con un retardo de 500 ms y al salir. Al pasar del 80 % se pide el siguiente número por
adelantado (WorkManager, solo con Wi-Fi si así está el ajuste). Si el número no tenía portada (PDF y 7z no se pueden
sacar por prefijo), se genera con el archivo ya bajado.

**Fijar.** El número o la serie entra en una cola (`files/pins.json`) que baja un trabajo «user-initiated» de
JobScheduler:

- Lleva una notificación de progreso con Pausar, Reanudar y Cancelar. La primera vez que fijas, la app pide el permiso
  de notificaciones; sin él se baja igual.
- Lo fijado no se desaloja nunca, y la cola sobrevive a cerrar la app.

**Caché.** Los números van en `Android/data/<paquete>/files/books` y las páginas extraídas en `cache/pages/<id>`. No se
piden permisos de almacenamiento.

- **Desalojo.** Al pasar el tope, o al bajar de lo que hay que dejar libre, sale primero lo terminado, luego lo no
  empezado y por último lo que se está leyendo, siempre lo más antiguo antes.
- **El número que abres.** Puede pasar el tope, pero nunca deja menos de 512 MB libres.
- **Los fijados.** También pueden pasar el tope, pero nunca bajan del margen libre.

**Progreso.** Va ligado al contenido (`md5:size`), así que sobrevive a renombrar o mover el fichero en Drive. Se
guarda en `files/progress.json` y tiene copia en la carpeta oculta de la app en Drive (`appDataFolder/progress.json`):

- Al ir a segundo plano se copia, si cambió algo y hay red.
- Al arrancar se mezcla con lo local; gana el cambio más reciente de cada número. Tras reinstalar, el progreso vuelve
  solo.

**Escaneo.** Se recorre Drive por niveles, en lotes de 40 carpetas y 4 consultas a la vez. El índice queda en
`files/index.json` y la biblioteca se pinta con él al momento.

- Al arrancar se vuelve a escanear si no hay índice, si es de otra raíz o si tiene más de 6 h.
- Se ignoran los nombres que empiezan por «.» y los accesos directos.
- Si un escaneo devuelve vacía una raíz que tenía libros, se conserva el índice anterior.

**Metadatos y portadas** (`Cómics/.indice`, los genera la migración, `docs/MIGRACION.md`). Todo es tolerante: si
`.indice` no existe todavía, la app funciona igual.

- **Índice y fichas.** `indice.jsonl` y `series.jsonl` se bajan solo si cambiaron. La ficha de cada carpeta sale del
  campo `meta_serie` de `indice.jsonl`. La `ruta` de `series.jsonl` es la carpeta de origen en iCloud, y solo cuenta
  si coincide con una carpeta de Drive.
- **Portadas del índice.** `portadas.zip` (WebP por md5) se importa solo con Wi-Fi.
- **Portadas sin índice.** Lo que no está en el índice se saca por prefijo del archivo en Drive, como mucho 3 a la
  vez: el directorio central en ZIP y un prefijo creciente de 1, 2, 4 y 8 MB en RAR.

## Ficheros de la app en la tablet

| Dónde | Qué |
|---|---|
| `files/settings.json` | Ajustes y tabla de manga por ruta, escritos de forma atómica con `.bak` |
| `files/index.json` | Índice de la biblioteca |
| `files/progress.json` | Progreso de lectura, escrito de forma atómica con `.bak` |
| `files/covers/` | Portadas WebP de 300 px |
| `files/indice/` | Lo bajado de `Cómics/.indice` |
| `files/cache.json`, `files/pins.json` | Estado de la caché y cola de fijados |
| `files/ui-prefs.json` | Filtro, valores del modo Ink Paper y fecha de la última copia |
| `getExternalFilesDir("books")` | Los cómics descargados |
| `cacheDir/pages/` | Páginas extraídas |

## Código

Hay un solo módulo `:app`. El grafo (`AppGraph`) se monta a mano en `ComicsApp`, que además lo implementa para que el
trabajo de fijados y el worker del N+1 lo encuentren aunque el proceso arranque sin pantalla.

| Paquete | Contenido |
|---|---|
| `auth/` | Token con `AuthorizationClient` |
| `drive/` | REST de Drive (listar, `Range`, appDataFolder) |
| `archive/` | ZIP, RAR con junrar y PDF con `PdfRenderer` a JPEG de 1440 px |
| `library/` | Escáner, índice, nombres, progreso y portadas |
| `metadata/` | `.indice` |
| `settings/` | Ajustes |
| `cache/` | Caché en disco, fijados con UIDT y prefetch con WorkManager |
| `ui/` | Navegación por pila, biblioteca, leyendo ahora, ficha, búsqueda, ajustes, tema y primer arranque |
| `reader/` | Ruta del lector, motor de pliegos, encajes, vertical y superposición |
| `probe/` | La sonda («Diagnóstico») |

Los textos de la interfaz están en un `Strings.kt` por paquete, a la espera de la i18n.

Pruebas JVM: `make test`, o `./gradlew :app:testDebugUnitTest`. Son 376 pruebas en 44 suites, con 1 omitida
(`LocalComicsReportTest`, que necesita los cómics locales de la Mac).

## Fuera del MVP o pendiente

- **Sin probar en la tablet:**
  - El MVP entero: está compilado, probado en la JVM y con la build de release (R8) pasando, pero sin instalar.
  - Paso de página y pellizco en los pliegos dobles.
  - Ajuste a lo ancho con desplazamiento vertical.
  - La exclusión de bordes y el «atrás» en dos pasos con la SystemUI de TCL.
  - Las teclas de volumen.
  - El trabajo UIDT con su notificación.
  - El prefetch.
  - El peso real del JPEG de PDF: se midió menos de 1 MB por página con un sustituto en la JVM.
  - El WebP de las portadas.
- **El modo Ink Paper hay que marcarlo una vez** en Ajustes. Los valores de las claves en cada modo no se han medido
  todavía.
- **Formatos.** 7z (`.cb7`) y TAR (`.cbt`) salen en la biblioteca pero no se abren: falta libarchive. Hoy es 1 de
  159 ficheros locales.
- **Zoom.** En los pliegos dobles el zoom no tesela, así que a 2-3× se ve menos nítido que en una página. En vertical
  continuo el pellizco es simple, sin teselado.
- **Biblioteca:**
  - No hay `folder.jpg` ni `cover.jpg` como portada de carpeta.
  - No se resuelven los accesos directos de Drive.
  - No hay escaneo incremental con `changes.list`: cada escaneo relee todo.
- **Fichas.** Una serie repartida en subcarpetas (`subcarpeta` de la taxonomía) tiene ficha en cada subcarpeta con
  números, no en la carpeta que solo contiene subcarpetas.
- **`Cómics/_sonda/`.** Si sigue en Drive, sale como un estante más.
- **Notificación de fijados.** Si el sistema mata el proceso con la notificación a la vista, sus botones dejan de
  responder hasta abrir la app. La cola no se pierde.
- **Pendientes de la revisión de la sonda.** Siguen abiertos AD-2 y AD-3 (`docs/SONDA.md`).
- **Textos.** La i18n (pasar los `Strings.kt` a `strings.xml`) queda para después.
