# EspOtg

An Android app to flash ESP32/ESP8266/S2/S3/C2/C3/C5/C6/C61/H2/P4 devices over USB
OTG, straight from your phone. No ads.

- Multi-binary flash queue with free-typed hex offsets (bootloader/partitions/app,
  or anything else at any address you type in).
- esptool-style flash options: sync/flash baud rate, SPI mode, SPI frequency,
  flash size, auto-bootloader-reset toggle, compression toggle, verify-after-write.
- Live logs while flashing, not just a spinner.
- Independent serial monitor (configurable baud, DTR/RTS control).
- Per-device profile recall: plug in a chip you've flashed before and it offers
  to reload the last flash plan you used for it, keyed by the chip's own MAC
  address (read during the mandatory bootloader handshake), with a USB adapter
  serial-number hint for an even faster pre-connect suggestion.

## Why this exists

Built after trying two existing ESP-flashing apps: one worked reliably but had
few options and ads; the other had the options (baud/SPI/flash-size/offsets/
live logs) but didn't work well. This combines the reliable flashing approach
of the former with the flexibility of the latter, with no ads and one added
feature neither had: remembering per-device settings.

## Architecture

- `flasher-core/` - pure Kotlin/JVM domain models (offsets, flash plans/options,
  chip/error enums), unit tested, no Android dependency.
- `flasher-native/` - Android Library (NDK/CMake) wrapping the vendored
  [esp-serial-flasher](https://github.com/espressif/esp-serial-flasher) C library
  via JNI - the actual SLIP/stub-loader protocol implementation, same approach
  used by the more-reliable of the two apps mentioned above.
- `app/` - Compose UI, Room persistence for device profiles, real USB I/O via
  [usb-serial-for-android](https://github.com/mik3y/usb-serial-for-android).

See [CLAUDE.md](CLAUDE.md) for the full architecture rationale, JNI port
design, and every toolchain gotcha hit while setting this up.

## Building

Requires a local SDK/NDK/CMake install, kept inside this repo (not
`~/Android/Sdk`) so it doesn't touch the rest of the machine:

```bash
git clone --recurse-submodules https://github.com/ludodefgh/EspOtg.git
cd EspOtg
./scripts/setup-sdk.sh   # downloads platform-tools, platform 36, build-tools, NDK, CMake into android-sdk/
./gradlew :app:assembleDebug :app:lintDebug test
```

If you already cloned without `--recurse-submodules`:

```bash
git submodule update --init --recursive
```

The JDK itself doesn't need to be pre-installed either - Gradle auto-provisions
one via the Foojay toolchain resolver if needed.

## Status

Initial implementation: scaffolded, builds, unit-tested. Real USB flashing and
the serial monitor can only be verified on a physical Android phone connected
over OTG to a physical ESP chip - that hasn't been done yet. Open items are
tracked as [issues](https://github.com/ludodefgh/EspOtg/issues), notably
compression-format compatibility and native USB-CDC auto-detection, both
flagged as needing a real-hardware pass before being trusted by default.

Real JTAG debugging and linking a device to a Git repo (to flash a chosen
release) are deliberately out of scope for this first pass - see the
tracked issues.

## License

Not yet decided.
