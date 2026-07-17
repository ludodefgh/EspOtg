#!/usr/bin/env bash
# Installs the Android SDK/NDK/CMake locally under <repo>/android-sdk instead of
# polluting a machine-wide ~/Android/Sdk. Idempotent: safe to re-run after bumping
# versions in gradle/libs.versions.toml.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK_DIR="${ROOT_DIR}/android-sdk"
CMDLINE_TOOLS_BUILD="15859902" # cmdline-tools;latest, see https://dl.google.com/android/repository/repository2-3.xml

PLATFORM_VERSION="android-36"
BUILD_TOOLS_VERSION="36.0.0"
NDK_VERSION="28.2.13676358"
CMAKE_VERSION="3.31.6"

mkdir -p "${SDK_DIR}"

if [ ! -x "${SDK_DIR}/cmdline-tools/latest/bin/sdkmanager" ]; then
  echo "Downloading Android command-line tools..."
  TMP_ZIP="$(mktemp)"
  curl -fsSL -o "${TMP_ZIP}" \
    "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_BUILD}_latest.zip"
  rm -rf "${SDK_DIR}/cmdline-tools/latest" "${SDK_DIR}/cmdline-tools-tmp"
  mkdir -p "${SDK_DIR}/cmdline-tools-tmp"
  unzip -q "${TMP_ZIP}" -d "${SDK_DIR}/cmdline-tools-tmp"
  mkdir -p "${SDK_DIR}/cmdline-tools"
  mv "${SDK_DIR}/cmdline-tools-tmp/cmdline-tools" "${SDK_DIR}/cmdline-tools/latest"
  rmdir "${SDK_DIR}/cmdline-tools-tmp"
  rm -f "${TMP_ZIP}"
fi

SDKMANAGER="${SDK_DIR}/cmdline-tools/latest/bin/sdkmanager"

echo "Accepting SDK licenses..."
# `yes` gets SIGPIPE once sdkmanager stops reading after the last prompt; under
# `pipefail` that non-zero status would otherwise abort the script even though
# license acceptance itself succeeded, so it's explicitly ignored here.
yes | "${SDKMANAGER}" --sdk_root="${SDK_DIR}" --licenses > /dev/null || true

echo "Installing platform-tools, platforms;${PLATFORM_VERSION}, build-tools;${BUILD_TOOLS_VERSION}, ndk;${NDK_VERSION}, cmake;${CMAKE_VERSION}..."
"${SDKMANAGER}" --sdk_root="${SDK_DIR}" \
  "platform-tools" \
  "platforms;${PLATFORM_VERSION}" \
  "build-tools;${BUILD_TOOLS_VERSION}" \
  "ndk;${NDK_VERSION}" \
  "cmake;${CMAKE_VERSION}"

cat > "${ROOT_DIR}/local.properties" <<EOF
sdk.dir=${SDK_DIR}
EOF

echo "Done. SDK installed in ${SDK_DIR}, local.properties written."
