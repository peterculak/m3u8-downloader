ADB    := $(HOME)/Library/Android/sdk/platform-tools/adb
APK    := app/build/outputs/apk/release/app-arm64-v8a-release.apk
DEVICE := $(shell $(ADB) devices | awk '/device$$/{print $$1; exit}')

.PHONY: build install deploy clean logs help

## Build the smallest possible APK (release + minify + ABI split)
build:
	./gradlew assembleRelease
	@echo ""
	@echo "  ✅ APK ready: $(CURDIR)/$(APK)"
	@echo ""

## Install the already-built APK onto the connected phone
install:
	$(ADB) -s $(DEVICE) install -r $(APK)

## Build + install in one shot (most common)
deploy: build install

## Wipe build outputs
clean:
	./gradlew clean

## Stream logcat from the app (Ctrl-C to stop)
logs:
	$(ADB) -s $(DEVICE) logcat -s AutoDownloadWorker DownloadEpisodeWorker MainViewModel

## Show this help
help:
	@echo ""
	@echo "  make deploy   — build & install on phone  (most common)"
	@echo "  make build    — compile only"
	@echo "  make install  — push existing APK to phone"
	@echo "  make logs     — stream app logcat"
	@echo "  make clean    — wipe build outputs"
	@echo ""
