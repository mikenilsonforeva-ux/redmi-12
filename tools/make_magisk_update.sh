#!/usr/bin/env bash
#
# Builds a Magisk update ZIP for the existing "revamped_fmradio" module by
# taking a COPY of the module as it is installed on the phone and replacing
# only RevampedFMRadio.apk in it. Every other file (sepolicy.rule, system.prop,
# libfmjni.so, service scripts, ...) is carried over byte for byte, so the
# working radio setup is not touched.
#
# Usage:
#   tools/make_magisk_update.sh <module_dir_copy> <new_apk> <out_zip>
#
# Example (see docs/INSTALL.md for how to get the module copy):
#   tools/make_magisk_update.sh ~/backup/revamped_fmradio RevampedFMRadio.apk \
#       RevampedFMRadio-signal-update.zip
#
set -euo pipefail

die() { echo "error: $*" >&2; exit 1; }

[ "$#" -eq 3 ] || die "usage: $0 <module_dir_copy> <new_apk> <out_zip>"
MOD="$(cd "$1" 2>/dev/null && pwd)" || die "module directory not found: $1"
APK="$2"
OUT="$3"

[ -f "$APK" ] || die "APK not found: $APK"
[ -f "$MOD/module.prop" ] || die "$MOD/module.prop not found: not a Magisk module copy"
grep -q '^id=revamped_fmradio[[:space:]]*$' "$MOD/module.prop" \
    || die "module.prop id is not revamped_fmradio; refusing to continue"

# Exactly one RevampedFMRadio.apk must exist in the module.
mapfile -t FOUND < <(find "$MOD" -type f -name 'RevampedFMRadio.apk')
[ "${#FOUND[@]}" -eq 1 ] || die "expected exactly one RevampedFMRadio.apk in the module, found ${#FOUND[@]}"
APK_REL="${FOUND[0]#"$MOD"/}"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

cp -a "$MOD/." "$WORK/"
# Runtime flag files must not travel inside an update package.
rm -f "$WORK/disable" "$WORK/remove" "$WORK/update"

cp "$APK" "$WORK/$APK_REL"

# Bump versionCode so Magisk shows it as an update.
if grep -q '^versionCode=[0-9]\+' "$WORK/module.prop"; then
    OLD="$(sed -n 's/^versionCode=\([0-9]\+\).*/\1/p' "$WORK/module.prop" | head -n1)"
    sed -i "s/^versionCode=.*/versionCode=$((OLD + 1))/" "$WORK/module.prop"
fi

# Standard Magisk module installer entry points.
mkdir -p "$WORK/META-INF/com/google/android"
printf '#MAGISK\n' > "$WORK/META-INF/com/google/android/updater-script"
cat > "$WORK/META-INF/com/google/android/update-binary" <<'EOF'
#!/sbin/sh
#################
# Initialization
#################
umask 022

ui_print() { echo "$1"; }

require_new_magisk() {
  ui_print "*******************************"
  ui_print " Please install Magisk v20.4+! "
  ui_print "*******************************"
  exit 1
}

#########################
# Load util_functions.sh
#########################
OUTFD=$2
ZIPFILE=$3

mount /data 2>/dev/null

[ -f /data/adb/magisk/util_functions.sh ] || require_new_magisk
. /data/adb/magisk/util_functions.sh
[ $MAGISK_VER_CODE -lt 20400 ] && require_new_magisk

install_module
exit 0
EOF

OUT_ABS="$(cd "$(dirname "$OUT")" && pwd)/$(basename "$OUT")"
rm -f "$OUT_ABS"

python3 -I - "$WORK" "$OUT_ABS" <<'PY'
import os, sys, zipfile
src, out = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
    for root, dirs, files in os.walk(src):
        dirs.sort()
        for name in sorted(files):
            full = os.path.join(root, name)
            z.write(full, os.path.relpath(full, src))
PY

echo "Created: $OUT_ABS"
echo "Replaced: $APK_REL"
echo "Contents:"
python3 -I -c 'import sys,zipfile; [print("  "+n) for n in zipfile.ZipFile(sys.argv[1]).namelist()]' "$OUT_ABS"
