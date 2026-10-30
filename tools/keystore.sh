#!/bin/zsh
# Crea ~/.android/comics.jks con una contraseña generada y guardada en el llavero de macOS.
# La contraseña NUNCA se imprime. Idempotente: si existe el .jks no hace nada.
set -euo pipefail
export JAVA_HOME=/usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
KS="$HOME/.android/comics.jks"; SVC=comics-keystore; ACC=comics
if [[ -f "$KS" ]]; then echo "ya existe $KS"; exit 0; fi
mkdir -p "$HOME/.android"
if ! security find-generic-password -s "$SVC" -a "$ACC" >/dev/null 2>&1; then
  PW=$(openssl rand -base64 48 | tr -dc 'A-Za-z0-9' | cut -c1-32)
  security add-generic-password -s "$SVC" -a "$ACC" -l "Cómics — keystore de firma (Android)" -j "Contraseña de ~/.android/comics.jks (store y clave). Copiar al gestor de contraseñas junto con el .jks." -w "$PW" -T /usr/bin/security
  unset PW
fi
PW=$(security find-generic-password -s "$SVC" -a "$ACC" -w)
keytool -genkeypair -keystore "$KS" -alias comics -keyalg RSA -keysize 4096 -validity 10000 \
  -storepass "$PW" -keypass "$PW" -dname "CN=Comics, OU=Jano, O=Jano, C=MX" >/dev/null 2>&1
chmod 600 "$KS"
echo "creado $KS ($(stat -f %z "$KS") bytes), contraseña en el llavero: servicio $SVC, cuenta $ACC"
keytool -list -v -keystore "$KS" -alias comics -storepass "$PW" | grep -E 'SHA1|SHA256|Válido|Valid'
