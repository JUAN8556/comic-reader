# La tablet, medida por adb (29-oct-2026, solo lectura)

Serial mDNS: `adb-A06B4A578993153-MBHzQn._adb-tls-connect._tcp` (adb 37 la encuentra solo; el puerto cambia, no se fija).

| Dato | Valor |
|------|-------|
| Modelo | TCL NXTPAPER 11 Plus (9469X, Bellona_WF_GL) |
| Android | 15 (SDK 35), parche de seguridad 2026-04-05, build AP3A.240905.015.A2 |
| Pantalla | 1440×2200 px físicos, 320 dpi (= 450×687,5 dp) |
| Navegación | `navigation_mode=2` → **gestos** (el deslizamiento desde el borde es «atrás»; el paso de página debe empezar a ≥24-32 dp del borde) |
| Página de memoria | `PAGE_SIZE=4096` → 4 KB; las .so de 16 KB no son requisito aquí (sí para el futuro) |
| Play Services | 26.41.31 (260400-1002748488) → AuthorizationClient disponible |
| WebView | 155.0.8059.46 |
| Google Drive (app) | 2.26.427.3 instalada |
| Almacenamiento | 216 GB, **203 GB libres** |
| RAM | 8 GB (3 GB libres en reposo) |

## Claves de modo NXTPAPER que existen de verdad (Settings)

La investigación suponía `tct_ink_mode_*`; **no existen**. Lo que hay:

- `system`: `color_mode_value=1`, `adv_color_mode_value=10`, `eyeprotect_kind=2`, `eyeprotect_status=0`, `nxtpaper_key_light_switches={…}`, `aikey_settings_single_press` (la tecla NXTPAPER abre «Modo NXTPAPER»).
- `secure`: **`nxtpaper_ink_style_state=2`**, `nxtpaper_style_before_apply_theme=2`, `nxtpaper_style_switch_count=5`, `installed_theme={"title":"Einkpaper dark theme"}`, `reading_mode_be_on=0`, `reading_mode_display_screen=0`, `tct_color_temperature_activated=0`.
- `global`: nada relevante.

Pendiente de la sonda: qué valores toma `nxtpaper_ink_style_state` en Normal / Color Paper / Ink Paper (hoy = 2, con el tema «Einkpaper dark» aplicado) y si una app normal puede leer `Settings.Secure` sin excepción (por adb sí se ve; desde la app está por comprobar).
