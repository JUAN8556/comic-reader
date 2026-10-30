# Consola de Google — lo que hace Jano una sola vez (25-30 min)

Cuenta: la de Google de Jano (la misma del Drive; el correo no se escribe aquí porque el repo es público). Proyecto: **Comics**. El mismo proyecto sirve para rclone (migración) y para la app.

0. **Antes:** https://myaccount.google.com/security → buscar «Programa de Protección Avanzada». Si está inscrito, parar: `drive.readonly` quedaría bloqueado y se pasa al plan B (cuenta de servicio).
1. https://console.cloud.google.com → selector de proyectos → «Proyecto nuevo» → Nombre `Comics` → Ubicación «Sin organización» → Crear → seleccionarlo.
2. ☰ → APIs y servicios → Biblioteca → «Google Drive API» → **Habilitar**.
3. ☰ → **Google Auth Platform** → «Comenzar»: nombre `Comics`, correo de asistencia, público **Externo**, correo de contacto → Crear.
4. **Branding:** sin logo. Página principal `https://github.com/JUAN8556/comic-reader` · Privacidad `https://github.com/JUAN8556/comic-reader#privacidad` · dominio autorizado `github.com`.
5. **Acceso a datos:** añadir `…/auth/drive.file`, `…/auth/drive.readonly`, `…/auth/drive.appdata`. Ignorar los avisos de verificación; **no enviar a verificación** (exención de uso personal, <100 usuarios).
6. **Público:** añadir el propio correo como usuario → **Publicar aplicación** → «En producción». (En «Testing» el acceso caduca cada 7 días.)
7. **Clientes**, tres:
   - **Aplicación de escritorio** `rclone Mac` → descargar el JSON al gestor de contraseñas. Se pega en `rclone config` en la Terminal de Jano; nunca pasa por el chat.
   - **Android** `Cómics tablet` · paquete `mx.jano.comics` · SHA-1 `F1:65:D5:13:F7:77:83:43:81:CF:8B:7A:29:85:46:9C:59:34:F8:1E`.
   - **Android** `Cómics debug` · paquete `mx.jano.comics.debug` · la misma SHA-1.
8. En la tablet, al pulsar «Conectar Google Drive» en la app: hoja de Play Services → «Google no ha verificado esta app» → Avanzado → ir a Comics → marcar los dos permisos → Permitir.

La huella SHA-1 sale de `make sha1` y no es secreta. El keystore (`~/.android/comics.jks`) y su contraseña (llavero de macOS, servicio `comics-keystore`) sí lo son y nunca entran en el repo.
