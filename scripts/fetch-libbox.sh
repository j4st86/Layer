#!/usr/bin/env bash
# Layer no longer downloads a third-party fat AAR.
# Build official sing-box libbox locally instead.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
exec "$ROOT/scripts/build-libbox.sh"
