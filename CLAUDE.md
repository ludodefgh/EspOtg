# EspOtg

Android app (Kotlin) to flash ESP32/ESP8266/S2/S3/C2/C3/C5/C6/C61/H2/P4 devices over
USB OTG from a phone: no ads, multi-binary flash queue with free-typed hex offsets,
esptool-style options (baud/SPI mode/SPI freq/flash size/auto-bootloader-reset/
compression), live flash logs, an independent serial monitor, and per-device profile
recall (last-used flash plan reloaded automatically when a known chip is replugged).

See `.claude/plans/gentle-toasting-dongarra.md`-equivalent context below for the full
rationale; this file is the living reference for conventions and gotchas.

## Module layout

- `flasher-core/` - pure Kotlin/JVM (no Android dep). Domain models: `HexOffset`,
  `FlashEntry`/`FlashPlan`/`FlashOptions`, `TargetChip`, `EspLoaderError`,
  `ChipIdentity`, `FlashProgress`. Fast unit tests live here (`./gradlew :flasher-core:test`),
  no emulator/device needed.
- `flasher-native/` - Android Library module, NDK/CMake. JNI bridge to the vendored
  `esp-serial-flasher` C library (see below). All real USB I/O stays in Kotlin; native
  code only implements the SLIP/stub-loader protocol and calls back into Kotlin for
  actual byte transfer (see "JNI port design" below).
- `app/` - Compose UI, Room persistence, real USB handling via usb-serial-for-android.
- `third_party/esp-serial-flasher/` - git **submodule**, pinned to tag `v2.0.0` (not
  `master` - reproducibility). Clone with `git submodule update --init --recursive`
  after a fresh checkout of this repo. Do not edit files inside it.

## Why esp-serial-flasher + usb-serial-for-android

Decompiled two competing Play Store apps for reference (not copied - see
`OtherApps/`, which is gitignored and never pushed). The one that worked reliably
("SerialFlow") wraps Espressif's own `esp-serial-flasher` C library via JNI rather
than reimplementing the esptool SLIP/stub-loader protocol from scratch - we do the
same. Transport (UART chips CP210x/CH340/FTDI/PL2303 + native USB-CDC on S2/S3/C3/C6)
is `usb-serial-for-android` (mik3y) as a normal Gradle dep via JitPack, not vendored.

No real JTAG protocol in this app - decided explicitly. What ESP32-S2/S3/C3/C6 call
"USB" (USB-Serial-JTAG) shows up host-side as a plain CDC-ACM serial port; that's
covered by the UART/CDC transport above, not by an actual JTAG implementation.

## JNI port design (flasher-native)

`esp-serial-flasher` v2.0.0 uses a vtable, `esp_loader_port_ops_t`
(`third_party/esp-serial-flasher/include/esp_loader_io.h`) - not the old loose
`loader_port_*` function style from older versions/tutorials found online. Reference
template: `third_party/esp-serial-flasher/port/linux_port.c` (closest existing port to
what an Android host port needs: real file descriptor I/O + DTR/RTS reset, not a
target-side MCU port like the STM32/Pi Pico ones).

Build with `PORT=USER_DEFINED` in `flasher-native/CMakeLists.txt` (the library's own
CMakeLists.txt explicitly supports this - "user has to manually link their port",
no need to fork it) and provide `android_port.c` as an extra source instead.

Callbacks that touch hardware (`write`, `read`, `change_transmission_rate`,
`enter_bootloader`/`reset_target` for DTR/RTS) call back into Kotlin via JNI to a
`UsbSerialTransport` instance (held as a `NewGlobalRef`, released in `deinit`).
`delay_ms`/`start_timer`/`remaining_time`/`log` stay pure C (no JNI round-trip in
timeout-polling loops). Check `ExceptionCheck`/`ExceptionClear` after every
`CallXxxMethod` - map to `ESP_LOADER_ERROR_FAIL` in C, then to a typed
`EspLoaderException` in Kotlin.

`TargetChip` and `EspLoaderError` in flasher-core mirror the C enums
(`target_chip_t` in `esp_loader.h`, `esp_loader_error_t` in `esp_loader_error.h`)
**by explicit numeric value**, not by declaration order guesswork - the native side
returns raw ordinals. Cross-check against the submodule's headers directly if either
enum ever needs updating; don't re-derive the mapping from memory or from esptool.py
(the C lib's target_chip_t order does NOT match ESP8266=0/ESP32=1/ESP32S2=2/ESP32C3=3/
ESP32S3=4/... - notably C3 comes before S3).

Compression: the library has native support (`esp_loader_flash_deflate_*`), so no
`java.util.zip.Deflater` reimplementation of the protocol is needed - just compress
with raw deflate (`nowrap=true`) before handing bytes to the native write call.
**Not yet validated against a real device** - see issue #3, don't flip
`FlashOptions.compression` default to relied-upon behavior until it's confirmed.

Stub loader: already vendored as committed C sources under
`third_party/esp-serial-flasher/src/stubs/esp_stub_*.c`, one per chip family. The
network-pulling script (`cmake/serial_flasher_pull_stubs.cmake`) only runs if
`SERIAL_FLASHER_STUB_PULL_VERSION` is explicitly defined - we don't define it, so the
build is fully offline/reproducible using the committed stubs. Don't add that define
without a reason; it would silently make the build require network access.

## Toolchain / environment gotchas (all hit during initial setup, July 2026)

- **jadx has two entry points, same jar.** `jadx-*-all.jar`'s manifest `Main-Class`
  is `jadx.gui.JadxGUI`, so `java -jar jadx-*-all.jar -d out --no-res -q <apk>`
  launches the GUI (ignoring the CLI flags) instead of decompiling headlessly - it
  silently produces an empty output dir and pops a window. Use the `bin/jadx`
  launcher script (or `java -cp jadx-*-all.jar jadx.cli.JadxCLI ...` directly), never
  `java -jar` on the fat jar, for headless decompilation.
- **`yes | sdkmanager --licenses` + `set -o pipefail` aborts the script even on
  success.** `sdkmanager` exits 0 after reading the last license prompt, but `yes`
  then gets SIGPIPE (exit 141) writing to the now-closed pipe; under `pipefail` that
  141 becomes the pipeline's reported status and `set -e` kills the script right
  there - even though license acceptance already fully succeeded. Fix: append
  `|| true` to that specific line (see `scripts/setup-sdk.sh`), don't disable
  `pipefail` globally for the whole script.
- **This machine only has `openjdk-21-jre[-headless]`, not the `-jdk` package** -
  `java -version` works but there's no `javac`, so Gradle toolchain auto-detection
  finds the install and correctly reports `Is JDK: false`, refusing to use it.
  Rather than `apt install openjdk-21-jdk` system-wide (machine-global, contrary to
  the "keep tooling local to the project" ask that also drove the local
  `android-sdk/` choice), added the
  `org.gradle.toolchains.foojay-resolver-convention` plugin to `settings.gradle.kts`
  so Gradle auto-downloads its own JDK per toolchain request, cached under Gradle's
  own toolchain store - no system install, no manual JDK vendoring needed.
- In `settings.gradle.kts`, a top-level `plugins { }` block **must** come after
  `pluginManagement { }`, not before - Gradle errors immediately (`` `plugins` block
  found. `plugins` cannot appear before `pluginManagement` ``) otherwise.
- Gradle 9.x validates `include(":module")` targets eagerly: the module directory
  must already exist (even empty) before you can run *any* Gradle command in the
  project, including generating the wrapper itself. Create stub dirs first.
- Version pins in `gradle/libs.versions.toml` were resolved directly against Maven
  Central / Google's Maven `maven-metadata.xml` (not from training-data memory,
  which is stale past Jan 2026) - re-check that way, not by guessing, if bumping
  versions later: AGP 9.3.0, Kotlin 2.4.10, KSP 2.3.10, Gradle 9.6.1, Compose BOM
  2026.06.01, Room 2.8.4, compileSdk/targetSdk 36, NDK 28.2.13676358, CMake 3.31.6.

## Repeated commands

```bash
# One-time / after bumping SDK versions in libs.versions.toml:
./scripts/setup-sdk.sh

# Fast domain-logic tests, no device/emulator:
./gradlew :flasher-core:test

# Full build:
./gradlew assembleDebug lint test

# After cloning fresh:
git submodule update --init --recursive
```

## Deferred / tracked as GitHub issues, not TODOs in code

Anything explicitly out of scope for the initial implementation gets a GitHub issue
on `ludodefgh/EspOtg` instead of a code comment or a note here that'll go stale:
real JTAG support, linking a device to a git repo + flashing a chosen GitHub release
(the explicitly-planned next big feature - `FlashEntry.uri` is deliberately
source-agnostic already so that future `GitHubReleaseSource` slots in without
touching the flasher core), the deflate-compat and native-CDC-detection risks noted
above, and instrumented JNI tests.

## Verification limits

Real USB flashing and serial monitoring can only be verified on a physical Android
phone connected over OTG to a physical ESP chip - that can't be done from here.
What *can* and should be checked after any change: `./gradlew assembleDebug lint
:flasher-core:test`. Anything touching `flasher-native`/USB transport/UI needs a
manual on-device pass called out explicitly, not assumed to work from a green build.
