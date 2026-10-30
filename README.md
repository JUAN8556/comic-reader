# Cómics

Lector de cómics para la tablet TCL NXTPAPER 11 Plus (Android 15), de uso personal.
Lee la carpeta «Cómics» de la unidad de Google Drive de su único usuario, directamente
desde la tablet, y la cachea para leer sin conexión. Inspirado en Cover – Comic Reader.

Kotlin + Jetpack Compose, un solo módulo. Se compila por línea de comandos desde una
Mac (`make help`) y se instala por adb inalámbrico. Sin Android Studio.

## Privacidad

La app es de uso personal y no la distribuye nadie. Accede a Google Drive solo con el
permiso que concede su único usuario en su propio dispositivo, con un proyecto de Google
Cloud que le pertenece. No hay servidor intermedio: los datos van de Google Drive a la
tablet y no salen de ella. El progreso de lectura se guarda en la tablet y, si el usuario
lo permite, una copia en la carpeta privada de la app en su Drive (`appDataFolder`).
No se recoge telemetría ni se comparte nada con terceros.

## Página de inicio

Este repositorio es la página de inicio y la política de privacidad de la app, según
exige Google al publicar un proyecto OAuth.

## Migración de la biblioteca

La herramienta que lleva los cómics de iCloud Drive a la carpeta «Cómics» de Google
Drive, por tandas y con nombres uniformes, está en `tools/migracion/`; cómo funciona y
cómo se lanza, en [docs/MIGRACION.md](docs/MIGRACION.md) (`make mig-plan`, `make migrar`).
