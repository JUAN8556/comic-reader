# La sonda: qué mide y cómo se usa

El APK sonda es la primera rebanada de «Cómics»: entra en Drive con la hoja nativa de Play Services, lista la carpeta «Cómics», descarga un cómic con `Range` reanudable, lo abre con el motor de archivos y lo lee con el pager y Telephoto. El código se queda tal cual para el MVP. La pantalla de la sonda (`probe/SondaScreen.kt`) solo sirve para medir, y cada botón escribe en un registro con hora.

Build de desarrollo: paquete `mx.jano.comics.debug`, nombre «Cómics dev». Convive con la build diaria (`mx.jano.comics`).

**Desde el MVP (30-oct) la app arranca en la biblioteca** y la sonda vive en **Ajustes → Diagnóstico**, con los mismos botones y el mismo registro. Pantallas, flujos y ajustes de la app: `docs/APP.md`.

## Antes de instalar

1. La consola de Google tiene que estar hecha (`docs/CONSOLA-GOOGLE.md`). Para la sonda importan tres cosas: el cliente Android **`mx.jano.comics.debug`** con la SHA-1 de `make sha1`, la app **publicada** («En producción», no «Testing») y el correo propio añadido como usuario.
2. La tablet, en la misma Wi-Fi y con la depuración inalámbrica activada.

## Instalar

```sh
cd ~/comic-reader
make connect      # empareja o conecta la tablet por Wi-Fi (toolkit 01-wireless.sh)
make dev          # build debug firmada con ~/.android/comics.jks (contraseña del llavero) + instalar + abrir
```

La app abre la biblioteca (o el primer arranque, si aún no hay carpeta elegida). Para la sonda: Ajustes → Diagnóstico.

- `make logs` muestra el logcat de la app. `adb logcat -s Sonda` muestra solo las trazas de Drive y del token: tiempos, bytes, KB/s y esperas por cuota. Nunca aparecen el token, la cabecera `Authorization` ni el correo.
- `make shot` guarda una captura en `dist/shots/`. Úsalo para fotografiar la hoja de consentimiento.
- No instales a mano un APK firmado con otra clave: Google contestaría `DEVELOPER_ERROR` (código 10), porque la SHA-1 no es la de la consola. Instala siempre con `make dev`.

## Los botones, sección por sección

Cada acción escribe en el registro una línea `> Nombre` al empezar y otra `Nombre: fin (N ms)` al acabar. Solo puede haber una acción a la vez: mientras una está en marcha, los demás botones se apagan, salvo «Vigilancia de ajustes» y «Copiar registro», que no compiten con nada (y «Cortar», que es para eso). Los errores salen como `ERROR clase: mensaje` y no cierran la app.

### 1. Dispositivo

Se ejecuta al arrancar la app. **«Repetir lectura»** lo vuelve a lanzar.

Mide lo siguiente:
- El modelo, Android, el parche, la versión de Play Services y la de WebView.
- El tamaño de página de memoria, la RAM, el heap (`memoryClass`, `largeMemoryClass`, `maxMemory`) y el espacio libre.
- Los modos de pantalla (resolución, Hz y dpi), `navigation_mode` y `animator_duration_scale`.

Después vienen las claves de NXTPAPER, en tres grupos:
- **Las 13 claves que adb sí vio el 29-oct** (`docs/DISPOSITIVO-2026-10-29.md`), leídas por su nombre desde la app. La importante es `secure/nxtpaper_ink_style_state`. Cada línea da el valor, `null` o `SecurityException`.
- Las 8 candidatas de la investigación (`tct_ink_mode_*` y parecidas) en System, Secure y Global. Se espera `null` en todas: adb dice que no existen.
- El volcado de `content://settings/{system,secure,global}`, filtrado a lo que suena a modo de papel: ink, paper, tct, nxt, color_mode, eyeprotect, reading_mode e installed_theme.

**«Vigilancia de ajustes»** arranca encendida. Mientras lo está, registra cada ajuste que cambie, como `ajuste cambiado: tabla/clave = valor`. Si la clave suena a modo de papel, la línea lleva `[modo de papel]`.

**«Leer modo ahora»** lee solo las 13 claves que adb vio, al momento, bajo el título `NXTPAPER ahora (apunta en qué modo está la tablet):`. Sirve para dejar el valor de cada modo en el registro aunque TCL cambie el modo sin escribir en Settings (o lo escriba tarde) y la vigilancia no vea nada.

**Qué apuntar:**
- Si `secure/nxtpaper_ink_style_state` da un valor o `SecurityException`.
- Con la vigilancia encendida, pulsa el botón NXTPAPER y pasa por Normal → Color Paper → Ink Paper → Normal. **En cada modo, espera dos segundos y pulsa «Leer modo ahora»**; apunta qué claves cambian y con qué valor, y si la vigilancia lo avisó sola (`ajuste cambiado: …`) o solo se ve en la lectura. Hoy, con el tema «Einkpaper dark», `nxtpaper_ink_style_state` vale 2.

### 2. Conectar Drive

Se piden `drive.readonly` y `drive.appdata` en una sola petición, sin client ID (Play Services identifica la app por el paquete y la SHA-1).

- **«Conectar Drive»**: la primera vez Play Services abre su hoja nativa. Al volver, el registro dice cuánto tiempo estuvo abierta, el código de resultado, los scopes concedidos y, si se desmarcó alguno, `FALTAN scopes: …`. Después pide el token sin pantalla y apunta los ms. El primer token correcto escribe `primer-token.txt` con la fecha.
- Tras cada botón de esta sección sale `autorización guardada: cuenta fijada sí/no · scopes concedidos (N): … · consentimiento: <fecha>, hace N días · último token silencioso: <fecha>, hace N días`. Es lo que Play Services dejó guardado (sin token ni correo), así que queda en `sonda.log` desde cuándo cuenta la prueba de los 8 días y qué scopes siguen concedidos.
- **«Token silencioso»**: pide el token sin forzar. Lo correcto es `token obtenido (N caracteres) en X ms, SIN pantalla`. Si sale `HIZO FALTA PANTALLA`, el silencio se perdió.
- **«Token nuevo (forzar)»**: borra el token de la caché de Play Services (`clearToken`) y pide otro. Mide el coste de renovarlo y comprueba que sigue sin pantalla.

**Qué apuntar (incógnita 1):**
- Si la hoja nativa enseña «Google no ha verificado esta app» y si «Avanzado → Ir a Comics» deja seguir. Haz una captura de cada pantalla con `make shot`.
- Si aparecen las dos casillas de permiso, y márcalas las dos.
- Los ms del primer token.
- Si sale `DEVELOPER_ERROR`, revisa el paquete `mx.jano.comics.debug` y la SHA-1 en la consola.

**Prueba de los 8 días (incógnita 6):** sin desinstalar la app, pulsa «Token silencioso» el día 8 o después. Apunta si salió `SIN pantalla` y la línea `autorización guardada: … consentimiento: <fecha>, hace N días` (la prueba cuenta desde el consentimiento; `primer token: …, hace N días` cuenta desde el primer token, que suele ser el mismo momento).

### 3. Listar Cómics

- `about`: la cuenta conectada y la cuota usada, en ms.
- `findFolder("Cómics", root)`. Si no la encuentra, lista las carpetas de Mi unidad y busca el nombre normalizado (NFC, sin espacios a los lados, sin mayúsculas).
- `listChildren` de «Cómics»: número de elementos y de carpetas, y los 10 primeros.
- Una búsqueda en anchura de muestras: como mucho 20 carpetas y 50 candidatos (`.cbr`, `.cbz` o `.pdf` de 80 MB o menos). Se elige el primero, y basta tocar otro de la lista para usarlo.
- **«Mirar cabeceras»**: baja 1 KB de cada candidato (`Range: bytes=0-1023`) y dice su formato real por la cabecera: `RAR4`, `RAR4 sólido`, `RAR5`, `RAR5 sólido`, `ZIP`, `PDF`, `7z`. La lista los muestra delante del nombre (`[RAR5] …`) y el registro da el recuento. Así se elige un RAR5 o un ZIP disfrazado sin descargar a ciegas (en la Mac solo 2 de 159 eran RAR5).

**Qué apuntar:** los ms de `findFolder` y de cada `listChildren`, si encontró «Cómics» a la primera y el recuento de `cabeceras: …`.

### 4. Descargar (Range)

- **«Descargar»**: baja la muestra a la caché privada (`cache/sonda/<id>.<ext>`). La barra muestra MB en disco y MB/s. Al acabar compara el tamaño y el md5 con los que da Drive. Antes de bajar, el cliente pide la versión del fichero (`headRevisionId`, `md5Checksum`, `size`) y la deja junto a la descarga en `<id>.<ext>.meta`.
- **«Cortar»**: cancela a medias. Queda en disco un prefijo válido.
- Vuelve a pulsar **«Descargar»**: el registro dice `hay N bytes en disco: se intenta reanudar …`, luego `drive: download: el parcial de N bytes es de la versión actual (…); se reanuda` y, al acabar, **`reanudó: sí. Range honrado: el servidor sirvió desde el byte N (206 + Content-Range)`**, `tamaño coincide` y `md5 coincide`.
- Si en Drive hay otra versión del fichero (mismo id, otro md5 o revisión), sale `drive: download: descarto el parcial … (el md5 cambió en Drive)` y se baja desde cero: nunca se pegan bytes de la versión nueva detrás de los de la vieja. Si el servidor ignora el `Range` (200), sale `drive: download: el servidor ignoró Range (200)`. En los dos casos el veredicto es `reanudó: NO …`.
- Las trazas del cliente de Drive (códigos HTTP, 206/200/416, 401 y renovación, esperas por cuota 429/403, KB/s) salen en el registro con el prefijo `drive:`, además de en logcat.
- **«Borrar del disco»**: borra la muestra, su `.meta` y sus páginas extraídas, para repetir la prueba.

**Qué apuntar (incógnita 2):** los MB/s por Wi-Fi, la línea `reanudó: …` después de cortar y volver a descargar, y si el md5 coincide. Solo cuenta como reanudación `reanudó: sí. Range honrado …`: `tamaño coincide` y `md5 coincide` también salen cuando se bajó todo desde cero.

### 5. Abrir archivo

- `sniff`: el formato real según la cabecera, en ms (con `RAR5 sólido` si lo es). Hay ZIP y 7z disfrazados de `.cbr`.
- `open`: el formato, el número de páginas y los ms. También los nombres de las 12 primeras páginas, para comprobar el orden natural (1, 2, 10; no 1, 10, 2).
- `motor: …`: lo que dice el motor. En RAR: formato según junrar (`RAR15` = RAR 1.5-4.x, `RAR50` = RAR5), sólido sí/no según junrar (un RAR5 sólido sale como `RAR5` en el tipo, pero aquí dice `sólido sí`), método de la 1.ª página (`0x30 (guardado)` … `0x35 (máximo)`; en RAR5, `0 (guardado)` o comprimido), ventana máxima, páginas guardadas leídas sin junrar y bytes comprimidos leídos. Se repite al final como `motor tras extraer: …`.
- Extrae la primera, la del medio, la última y, después, la anterior a la del medio `(hacia atrás)`, con los ms de cada una, los KB, el tamaño en píxeles y **la vía**: `junrar`, `lectura directa (guardada, sin junrar)`, `junrar sólido: N entradas descomprimidas para llegar a ella` o `caché de sólidos (acierto …)`, más los KB comprimidos leídos. Al final, la memoria Java usada antes y después.
- **«Sólido sintético»**: genera la primera vez (unos segundos; se queda en `cache/sonda/sintetico/`) un RAR4 sólido de 60 páginas JPEG de 1200×1800 y lo abre con el mismo análisis; luego se puede «Leer». Las entradas van guardadas, sin comprimir, porque no hay `rar` con el que comprimir: mide el camino sólido de junrar (salto entre entradas, reinicio hacia atrás) y la caché de sólidos en el almacenamiento de la tablet, **no** la descompresión LZ de un sólido real. Ojo: el fallo latente de junrar con entradas guardadas en sólidos (`docs/INFORME-ARCHIVOS-LOCALES.md`, punto 3) podría salir aquí; si sale, es un dato.

**Qué apuntar (incógnita 3):** los ms de `open` y de cada página en el Helio G100, **con su vía**. En la Mac, una página RAR4 tarda unos 8 ms (p50). Pruébalo con un RAR4 cuya 1.ª página sea comprimida (vía `junrar`), uno guardado (vía `lectura directa`: eso no mide junrar), un RAR5 y un ZIP (usa «Mirar cabeceras» para encontrarlos), y con «Sólido sintético» (la vuelta `(hacia atrás)` debe ser un acierto de caché). Si aparece un RAR sólido de verdad, ese manda. Si hay un PDF entre los candidatos, apunta también lo que tarda una página: desde el MVP se dibuja a 1440 px de ancho en JPEG de calidad 85 (antes, PNG de 2000 px). Un 7z da un error claro a propósito: aún no se lee.

### 6. Leer

Primero hay que «Abrir archivo». El interruptor **«Manga RTL»** invierte el sentido y **«Leer»** abre el lector.

Desde el MVP la sonda usa el mismo núcleo que el lector de la app, con ajustes locales que no se guardan:

- Con la tablet en horizontal enseña pliegos dobles: la portada va sola y las páginas apaisadas también.
- Las páginas pasan con un deslizamiento horizontal. Pellizco y doble toque hacen zoom, hasta 3×.
- Un toque en el tercio izquierdo o derecho pasa de página al instante, sin animación (en RTL los lados se invierten).
  Si la página desborda, primero la recorre.
- Un toque en el tercio central abre la superposición: «Pág. X de Y», la barra para saltar, Ajuste, Páginas y «Cerrar».
- «Atrás» abre la superposición con el aviso «Atrás otra vez para salir del libro». Con ella abierta, sale.
- Los bordes de la pantalla están excluidos del gesto «atrás» del sistema (UI-4).
- El lector siempre empieza en la página 1.
- Mientras está abierto escribe en el registro, con el prefijo `lector:`, cada página asentada, lo que tardó cada extracción (`extraída en N ms (KB), M ms esperando el archivo`), cuándo se dibujó cada página (`dibujada (en pantalla|precargada) N ms después de componerse`) y los errores. Los gestos (pellizco, arrastre en el borde) no dejan traza: se apuntan a mano.

**Qué apuntar (incógnita 4):**
- Con zoom, si al llegar al borde de la imagen el arrastre pasa a la página siguiente.
- Si el pager roba el pellizco.
- Si el zoom vuelve a 1× al cambiar de página.
- Si los toques laterales se notan lentos (Telephoto espera un instante para distinguir el doble toque).
- Cómo se ven el refresco y el fantasma en Ink Paper y en Color Paper.

La navegación es por gestos: el deslizamiento que empieza en el mismo borde es «atrás». Empieza los deslizamientos a 24-32 dp del borde, o sea, a un dedo dentro.

### 7. Registro

- **«Copiar registro»** copia al portapapeles las últimas 2000 líneas.
- La pantalla muestra la ruta del fichero y el `adb pull` listo para copiar.

## Sacar el registro a la Mac

```sh
cd ~/comic-reader && mkdir -p dist/logs
adb pull /sdcard/Android/data/mx.jano.comics.debug/files/sonda/sonda.log dist/logs/
adb pull /sdcard/Android/data/mx.jano.comics.debug/files/sonda/primer-token.txt dist/logs/
adb logcat -d -s Sonda > dist/logs/sonda-logcat.txt
```

Si adb ve más de un dispositivo, antepón `ANDROID_SERIAL=$(adb devices | sed -n '2p' | cut -f1)` como hace el Makefile. El registro se va añadiendo al mismo fichero y cada arranque empieza con `=== sesión … ===`. `sonda.log` contiene el correo de la cuenta y los nombres de los cómics: se queda en `dist/`, que no entra en el repo (está en `.gitignore`).

`sonda.log` ya lleva las trazas de Drive (`drive: …`), las del lector (`lector: …`) y el estado guardado de la autorización. Lo único que sigue solo en logcat son los detalles internos del token (ms de `authorize` y de `clearToken`). Logcat es un búfer circular: **saca `sonda-logcat.txt` en la misma sesión**, justo después de las pruebas; el día 8 ya no quedarán las del día 1.

## Las incógnitas que resuelve

| # | Incógnita | Dónde se mide | Resuelta cuando |
|---|-----------|---------------|-----------------|
| 1 | La hoja nativa de consentimiento (AuthorizationClient), ¿deja pasar el aviso «Google no ha verificado esta app» con el scope restringido `drive.readonly` en un proyecto publicado sin verificar? | 2 · «Conectar Drive» | Hay capturas de la hoja y el registro dice `scopes concedidos` con `drive.readonly` |
| 2 | Listar y descargar con `Range` reanudable | 3 · «Listar Cómics», 4 · «Descargar» / «Cortar» / «Descargar» | `reanudó: sí. Range honrado: el servidor sirvió desde el byte N (206 + Content-Range)` seguido de `tamaño coincide` y `md5 coincide` |
| 3 | junrar 8.1.2 en el Helio G100 con RAR4, RAR4 sólido y RAR5 reales | 3 · «Mirar cabeceras», 5 · «Abrir archivo» con cada tipo y «Sólido sintético» | ms de `open` y de cada página con su vía: RAR4 por `junrar` (no solo `lectura directa`), RAR5, y el sólido sintético con la vuelta atrás como acierto de caché. El sólido comprimido real queda pendiente hasta que aparezca uno |
| 4 | Telephoto + HorizontalPager sin robo de gestos | 6 · «Leer» | Pellizco, arrastre en el borde y toques laterales se comportan bien en la tablet |
| 5 | Leer el modo NXTPAPER desde una app normal. Las `tct_ink_mode_*` no existen; la candidata real es `secure/nxtpaper_ink_style_state` | 1 · Dispositivo + vigilancia + botón NXTPAPER + «Leer modo ahora» en cada modo | Valor (o `SecurityException`) de `nxtpaper_ink_style_state` en Normal, Color Paper e Ink Paper, y si la vigilancia avisa del cambio |
| 6 | El token silencioso a los 8 días | 2 · «Token silencioso» el día 8 | `SIN pantalla` y `autorización guardada: … consentimiento: …, hace 8,xx días` |

## Hoja para apuntar

```
Fecha / hora:
Modo NXTPAPER al empezar:
1. nxtpaper_ink_style_state  Normal=      Color Paper=      Ink Paper=      (¿SecurityException?)
   ¿La vigilancia avisó sola o solo «Leer modo ahora»?
   Otras claves que cambian al pulsar el botón:
2. Hoja: ¿aviso de app no verificada?    ¿«Avanzado → Ir a Comics»?    ¿dos casillas?
   Primer token: ___ ms     Silencioso: ___ ms     Forzado: ___ ms
3. findFolder: ___ ms   listChildren(Cómics): ___ elementos en ___ ms   cabeceras: RAR4 __ RAR5 __ ZIP __ PDF __
4. Muestra (nombre, MB):            MB/s:        Corte en byte:        «reanudó: sí. Range honrado»?   ¿md5?
5. Tipo:        vía:          open: ___ ms   pág. 1 / medio / última / atrás: ___ / ___ / ___ / ___ ms
   (repetir con RAR4 por junrar, RAR5, ZIP, «Sólido sintético» y PDF si lo hay)
6. Lector: ¿zoom → borde → página siguiente?   ¿pellizco robado?   ¿toque lateral lento?   ¿fantasma?
Día 8: «Token silencioso»  →  hace ___ días   ¿SIN pantalla?
```

## Lo que la sonda no cubre todavía

- 7z: un `.cbr` de la Mac lo es y da `UnsupportedArchiveException` a propósito (falta libarchive).
- Los 5xx y los cortes de red de Drive no se reintentan solos: se repite el botón y la descarga sigue desde donde quedó.
- El lector de la sonda no recuerda la página. Al cerrar y volver a «Leer» empieza en la 1. El de la app sí la recuerda (`docs/APP.md`).
- La vía PDF (`PdfRenderer`) y la caché de RAR sólidos solo están probadas en la Mac con ficheros de prueba. La tablet es la primera vez de verdad.
- Un RAR sólido COMPRIMIDO real: «Sólido sintético» lleva las entradas guardadas, así que no mide la descompresión LZ de un sólido.
- Los experimentos E2 y E3 de la propuesta v2 (`PROPUESTA-LECTOR-COMICS-v2-DRIVE-2026-10-29.md`, «La fricción real»): el picker con `drive.file` y `allow_folder_selection`, y si `drive.file` ve lo que rclone subió con el cliente Desktop del mismo proyecto. No están en la sonda y no se han descartado: son la vía con scopes no sensibles (sin aviso de app no verificada), a cambio de ver solo una parte de Drive. Pasan a ser urgentes si la hoja nativa no deja pasar el aviso con `drive.readonly` (incógnita 1).

## Pendientes de la revisión

La revisión de la sonda (30-oct) dejó estos puntos de severidad baja o inciertos. ARCH-5 y UI-4 quedan resueltos en el MVP (falta verlos en la tablet); el resto sigue abierto.

- **AD-2 · 416 sin `Content-Range` legible se da por completo** (`drive/DriveRestApi.kt`, rama 416). Si `parseUnsatisfiedTotal` devuelve null se entra en `Done(bytes = 0)` sin prueba. Drive manda `bytes */N` y la conexión es TLS, así que hoy no debería pasar; ahora además la versión se comprueba antes de pedir el rango. Propuesta: tratar `remoteSize == null` como `Restart` y dejar `Done` solo para `remoteSize == offset`.
- **AD-3 · `forgetDeclinedScopes()` y `forgetAccount()` sin llamador** (`auth/PlayServicesTokenProvider.kt`). Si la hoja ofrece casillas por scope y se desmarca `drive.appdata`, a partir de ahí solo se pide `drive.readonly` en silencio y no hay forma de volver a pedir los dos sin borrar los datos de la app (lo que también reinicia la prueba de los 8 días). Depende de si la hoja nativa ofrece casillas (incógnita 1). Propuesta: botón «Olvidar cuenta y scopes» o que «Conectar Drive» llame a `forgetDeclinedScopes()`; y si `authorize()` falla con cuenta fijada, olvidarla y reintentar una vez.
- **ARCH-5 · RESUELTO en el MVP:** JPEG de calidad 85 a 1440 px, con `Matrix` y topes que conservan la proporción (16.384 px por lado, 16 Mpx). Falta medir los KB y los ms con un PDF en la tablet. Lo que decía la revisión: **PDF a PNG ARGB de 2000 px** (`archive/PdfComicArchive.kt`). 6-12 MB por página y segundos de codificación estimados; un PDF de 200 páginas podría dejar ~2 GB en `paginas/<id>`. Una tira con proporción > 4 se renderiza aplastada (sin `Matrix`). Lo dicta el contrato («en PDF, un PNG renderizado»): decidir JPEG/WebP y el ancho de pantalla antes del MVP, con los ms y KB que dé «Abrir archivo» con un PDF en la tablet.
- **UI-4 · RESUELTO en el MVP, en tres capas:** los toques laterales son la vía principal para pasar página; `systemGestureExclusion` en la franja central de 200 dp de cada borde; y un «atrás» abre la superposición, de modo que hacen falta dos para salir. Falta comprobarlo con la SystemUI de TCL en inmersivo. Lo que decía la revisión: **El pager no excluye los bordes del gesto de Atrás** (`reader/ReaderScreen.kt`). Con `navigation_mode=2`, un deslizamiento que empiece en el borde es «atrás» y cierra el lector. Depende de cómo trate la SystemUI de TCL el borde en inmersivo: apuntarlo en la incógnita 4. Propuesta: `Modifier.systemGestureExclusion()` en el pager (Android respeta hasta 200 dp por borde).
- **Contrato, para cuando se pueda tocar**: `headRevisionId` en `DriveFile` y en `FILE_FIELDS` (hoy solo lo pide la comprobación de versión de `download`, por su cuenta); `RAR5_SOLID` en `ArchiveKind` (hoy un RAR5 sólido sale como `RAR5` y solo el diagnóstico `motor:` dice que es sólido); y si `download` debería recibir el `DriveFile` en vez del id para no pedir la versión otra vez.
- **Trazas del token**: los ms de `authorize` y de `clearToken` de `PlayServicesTokenProvider` siguen solo en logcat.
