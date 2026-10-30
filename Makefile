# Cómics — Makefile delgado. Variables SOLO aquí; no se toca ~/.zshrc.
SHELL := /bin/zsh
export JAVA_HOME    := /usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME := $(HOME)/Library/Android/sdk
export PATH         := $(JAVA_HOME)/bin:$(ANDROID_HOME)/platform-tools:$(ANDROID_HOME)/cmdline-tools/latest/bin:$(PATH)

APP_ID        := mx.jano.comics
KEYSTORE      := $(HOME)/.android/comics.jks
KEYSTORE_SVC  := comics-keystore
TOOLKIT       := $(HOME)/nxtpaper-toolkit
DEVICE_CONF   := $(TOOLKIT)/config/device.conf
SERIAL         = $(shell sed -n 's/^DEVICE_SERIAL="\(.*\)"/\1/p' $(DEVICE_CONF) 2>/dev/null)
ADB            = ANDROID_SERIAL=$(SERIAL) adb

.PHONY: help setup doctor keystore sha1 connect dev deploy run logs shot test clean

help: ## esta ayuda
	@grep -E '^[a-z-]+:.*##' $(MAKEFILE_LIST) | awk -F':.*## ' '{printf "  %-10s %s\n", $$1, $$2}'

setup: ## una vez: cmdline-tools + SDK 36 + build-tools + gradle wrapper
	@tools/setup-toolchain.sh 2>&1 | tee dist/logs/setup-toolchain.log

doctor: ## solo lectura: JDK, SDK, licencias, wrapper, keystore, adb
	@tools/doctor.sh

keystore: ## crea ~/.android/comics.jks si no existe (contraseña en el llavero)
	@tools/keystore.sh

sha1: ## huella SHA-1 del keystore (para los clientes OAuth Android)
	@keytool -list -v -keystore $(KEYSTORE) -alias comics -storepass "$$(security find-generic-password -s $(KEYSTORE_SVC) -w)" | grep -E 'SHA1|SHA256'

connect: ## empareja/conecta la tablet por Wi-Fi (toolkit 01-wireless.sh)
	@$(TOOLKIT)/scripts/01-wireless.sh

dev: ## build debug (.debug) + install + arranque
	@./gradlew :app:installDebug -q && $(ADB) shell am start -W -n $(APP_ID).debug/$(APP_ID).MainActivity

deploy: ## build release (R8) + install -r + arranque cronometrado
	@./gradlew :app:assembleRelease -q && $(ADB) install -r app/build/outputs/apk/release/app-release.apk && $(ADB) shell am start -W -n $(APP_ID)/$(APP_ID).MainActivity

run: ## relanza la build diaria sin compilar
	@$(ADB) shell am start -W -n $(APP_ID)/$(APP_ID).MainActivity

logs: ## logcat solo de la app
	@$(ADB) logcat -v color --pid=$$($(ADB) shell pidof -s $(APP_ID) || $(ADB) shell pidof -s $(APP_ID).debug)

shot: ## captura de pantalla a dist/shots/
	@mkdir -p dist/shots && $(ADB) exec-out screencap -p > dist/shots/$$(date +%Y%m%d-%H%M%S).png && ls -t dist/shots | head -1

test: ## pruebas unitarias JVM
	@./gradlew :app:testDebugUnitTest

clean: ## borra builds (nunca el keystore ni la caché de la tablet)
	@./gradlew clean -q
