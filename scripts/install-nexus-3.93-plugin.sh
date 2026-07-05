#!/usr/bin/env bash
# Install nexus3-github-oauth-plugin into a Nexus Repository 3.93.x Spring Boot
# executable jar.
#
# Nexus 3.93 runs via Spring Boot JarLauncher. The legacy Karaf /deploy scanner
# is gone and JarLauncher ignores loader.path. A plugin jar must be embedded as
# BOOT-INF/lib/<plugin>.jar and listed in BOOT-INF/classpath.idx, then Nexus must
# be restarted.
#
# This script is intentionally conservative: it writes a timestamped backup,
# patches a temporary copy, validates the resulting jar, then atomically moves it
# into place. It uses Python's zipfile module instead of external zip/unzip so it
# works on the minimal vps-langfuse install.

set -euo pipefail

usage() {
  cat >&2 <<'USAGE'
Usage:
  install-nexus-3.93-plugin.sh --plugin <plugin-jar> [--nexus-home /opt/nexus] [--service nexus] [--restart]

Options:
  --plugin       Path to target/nexus3-github-oauth-plugin-3.93.2-01.jar
  --nexus-home   Nexus install directory (default: /opt/nexus)
  --service      systemd service name to restart/status (default: nexus)
  --restart      Restart the service after patching the boot jar

The script must run as a user that can write $nexus_home/bin and restart the
service if --restart is supplied (usually via sudo).
USAGE
}

plugin=""
nexus_home="/opt/nexus"
service="nexus"
restart=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --plugin)
      plugin="${2:-}"; shift 2 ;;
    --nexus-home)
      nexus_home="${2:-}"; shift 2 ;;
    --service)
      service="${2:-}"; shift 2 ;;
    --restart)
      restart=true; shift ;;
    -h|--help)
      usage; exit 0 ;;
    *)
      echo "Unknown argument: $1" >&2; usage; exit 2 ;;
  esac
done

if [[ -z "$plugin" ]]; then
  echo "Missing --plugin" >&2
  usage
  exit 2
fi

if [[ ! -f "$plugin" ]]; then
  echo "Plugin jar not found: $plugin" >&2
  exit 1
fi

bin_dir="$nexus_home/bin"
if [[ ! -d "$bin_dir" ]]; then
  echo "Nexus bin dir not found: $bin_dir" >&2
  exit 1
fi

boot_jars=("$bin_dir"/sonatype-nexus-repository-*.jar)
if [[ ${#boot_jars[@]} -ne 1 || ! -f "${boot_jars[0]}" ]]; then
  echo "Expected exactly one $bin_dir/sonatype-nexus-repository-*.jar" >&2
  printf 'Found: %s\n' "${boot_jars[@]}" >&2
  exit 1
fi
boot_jar="${boot_jars[0]}"
plugin_name="$(basename "$plugin")"
case "$plugin_name" in
  nexus3-github-oauth-plugin-*.jar) ;;
  *) echo "Refusing unexpected plugin jar name: $plugin_name" >&2; exit 1 ;;
esac

if ! command -v python3 >/dev/null 2>&1; then
  echo "python3 is required" >&2
  exit 1
fi

jar_tool=""
if [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/jar" ]]; then
  jar_tool="${JAVA_HOME}/bin/jar"
else
  for candidate in \
    "$nexus_home"/jdk/*/bin/jar \
    "$nexus_home"/jdk/*/*/bin/jar \
    /opt/nexus/jdk/*/bin/jar \
    /opt/nexus/jdk/*/*/bin/jar \
    /usr/bin/jar \
    /bin/jar; do
    if [[ -x "$candidate" ]]; then
      jar_tool="$candidate"
      break
    fi
  done
fi
if [[ -z "$jar_tool" ]]; then
  jar_tool="$(command -v jar || true)"
fi
if [[ -z "$jar_tool" || ! -x "$jar_tool" ]]; then
  echo "Could not find a usable jar tool; set JAVA_HOME or install a JDK" >&2
  exit 1
fi

workdir="$(mktemp -d)"
cleanup() { rm -rf "$workdir"; }
trap cleanup EXIT

backup="$boot_jar.backup.$(date -u +%Y%m%dT%H%M%SZ)"
patched="$workdir/$(basename "$boot_jar")"
cp --preserve=mode,ownership,timestamps "$boot_jar" "$backup"
cp --preserve=mode,timestamps "$boot_jar" "$patched"

entry="BOOT-INF/lib/$plugin_name"
python3 - "$patched" "$plugin" "$entry" <<'PY'
import os
import shutil
import sys
import tempfile
import zipfile

boot_jar, plugin, entry = sys.argv[1:]
idx_name = 'BOOT-INF/classpath.idx'
old_plugin_prefix = 'BOOT-INF/lib/nexus3-github-oauth-plugin-'

tmp_fd, tmp_path = tempfile.mkstemp(prefix='nexus-boot-', suffix='.jar', dir=os.path.dirname(boot_jar))
os.close(tmp_fd)
try:
    with zipfile.ZipFile(boot_jar, 'r') as zin:
        try:
            idx_text = zin.read(idx_name).decode('utf-8')
        except KeyError:
            raise SystemExit('Boot jar has no BOOT-INF/classpath.idx; unexpected Nexus layout')

        lines = [line.rstrip('\n') for line in idx_text.splitlines()]
        lines = [line for line in lines if old_plugin_prefix not in line]
        quoted = f'- "{entry}"'
        if quoted not in lines:
            lines.append(quoted)
        new_idx = ('\n'.join(lines) + '\n').encode('utf-8')

        with zipfile.ZipFile(tmp_path, 'w', compression=zipfile.ZIP_DEFLATED) as zout:
            for info in zin.infolist():
                if info.filename == idx_name:
                    continue
                if info.filename.startswith(old_plugin_prefix) and info.filename.endswith('.jar'):
                    continue
                data = zin.read(info.filename)
                zout.writestr(info, data)
            zout.write(plugin, entry)
            zout.writestr(idx_name, new_idx)
    shutil.move(tmp_path, boot_jar)
except Exception:
    try:
        os.remove(tmp_path)
    except FileNotFoundError:
        pass
    raise
PY

# Validate patched jar contents before replacing the live boot jar.
"$jar_tool" tf "$patched" | grep -Fx "$entry" >/dev/null
(
  cd "$workdir"
  "$jar_tool" xf "$patched" BOOT-INF/classpath.idx
  grep -Fx -- "- \"$entry\"" BOOT-INF/classpath.idx >/dev/null
)

install -m "$(stat -c '%a' "$boot_jar")" "$patched" "$boot_jar"
# Preserve common Nexus ownership when script runs as root.
if command -v chown >/dev/null 2>&1; then
  chown --reference="$backup" "$boot_jar" 2>/dev/null || true
fi

# The old Karaf artifact is ignored by 3.93 but confusing; remove if present.
rm -f "$nexus_home/deploy"/nexus3-github-oauth-plugin*.kar 2>/dev/null || true

echo "Installed $plugin_name into $(basename "$boot_jar")"
echo "Backup: $backup"

echo "Verification:"
"$jar_tool" tf "$boot_jar" | grep -F "BOOT-INF/lib/nexus3-github-oauth-plugin" || true

if [[ "$restart" == true ]]; then
  systemctl restart "$service"
  systemctl status --no-pager "$service"
fi
