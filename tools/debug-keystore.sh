#!/usr/bin/env bash
# Create the persistent debug keystore (keystore/debug.keystore, gitignored) once,
# so every debug build has the same signature and can update an installed copy.
set -euo pipefail
cd "$(dirname "$0")/.."
[ -f keystore/debug.keystore ] && { echo "keystore/debug.keystore exists"; exit 0; }
mkdir -p keystore
tools/dev.sh keytool -genkeypair -v -keystore keystore/debug.keystore -storepass android \
  -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=Android Debug,O=Android,C=US"
