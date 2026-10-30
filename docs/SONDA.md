# La sonda: qué mide y cómo se usa

El APK sonda es la primera rebanada de «Cómics»: entra en Drive con la hoja nativa de Play Services, lista la carpeta «Cómics», descarga un cómic con `Range` reanudable, lo abre con el motor de archivos y lo lee con el pager y Telephoto. El código se queda tal cual para el MVP. La pantalla de la sonda (`probe/SondaScreen.kt`) solo sirve para medir, y cada botón escribe en un registro con hora.

Build de desarrollo: paquete `mx.jano.comics.debug`, nombre «Cómics dev». Convive con la build diaria (`mx.jano.comics`).

## Antes de instalar

1. La consola de Google tiene que estar hecha (`docs/CONSOLA-GOOGLE.md`). Para la sonda importan tres cosas: el cliente Android **`mx.jano.comics.debug`** con la SHA-1 de `make sha1`, la app **publicada** («En producción», no «Testing») y el correo propio añadido como usuario.
2. La tablet, en la misma Wi-Fi y con la depuración inalámbrica activada.

## Instalar

```sh
cd ~/comic-reader
make connect      # empareja o conecta la tablet por Wi-Fi (toolkit 01-wireless.sh)
make dev          # build debug firmada con ~/.android/comics.jks (contraseña del llavero) + instalar + abrir
```

- `make logs` muestra el logcat de la app. `adb logcat -s Sonda` muestra solo las trazas de Drive y del token: tiempos, bytes, KB/s y esperas por cuota. Nunca aparecen el token, la cabecera `Authorization` ni el correo.
- `make shot` guarda una captura en `dist/shots/`. Úsalo para fotografiar la hoja de consentimiento.
- No instales a mano un APK firmado con otra clave: Google contestaría `DEVELOPER_ERROR` (código 10), porque la SHA-1 no es la de la consola. Instala siempre con `make dev`.

## Los botones, sección por sección

Cada acción escribe en el registro una línea `> Nombre` al empezar y otra `Nombre: fin (N ms)` al acabar. Solo puede haber una acción a la vez: mientras una está en marcha, los demás botones se apagan. Los errores salen como `ERROR clase: mensaje` y no cierran la app.

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

**Qué apuntar:**
- Si `secure/nxtpaper_ink_style_state` da un valor o `SecurityException`.
- Con la vigilancia encendida, pulsa el botón NXTPAPER y pasa por Normal → Color Paper → Ink Paper → Normal. En cada modo, apunta qué claves cambian y con qué valor. Hoy, con el tema «Einkpaper dark», `nxtpaper_ink_style_state` vale 2.

### 2. Conectar Drive

Se piden `drive.readonly` y `drive.appdata` en una sola petición, sin client ID (Play Services identifica la app por el paquete y la SHA-1).

- **«Conectar Drive»**: la primera vez Play Services abre su hoja nativa. Al volver, el registro dice cuánto tiempo estuvo abierta, el código de resultado, los scopes concedidos y, si se desmarcó alguno, `FALTAN scopes: …`. Después pide el token sin pantalla y apunta los ms. El primer token correcto escribe `primer-token.txt` con la fecha.
- **«Token silencioso»**: pide el token sin forzar. Lo correcto es `token obtenido (N caracteres) en X ms, SIN pantalla`. Si sale `HIZO FALTA PANTALLA`, el silencio se perdió.
- **«Token nuevo (forzar)»**: borra el token de la caché de Play Services (`clearToken`) y pide otro. Mide el coste de renovarlo y comprueba que sigue sin pantalla.

**Qué apuntar (incógnita 1):**
- Si la hoja nativa enseña «Google no ha verificado esta app» y si «Avanzado → Ir a Comics» deja seguir. Haz una captura de cada pantalla con `make shot`.
- Si aparecen las dos casillas de permiso, y márcalas las dos.
- Los ms del primer token.
- Si sale `DEVELOPER_ERROR`, revisa el paquete `mx.jano.comics.debug` y la SHA-1 en la consola.

**Prueba de los 8 días (incógnita 6):** sin desinstalar la app, pulsa «Token silencioso» el día 8 o después. Apunta la línea `primer token: …, hace N días` y si salió `SIN pantalla`. En `adb logcat -s Sonda` cada token silencioso dice también `X días desde el consentimiento`.

### 3. Listar Cómics

- `about`: la cuenta conectada y la cuota usada, en ms.
- `findFolder("Cómics", root)`. Si no la encuentra, lista las carpetas de Mi unidad y busca el nombre normalizado (NFC, sin espacios a los lados, sin mayúsculas).
- `listChildren` de «Cómics»: número de elementos y de carpetas, y los 10 primeros.
- Una búsqueda en anchura de muestras: como mucho 20 carpetas y 50 candidatos (`.cbr`, `.cbz` o `.pdf` de 80 MB o menos). Se elige el primero, y basta tocar otro de la lista para usarlo.

**Qué apuntar:** los ms de `findFolder` y de cada `listChildren`, y si encontró «Cómics» a la primera.

### 4. Descargar (Range)

- **«Descargar»**: baja la muestra a la caché privada (`cache/sonda/<id>.<ext>`). La barra muestra MB en disco y MB/s. Al acabar compara el tamaño y el md5 con los que da Drive.
- **«Cortar»**: cancela a medias. Queda en disco un prefijo válido.
- Vuelve a pulsar **«Descargar»**: el registro dice `reanuda desde el byte N … con Range: bytes=N-` y, al acabar, `tamaño coincide` y `md5 coincide`.
- **«Borrar del disco»**: borra la muestra y sus páginas extraídas, para repetir la prueba.

**Qué apuntar (incógnita 2):** los MB/s por Wi-Fi, si la reanudación sigue desde el byte en que se cortó, y si el md5 coincide después de cortar y reanudar. En logcat `Sonda` salen las esperas por cuota (429/403) si las hay.

### 5. Abrir archivo

- `sniff`: el formato real según la cabecera, en ms. Hay ZIP y 7z disfrazados de `.cbr`.
- `open`: el formato, el número de páginas y los ms. También los nombres de las 12 primeras páginas, para comprobar el orden natural (1, 2, 10; no 1, 10, 2).
- Extrae la primera, la del medio y la última, con los ms de cada una, los KB y el tamaño en píxeles. Al final, la memoria Java usada antes y después.

**Qué apuntar (incógnita 3):** los ms de `open` y de cada página en el Helio G100. En la Mac, una página RAR4 tarda unos 8 ms (p50). Pruébalo con un RAR4, un RAR5 y un ZIP, y con un RAR sólido si aparece alguno; en la Mac no había ninguno (`docs/INFORME-ARCHIVOS-LOCALES.md`). Si hay un PDF entre los candidatos, apunta también lo que tarda una página: se dibuja a 2000 px de ancho en PNG. Un 7z da un error claro a propósito: aún no se lee.

### 6. Leer

Primero hay que «Abrir archivo». El interruptor **«Manga RTL»** invierte el sentido y **«Leer»** abre el lector.

- Las páginas pasan con un deslizamiento horizontal. Pellizco y doble toque hacen zoom, hasta 3×.
- Un toque en el tercio izquierdo o derecho pasa de página al instante, sin animación (en RTL los lados se invierten).
- Un toque en el tercio central muestra «Pág. X de Y», la barra para saltar y «Cerrar».
- «Atrás» cierra primero esa capa y después el lector.
- El lector siempre empieza en la página 1.

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

## Las incógnitas que resuelve

| # | Incógnita | Dónde se mide | Resuelta cuando |
|---|-----------|---------------|-----------------|
| 1 | La hoja nativa de consentimiento (AuthorizationClient), ¿deja pasar el aviso «Google no ha verificado esta app» con el scope restringido `drive.readonly` en un proyecto publicado sin verificar? | 2 · «Conectar Drive» | Hay capturas de la hoja y el registro dice `scopes concedidos` con `drive.readonly` |
| 2 | Listar y descargar con `Range` reanudable | 3 · «Listar Cómics», 4 · «Descargar» / «Cortar» / «Descargar» | `reanuda desde el byte N` seguido de `tamaño coincide` y `md5 coincide` |
| 3 | junrar 8.1.2 en el Helio G100 con RAR4, RAR4 sólido y RAR5 reales | 5 · «Abrir archivo» con cada tipo | ms de `open` y de cada página para cada tipo (el sólido, solo si aparece uno) |
| 4 | Telephoto + HorizontalPager sin robo de gestos | 6 · «Leer» | Pellizco, arrastre en el borde y toques laterales se comportan bien en la tablet |
| 5 | Leer el modo NXTPAPER desde una app normal. Las `tct_ink_mode_*` no existen; la candidata real es `secure/nxtpaper_ink_style_state` | 1 · Dispositivo + vigilancia + botón NXTPAPER | Valor (o `SecurityException`) de `nxtpaper_ink_style_state` en Normal, Color Paper e Ink Paper |
| 6 | El token silencioso a los 8 días | 2 · «Token silencioso» el día 8 | `hace 8,xx días` y `SIN pantalla` |

## Hoja para apuntar

```
Fecha / hora:
Modo NXTPAPER al empezar:
1. nxtpaper_ink_style_state  Normal=      Color Paper=      Ink Paper=      (¿SecurityException?)
   Otras claves que cambian al pulsar el botón:
2. Hoja: ¿aviso de app no verificada?    ¿«Avanzado → Ir a Comics»?    ¿dos casillas?
   Primer token: ___ ms     Silencioso: ___ ms     Forzado: ___ ms
3. findFolder: ___ ms   listChildren(Cómics): ___ elementos en ___ ms
4. Muestra (nombre, MB):            MB/s:        Corte en byte:        ¿Reanudó?   ¿md5?
5. Tipo:        open: ___ ms   pág. 1 / medio / última: ___ / ___ / ___ ms
   (repetir con RAR4, RAR5, ZIP y PDF si los hay)
6. Lector: ¿zoom → borde → página siguiente?   ¿pellizco robado?   ¿toque lateral lento?   ¿fantasma?
Día 8: «Token silencioso»  →  hace ___ días   ¿SIN pantalla?
```

## Lo que la sonda no cubre todavía

- 7z: un `.cbr` de la Mac lo es y da `UnsupportedArchiveException` a propósito (falta libarchive).
- Los 5xx y los cortes de red de Drive no se reintentan solos: se repite el botón y la descarga sigue desde donde quedó.
- El lector no recuerda la página. Al cerrar y volver a «Leer» empieza en la 1.
- La vía PDF (`PdfRenderer`) y la caché de RAR sólidos solo están probadas en la Mac con ficheros de prueba. La tablet es la primera vez de verdad.
