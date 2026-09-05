#!/usr/bin/env bash
# Refresh bundled itdoginfo SRS lists in app/src/main/assets/rule-sets.
# Keep the mapping in sync with RuleSetCatalog.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/app/src/main/assets/rule-sets"
mkdir -p "$DEST"

TAG="$(curl -fsSL -o /dev/null -w '%{url_effective}' \
  https://github.com/itdoginfo/allow-domains/releases/latest | sed 's#.*/##')"
if [[ -z "$TAG" || "$TAG" == "latest" ]]; then
  echo "Could not resolve latest itdoginfo/allow-domains release" >&2
  exit 1
fi

BASE="https://github.com/itdoginfo/allow-domains/releases/download/${TAG}"
echo "Bundling itdoginfo/allow-domains $TAG"

while IFS='|' read -r remote local; do
  echo "  $remote -> $local"
  curl -fsSL -o "$DEST/$local" "$BASE/$remote"
  if [[ ! -s "$DEST/$local" ]]; then
    echo "empty download: $remote" >&2
    exit 1
  fi
done <<'EOF'
youtube.srs|rs-youtube.srs
meta.srs|rs-meta.srs
twitter.srs|rs-twitter.srs
telegram.srs|rs-telegram.srs
discord.srs|rs-discord.srs
google_ai.srs|rs-google-ai.srs
tiktok.srs|rs-tiktok.srs
news.srs|rs-news.srs
hdrezka.srs|rs-hdrezka.srs
google_play.srs|rs-google-play.srs
cloudflare.srs|rs-cloudflare.srs
geoblock.srs|rs-geoblock.srs
EOF

printf '%s\n' "$TAG" > "$DEST/VERSION"

KOTLIN="$ROOT/core/src/main/kotlin/com/layer/core/config/RuleSetCatalog.kt"
if [[ -f "$KOTLIN" ]]; then
  python3 - "$KOTLIN" "$TAG" <<'PY'
from pathlib import Path
import sys
path = Path(sys.argv[1])
tag = sys.argv[2]
text = path.read_text()
old = None
for line in text.splitlines():
    if "const val BUNDLED_RELEASE" in line:
        old = line
        break
if not old:
    raise SystemExit("BUNDLED_RELEASE not found in RuleSetCatalog.kt")
new = f'    const val BUNDLED_RELEASE = "{tag}"'
path.write_text(text.replace(old, new, 1))
print(f"Updated BUNDLED_RELEASE to {tag}")
PY
fi

echo "Saved $DEST ($(du -sh "$DEST" | awk '{print $1}'))"
