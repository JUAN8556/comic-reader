# Resultados de la sonda (30-oct-2026, 01:00-01:25, tablet de Jano)

APK `mx.jano.comics.debug` 0.0.1-dev (main `407ebc0`), instalado en 15 s por Wi-Fi, arranque en frío 1,6 s. Registro completo en `dist/logs/sonda-2026-10-30.log` (no versionado).

## Incógnitas que la sonda tenía que resolver

| # | Incógnita | Resultado |
|---|-----------|-----------|
| 1 | Hoja nativa de Play Services con `drive.readonly` (restringido) en app publicada sin verificar | **Pasa.** FullScreenConsentActivity 16,3 s → RESULT_OK; scopes concedidos: `drive.readonly`, `drive.appdata` y `drive.file` (heredado del consentimiento de rclone: Google agrupa por proyecto+usuario). Token de 321 caracteres en 35 ms. |
| 2 | Listar y descargar con `Range` reanudable | **Pasa.** about 442 ms; raíz 33 elementos en 466 ms; `Cómics` encontrada por nombre. Corte a 20,5 MB → comprobación de versión (headRevisionId/md5/size) → `Range: bytes=21512192-` honrado (206 + Content-Range) → md5 final idéntico. Velocidad: 1,3-2,5 MB/s (≈10-20 Mb/s). |
| 3 | junrar 8.1.2 en el Helio G100 | **Pasa, mejor de lo estimado.** RAR4 0x35 (Naruto, 71 MB, 160 págs): open 79 ms; página 226 ms la primera (JIT) y 39-94 ms después. RAR4 guardado: 1 ms/pág (lectura directa). RAR5 (36 págs): open 42 ms, 45-330 ms/pág. ZIP: open 19 ms, 8-32 ms/pág. Sólido sintético (60 págs): 66-80 ms para llegar a la 31 o la 60, 2 ms hacia atrás por la caché. Memoria Java < 25 MB en todo momento. |
| 4 | Telephoto + HorizontalPager, RTL | **Pasa (QA de Jano):** paso de página, pellizco, arrastre con cesión al pager en el borde de la imagen, doble toque, superposición «Pág. X de Y» con barra, toques laterales, modo manga. En el lector: extracción 15-91 ms (mediana 38), dibujado 126-311 ms tras componerse, 0 errores. **Fallo confirmado (UI-4):** deslizar desde el borde de la pantalla dispara «atrás» del sistema y cierra el lector → `Modifier.systemGestureExclusion()` en el pager (Android respeta hasta 200 dp por borde) o toques laterales como vía principal. |
| 5 | Claves de modo NXTPAPER desde una app normal | **Se leen sin SecurityException.** `secure/nxtpaper_ink_style_state`, `system/color_mode_value`, `adv_color_mode_value`, `eyeprotect_*`. Falta anotar el valor en cada modo (botón «Leer modo ahora» en Normal / Color Paper / Ink Paper). |
| 6 | Token silencioso (prueba de 8 días) | 37 ms sin pantalla a los 8 minutos; «cuenta fijada: sí» tras el primer silencioso. **Repetir el 7-nov-2026.** |
| 7 | Registro recuperable | `adb pull` del `sonda.log`: 1.026 líneas con HTTP, token (sin valores), archivos y lector. |

## Otros datos
- La depuración inalámbrica de la TCL se apaga sola (al bloquear la pantalla o al apagarse): hay que reactivarla antes de cada sesión y conviene «Pantalla activa mientras se carga» en Opciones del desarrollador.
- «Kaiju No. 8» no aparece como carpeta de la raíz vista por la API (probablemente es un acceso directo): coherente con dejarla fuera.
- Muestras de prueba en `Cómics/_sonda/` (Doom 2099 #22, Immortal Hulk #6, 260 El árbol de la muerte, Iron Man Los Años Perdidos 1, Naruto Tomo 54): las quita la migración real.
- El selector de muestra de la sonda solo lista candidatos ≤ 80 MB.

## Consecuencias para el MVP
1. Vía de acceso confirmada: `AuthorizationClient` + REST. Planes B (cuenta de servicio) y C (FolderSync) descartados.
2. Descarga a ~1,5 MB/s: el lector necesita prefetch N+1 del cómic siguiente y el «fijar serie» en segundo plano; una grapa de 40 MB tarda ~25 s.
3. junrar sobra para RAR4/RAR5; la caché de sólidos ya está; libarchive solo para 7z (1 de 159 locales).
4. Arreglar UI-4 (bordes) y los pendientes de severidad baja de `docs/SONDA.md` dentro del lector del MVP.
5. Detección automática de modo NXTPAPER: viable; mapear valores por modo con la sonda.
