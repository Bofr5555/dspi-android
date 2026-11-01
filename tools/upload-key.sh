#!/usr/bin/env bash
# Create the Google Play UPLOAD key (docs/RELEASE.md, step 2).
#
#   tools/upload-key.sh                 owner: keystore/upload.jks + keystore/upload.properties
#   tools/upload-key.sh --dir DIR --throwaway
#                                       CI: a throwaway key in DIR (random password, no prompts)
#                                       (from the host, DIR must be inside the checkout: only
#                                       the checkout is mounted into the container)
#
# keytool runs in the build container (tools/dev.sh); nothing is installed on
# the host. keystore/ is gitignored: the key is never committed. BACK UP BOTH
# FILES (the .jks and the .properties, which holds the password) somewhere
# safe and offline: with Play App Signing a lost upload key can be reset
# through Play Console support, but it takes days and blocks releases.
#
# Env (owner run, optional): UPLOAD_KEY_DNAME (default "CN=DSPi Android upload key")
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$here/.." && pwd)"

dir="$ROOT/keystore"
throwaway=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --dir) dir="$2"; shift 2 ;;
    --throwaway) throwaway=1; shift ;;
    -h|--help) sed -n '2,17p' "$0"; exit 0 ;;
    *) echo "upload-key.sh: unknown argument $1" >&2; exit 2 ;;
  esac
done

mkdir -p "$dir"
dir="$(cd "$dir" && pwd)"
jks="$dir/upload.jks"
props="$dir/upload.properties"
alias_name="upload"

if [[ -e "$jks" || -e "$props" ]]; then
  echo "upload-key.sh: $jks or $props already exists; refusing to overwrite an upload key." >&2
  exit 1
fi

umask 077
if [[ "$throwaway" == 1 ]]; then
  pass="$(head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n')"
  dname="CN=DSPi CI throwaway key (NOT for Play)"
else
  echo "Upload key password (empty = generate a random one; it is stored in $props):"
  read -rs pass; echo
  if [[ -z "$pass" ]]; then
    pass="$(head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n')"
  else
    read -rsp "Repeat: " pass2; echo
    [[ "$pass" == "$pass2" ]] || { echo "passwords differ" >&2; exit 1; }
    [[ ${#pass} -ge 8 ]] || { echo "password must be at least 8 characters" >&2; exit 1; }
  fi
  dname="${UPLOAD_KEY_DNAME:-CN=DSPi Android upload key}"
fi

# keytool reads the password from a file (no password on any command line).
# PKCS12 keystores use one password for the store and the key.
passfile="$dir/.upload-pass.tmp"
trap 'rm -f "$passfile"' EXIT
printf '%s\n' "$pass" > "$passfile"

# Inside the container (tools/ci.sh) dev.sh just execs; outside it starts it.
"$ROOT/tools/dev.sh" keytool -genkeypair -noprompt \
  -keystore "$jks" -storetype PKCS12 \
  -storepass:file "$passfile" -keypass:file "$passfile" \
  -alias "$alias_name" -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "$dname" >/dev/null

chmod 600 "$jks"
chmod 700 "$dir"
cat > "$props" <<EOF
# Google Play upload key for se.bofr.dspi (tools/upload-key.sh). NEVER commit.
storeFile=upload.jks
storePassword=$pass
keyAlias=$alias_name
keyPassword=$pass
EOF

echo "created $jks"
echo "created $props"
if [[ "$throwaway" != 1 ]]; then
  cat <<EOF

Next (docs/RELEASE.md):
  1. Back up $jks AND $props (offline, two places).
  2. Upload-certificate fingerprint (Play Console shows it after the first upload):
       tools/dev.sh keytool -list -v -keystore $jks -alias $alias_name
EOF
fi
