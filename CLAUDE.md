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
  Single shared `AppViewModel` (not one per screen - avoids plumbing
  `UsbSerialDriver`/`UsbSerialPort` through nav args) drives `connect`/`flash`/
  `monitor`/`profiles` screens. `FlashEngine` owns one flashing session
  (connect → per-entry write → verify → reset), exposing `progress`/`logs` flows.
  `UsbSerialForAndroidTransport` implements flasher-native's `UsbSerialTransport`
  on a real `UsbSerialPort`; `UsbPortCoordinator` is a plain object singleton
  arbitrating exclusive port access between flashing and monitoring (deliberately
  not DI-managed - nothing else here needs a dependency graph yet). Each screen
  independently opens/closes its own port session rather than sharing one live
  native handle across screens - simpler lifecycle at the cost of reconnecting
  once more when going from the Connect identify-pass to actually flashing.
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
- **AGP 9 has built-in Kotlin support** - do NOT apply `org.jetbrains.kotlin.android`
  to `com.android.application`/`com.android.library` modules (`app`, `flasher-native`);
  applying it is now a hard error ("no longer required... remove the plugin").
  Pure-JVM modules (`flasher-core`) still need `org.jetbrains.kotlin.jvm` as normal.
  `kotlinx-serialization`/`kotlin.plugin.compose`/KSP plugins are unaffected, keep
  applying those directly. Configure the compiler via a `kotlin { }` block nested
  *inside* `android { }` (`compilerOptions { jvmTarget.set(...) }`), not the old
  `kotlinOptions { jvmTarget = "..." }` from the removed plugin.
- **`jvmToolchain(21)` must be called explicitly**, even when `compileOptions.
  sourceCompatibility/targetCompatibility` already say `VERSION_21` - those two
  alone are just javac `-source`/`-target` flags, they don't request a Gradle
  toolchain. Without an explicit `jvmToolchain(21)` call inside `android { kotlin
  { ... } }`, AGP's `compileDebugJavaWithJavac` silently falls back to whichever
  JVM is running the Gradle daemon itself - the broken JRE here - instead of
  going through the foojay auto-download path, and fails with "does not provide
  the required capabilities: [JAVA_COMPILER]". `flasher-core`'s plain `kotlin {
  jvmToolchain(21) }` didn't have this problem because it already requested a
  toolchain explicitly; every Android module's `kotlin { }` block needs the same
  explicit call, not just `compilerOptions`.
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
- **Don't bump `androidx.core:core-ktx`/`androidx.lifecycle:*` past 1.18.0/2.10.0**
  without also bumping `compileSdk` to 37 - 1.19.0/2.11.0's AAR metadata requires
  compileSdk 37, which only exists as a beta/canary-channel platform right now
  (`sdkmanager` needs an explicit channel to install it). Stuck on the older minor
  versions until API 37 leaves preview, or accept building against a preview SDK.
- **The manifest namespace URI must be `.../apk/res/android`, not `.../apk/res/auto`.**
  `res/auto` is for custom-attribute namespaces in resource XML (e.g. layout files
  referencing a custom view's attrs), not the manifest's `android:` namespace. Using
  it by copy-paste mistake doesn't error at the XML level - every `android:name`/
  `android:required`/etc. attribute just silently resolves to nothing, and the
  manifest merger reports confusing "Missing 'name' key attribute" errors on
  elements that visibly have that attribute right there in the source.
- **There's no `Theme.Material.DayNight.*` in the platform.** "DayNight" themes are
  an AppCompat/MaterialComponents library concept (`Theme.AppCompat.DayNight.*`),
  not something `android:` framework styles provide at any API level. A pure-Compose
  app (no AppCompat dependency) needs a plain static framework theme
  (`android:Theme.Material.NoActionBar` here) as the manifest/pre-Compose theme;
  actual light/dark switching happens in `EspOtgTheme` via `isSystemInDarkTheme()`.
- **Material3 1.4.0's `ExposedDropdownMenuBox` API changed shape**: `menuAnchor()`
  takes `ExposedDropdownMenuAnchorType` (not the old `MenuAnchorType`), and
  `ExposedDropdownMenu`/`menuAnchor` are members of `ExposedDropdownMenuBoxScope`
  (the box's trailing-lambda receiver) - not top-level composables to import.
  Verified by pulling the real `material3-android-1.4.0-sources.jar` from Google's
  Maven rather than guessing from older tutorials/training-data recall.
- **`Icons.Default.*`/`Icons.AutoMirrored.Filled.*` need an explicit
  `androidx.compose.material:material-icons-core` dependency** - it's not pulled in
  transitively by `material3` alone.
- **Lint (`MutableImplicitPendingIntent`) rejects a `FLAG_MUTABLE` PendingIntent
  wrapping an implicit `Intent`** (targeting Android 14+, which `targetSdk 36`
  does) - fix by making the Intent explicit (`.setPackage(context.packageName)`),
  keep `FLAG_MUTABLE` since the system needs to write `EXTRA_PERMISSION_GRANTED`
  into that same Intent when replying to `UsbManager.requestPermission`.
- `mipmap-anydpi-v26/ic_launcher.xml` needs the `-v26` API qualifier even though
  `minSdk` is already 26 - lint calls the qualifier "unnecessary" but removing it
  (`mipmap-anydpi/`) breaks AAPT2 resource resolution (`resource mipmap/ic_launcher
  not found`). Left the lint warning in place rather than "fixing" it.

## Real-hardware debugging lessons (ESP32-C3 Super Mini, native USB-Serial-JTAG)

Chronology of the first real-device bring-up (v0.1.1..v0.1.8), kept here because
each step eliminated a plausible-but-wrong hypothesis:

- **usb-serial-for-android `read(dest, length, timeout)` silently discards whole
  USB packets when `length` is smaller than the arriving packet** (kernel
  EOVERFLOW → the library maps it to 0, indistinguishable from a timeout). The
  SLIP decoder reads 1 byte at a time; the ROM bootloader answers SYNC with
  multi-byte packets → every response was thrown away while every TX succeeded.
  THE root cause of days of "TIMEOUT with perfect TX and zero RX" on real
  hardware, fixed with an intermediate 4KB RX cache in
  `UsbSerialForAndroidTransport.read()` (always read full-buffer from the port,
  serve small requests from the cache). The serial monitor never hit this
  because it always read with a 4096-byte buffer. If RX ever "times out" again
  while TX flows, check buffer sizing *first*, not reset sequences.
- The chip (VID 0x303A/PID 0x1001) may not respond to DTR/RTS auto-reset at all
  if its *current firmware* doesn't implement USB-Serial-JTAG reset handling -
  auto-reset over native USB is a firmware feature, not a hardware guarantee
  (external UART-bridge boards hardwire it instead). Manual entry (hold BOOT,
  tap RESET / replug while holding BOOT) is the fallback; the app has an "Auto
  bootloader reset" toggle for exactly this.
- The ROM banner (`rst:0x15 (USB_UART_CHIP_RESET), boot:0x5 (DOWNLOAD(USB/...)),
  waiting for download`) visible through the app's own serial monitor was the
  definitive proof the chip *was* in download mode while SYNC kept "timing out" -
  which is what finally narrowed the bug down to the RX path rather than reset
  sequencing. The monitor is the best cross-check tool when flashing misbehaves:
  same port, same transport, different read pattern.
- `enter_bootloader` in esp_loader_port_ops_t returns void - exceptions/failures
  in the Kotlin callbacks behind it are invisible to the C library and surface
  later as a generic SYNC timeout. Anything that can fail inside those callbacks
  must log through its own side channel (the `logger` param on
  UsbSerialForAndroidTransport); never assume a silent enter_bootloader worked.
- **Never call `.format()` on a string with untrusted text interpolated into
  it.** The first flash to get past SYNC/stub/baud-change died with
  `a != java.lang.Long`, initially misread as an R8/value-class
  ClassCastException (HexOffset got converted from a value class to a data class
  on that wrong theory - harmless, kept). The stack trace (once
  LineNumberTable + stackTraceToString logging were added) showed the truth:
  `IllegalFormatConversionException` from `String.format` - the log line
  `"Flashing ${uriSegment} @ 0x%X".format(offset)` interpolated a
  percent-encoded content-URI filename, whose `%3a`-style escapes get parsed as
  format specifiers (`%3a` = width-3 hex-float conversion, incompatible with
  Long → message "a != java.lang.Long", where 'a' is the conversion char, not a
  class). Format strings must be literals; interpolate values with Kotlin
  templates or pass them as format *arguments*, never into the format string
  itself. The two remaining `.format()` calls (toMacString, toHexString) use
  literal format strings and are fine.
- Release-only crash hygiene (added during the above): `-keepattributes
  SourceFile,LineNumberTable` in app/proguard-rules.pro + FlashEngine logging
  `stackTraceToString()` on failure - keep both; the r8-map-id frames in a
  user's log paste were exactly what cracked the misdiagnosis.

## Release signing

A release keystore exists locally on the dev machine, kept **outside this repo**
(never commit a keystore), and the 4 secrets it needs
(`KEYSTORE_BASE64`/`KEYSTORE_PASSWORD`/`KEY_ALIAS`/`KEY_PASSWORD`) are already set
on the GitHub repo, so `.github/workflows/android-release.yml` is ready to produce
a signed APK the moment a `v*` tag is pushed - verified end-to-end with a local
`:app:assembleRelease` using the same env vars CI uses, R8/shrink-resources
included, signature confirmed via `apksigner verify`. Deliberately no `v0.1.0` tag
pushed yet: the actual "does it flash a real chip" milestone hasn't happened, and
cutting a release before that would just be premature. Ask before pushing a
release tag even though the infra is ready - that's a visible, public action.

## Repeated commands

```bash
# One-time / after bumping SDK versions in libs.versions.toml:
./scripts/setup-sdk.sh

# Fast domain-logic tests, no device/emulator:
./gradlew :flasher-core:test

# Full build (all green as of the initial implementation):
./gradlew :app:assembleDebug :app:lintDebug test

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
