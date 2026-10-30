#!/bin/zsh
# Solo lectura: dice qué falta para compilar e instalar. Sale con el número de fallos.
set -u
export JAVA_HOME=/usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME="$HOME/Library/Android/sdk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
REPO="${0:A:h:h}"
FALLOS=0
ok(){ print -r -- "  ✓ $*"; }
ko(){ print -r -- "  ✗ $*"; FALLOS=$((FALLOS+1)); }
print -r -- "doctor · $(date '+%Y-%m-%d %H:%M')"

[[ "$(uname -m)" == x86_64 ]] && ok "Mac x86_64" || ko "arquitectura $(uname -m)"
JV=$(java -version 2>&1 | head -1)
[[ "$JV" == *'"17.'* ]] && ok "JDK $JV" || ko "JDK: $JV (se exige 17)"
brew list --pinned 2>/dev/null | grep -qx 'openjdk@17' && ok "openjdk@17 fijado en brew" || ko "openjdk@17 sin pin → brew pin openjdk@17"

for p in "platform-tools" "platforms;android-37.0" "build-tools;36.0.0" "cmdline-tools;latest"; do
  [[ -d "$ANDROID_HOME/${p//;//}" ]] && ok "SDK $p" || ko "SDK falta $p → make setup"
done
[[ -s "$ANDROID_HOME/licenses/android-sdk-license" ]] && ok "licencias del SDK aceptadas" || ko "licencias del SDK sin aceptar"

if [[ -x "$REPO/gradlew" ]]; then
  GV=$(sed -n 's|.*gradle-\(.*\)-bin.zip|\1|p' "$REPO/gradle/wrapper/gradle-wrapper.properties")
  [[ "$GV" == 9.6.* ]] && ok "gradlew $GV" || ko "gradlew $GV (se espera 9.6.x)"
else
  ko "falta gradlew → make setup"
fi

[[ -f "$HOME/.android/comics.jks" ]] && ok "keystore ~/.android/comics.jks" || ko "falta el keystore → make keystore"
security find-generic-password -s comics-keystore -a comics >/dev/null 2>&1 && ok "contraseña en el llavero (comics-keystore)" || ko "falta la contraseña en el llavero"
git -C "$REPO" ls-files | grep -q '\.jks$' && ko "¡hay un .jks versionado en git!" || ok "ningún .jks en git"

CASK=$(/usr/local/bin/adb version 2>/dev/null | sed -n 's/^Version \([0-9.]*\).*/\1/p')
SDK=$("$ANDROID_HOME/platform-tools/adb" version 2>/dev/null | sed -n 's/^Version \([0-9.]*\).*/\1/p')
if [[ -n "$CASK" && -n "$SDK" ]]; then
  [[ "$CASK" == "$SDK" ]] && ok "adb cask $CASK = adb SDK $SDK" || ko "adb cask $CASK ≠ adb SDK $SDK (cada uno mata el servidor del otro)"
fi
N=$(adb devices 2>/dev/null | sed -n '2,$p' | grep -c 'device$')
case $N in
  1) ok "una tablet conectada: $(adb devices | sed -n 2p | cut -f1)";;
  0) ko "ninguna tablet por adb → Depuración inalámbrica + make connect";;
  *) ko "$N dispositivos; debe haber uno";;
esac
print -r -- "fallos: $FALLOS"
exit $FALLOS
