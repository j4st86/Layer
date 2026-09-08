#!/usr/bin/env bash
# Refresh the bundled AdGuard DNS filter in app/src/main/assets/adblock.
# Keep the file name and BUNDLED_VERSION in sync with AdBlockPolicy.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/app/src/main/assets/adblock"
mkdir -p "$DEST"

URL="https://adguardteam.github.io/HostlistsRegistry/assets/filter_1.txt"
OUT="$DEST/adguard-dns-filter.txt"

echo "Downloading AdGuard DNS filter"
curl -fsSL -A "Mozilla/5.0 (Layer fetch-adblock)" -o "$OUT" "$URL"
if [[ ! -s "$OUT" ]]; then
  echo "empty download: $URL" >&2
  exit 1
fi

VERSION="$(python3 - "$OUT" <<'PY'
from pathlib import Path
import sys
text = Path(sys.argv[1]).read_text(errors="replace")
version = ""
for line in text.splitlines()[:20]:
    if line.lower().startswith("! version:"):
        version = line.split(":", 1)[1].strip()
        break
if not version:
    raise SystemExit("! Version: not found in AdGuard DNS filter")
print(version)
PY
)"

printf '%s\n' "$VERSION" > "$DEST/VERSION"

KOTLIN="$ROOT/core/src/main/kotlin/com/layer/core/config/AdBlockPolicy.kt"
python3 - "$KOTLIN" "$VERSION" <<'PY'
from pathlib import Path
import sys
path = Path(sys.argv[1])
version = sys.argv[2]
text = path.read_text()
old = None
for line in text.splitlines():
    if "const val BUNDLED_VERSION" in line:
        old = line
        break
if not old:
    raise SystemExit("BUNDLED_VERSION not found in AdBlockPolicy.kt")
new = f'    const val BUNDLED_VERSION = "{version}"'
path.write_text(text.replace(old, new, 1))
print(f"Updated BUNDLED_VERSION to {version}")
PY

echo "Saved $OUT ($(du -h "$OUT" | awk '{print $1}')) version $VERSION"
