#!/bin/zsh
# Paso 0: cmdline-tools + SDK + build-tools + gradle wrapper. Idempotente.
set -euo pipefail
REPO="${0:A:h:h}"
export JAVA_HOME=/usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME="$HOME/Library/Android/sdk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
CLT_ZIP=commandlinetools-mac_x86_64-16111833_latest.zip
CLT_URL="https://dl.google.com/android/repository/$CLT_ZIP"
GV=9.6.0
log(){ print -r -- "[setup $(date +%H:%M:%S)] $*"; }

[[ "$(uname -m)" == x86_64 ]] || { log "esta Mac no es x86_64"; exit 1; }
java -version 2>&1 | head -1 | grep -q '"17\.' || { log "JDK 17 no encontrado en $JAVA_HOME"; exit 1; }
log "JDK: $(java -version 2>&1 | head -1)"

if [[ ! -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]]; then
  log "bajando $CLT_ZIP"
  TMP=$(mktemp -d); cd "$TMP"
  curl -fsSLO "$CLT_URL"
  curl -fsS https://dl.google.com/android/repository/repository2-3.xml > repo.xml
  EXPECTED=$(python3 - "$CLT_ZIP" repo.xml <<'PY'
import sys, re
zipname, xml = sys.argv[1], open(sys.argv[2]).read()
for m in re.finditer(r'<archive>(.*?)</archive>', xml, re.S):
    blk = m.group(1)
    if zipname in blk:
        c = re.search(r'<checksum[^>]*>([0-9a-f]{40})</checksum>', blk)
        if c: print(c.group(1)); break
PY
)
  ACTUAL=$(shasum -a 1 "$CLT_ZIP" | cut -d' ' -f1)
  [[ -n "$EXPECTED" && "$EXPECTED" == "$ACTUAL" ]] || { log "SHA-1 NO coincide: esperado=$EXPECTED actual=$ACTUAL"; exit 1; }
  log "SHA-1 OK $ACTUAL"
  unzip -q "$CLT_ZIP"
  mkdir -p "$ANDROID_HOME/cmdline-tools"
  rm -rf "$ANDROID_HOME/cmdline-tools/latest"
  mv cmdline-tools "$ANDROID_HOME/cmdline-tools/latest"
  cd "$HOME"; rm -rf "$TMP"
else
  log "cmdline-tools ya instaladas"
fi

log "licencias del SDK (aceptación autorizada por Jano el 29-oct-2026)"
yes | sdkmanager --licenses >/dev/null 2>&1 || true
log "instalando platform-tools, platforms;android-36, build-tools;36.0.0"
sdkmanager --install "platform-tools" "platforms;android-36" "build-tools;36.0.0" >/dev/null
sdkmanager --list_installed 2>/dev/null | sed -n '1,20p'

if [[ ! -x "$REPO/gradlew" ]]; then
  log "gradle wrapper $GV"
  TMP=$(mktemp -d); cd "$TMP"
  curl -fsSLO "https://services.gradle.org/distributions/gradle-$GV-bin.zip"
  SHA=$(curl -fsSL "https://services.gradle.org/distributions/gradle-$GV-bin.zip.sha256" | tr -d "[:space:]")
  echo "$SHA  gradle-$GV-bin.zip" | shasum -a 256 -c - >/dev/null || { log "SHA-256 de gradle NO coincide"; exit 1; }
  unzip -q "gradle-$GV-bin.zip"
  cd "$REPO"
  "$TMP/gradle-$GV/bin/gradle" wrapper --gradle-version "$GV" --distribution-type bin --no-daemon -q
  rm -rf "$TMP"
else
  log "gradlew ya existe"
fi
cd "$REPO"
./gradlew --version --no-daemon -q 2>/dev/null | grep -E '^(Gradle|Kotlin|JVM)' || true
log "LISTO"
