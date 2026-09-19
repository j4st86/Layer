#!/usr/bin/env bash
# Build a slim official sing-box libbox.aar for Layer.
#
# Patches live in scripts/libbox/patches and are applied onto a clean tag.
# Commands:
#   ./scripts/build-libbox.sh           # apply patches and bind the AAR
#   ./scripts/build-libbox.sh apply     # checkout the tag and apply patches only
#   ./scripts/build-libbox.sh refresh   # rewrite patches from the current cache tree
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PROPS="$ROOT/sing-box.properties"
PATCH_DIR="$ROOT/scripts/libbox/patches"
DEST="$ROOT/app/libs/libbox.aar"
SRC="${SINGBOX_SRC:-$ROOT/.cache/sing-box}"

if [[ ! -f "$PROPS" ]]; then
  echo "missing $PROPS" >&2
  exit 1
fi

prop() {
  local key="$1"
  awk -F= -v k="$key" '$1==k {print substr($0, index($0,"=")+1); exit}' "$PROPS"
}

VERSION="${SINGBOX_VERSION:-$(prop singbox.tag)}"
GOMOBILE_VERSION="${GOMOBILE_VERSION:-$(prop gomobile.version)}"
TAGS="${LIBBOX_TAGS:-$(prop libbox.tags)}"
ANDROID_API="${ANDROID_API:-$(prop libbox.androidApi)}"
BIND_TARGET="${BIND_TARGET:-$(prop libbox.bindTarget)}"
COMMAND="${1:-build}"

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/28.0.13004108}"
export PATH="$JAVA_HOME/bin:/opt/homebrew/bin:$PATH"

if ! command -v go >/dev/null; then
  echo "Go is required (1.25+). brew install go" >&2
  exit 1
fi
if [[ ! -d "$ANDROID_NDK_HOME" ]]; then
  echo "Android NDK not found at $ANDROID_NDK_HOME" >&2
  echo "Install with: sdkmanager --install \"ndk;28.0.13004108\"" >&2
  exit 1
fi

GOPATH="$(go env GOPATH)"
export PATH="$GOPATH/bin:$PATH"

ensure_source() {
  mkdir -p "$(dirname "$SRC")"
  if [[ ! -d "$SRC/.git" ]]; then
    echo "Cloning sing-box $VERSION..."
    git clone --depth 1 --branch "$VERSION" https://github.com/SagerNet/sing-box.git "$SRC"
  else
    if ! git -C "$SRC" rev-parse --verify --quiet "refs/tags/$VERSION" >/dev/null; then
      echo "Fetching sing-box $VERSION..."
      git -C "$SRC" fetch --depth 1 origin "tag" "$VERSION"
    fi
  fi
  git -C "$SRC" checkout --force "$VERSION"
  git -C "$SRC" reset --hard "$VERSION"
  git -C "$SRC" clean -fdq
}

apply_patches() {
  local patch
  shopt -s nullglob
  local patches=("$PATCH_DIR"/*.patch)
  shopt -u nullglob
  if [[ ${#patches[@]} -eq 0 ]]; then
    echo "no patches in $PATCH_DIR" >&2
    exit 1
  fi
  for patch in "${patches[@]}"; do
    echo "Checking $(basename "$patch")..."
    if ! git -C "$SRC" apply --check "$patch"; then
      echo "patch did not apply: $patch" >&2
      echo "Rebase it with: $0 refresh  (after fixing the tree), or inspect the failed hunk." >&2
      exit 1
    fi
  done
  for patch in "${patches[@]}"; do
    echo "Applying $(basename "$patch")..."
    git -C "$SRC" apply "$patch"
  done
}

refresh_patches() {
  if [[ ! -d "$SRC/.git" ]]; then
    echo "no sing-box checkout at $SRC; run $0 apply first" >&2
    exit 1
  fi
  mkdir -p "$PATCH_DIR"
  git -C "$SRC" add -A
  git -C "$SRC" diff --cached -- common/tls/reality_client.go > "$PATCH_DIR/0001-reality-client-version-26.8.1.patch"
  git -C "$SRC" diff --cached -- \
    constant/v2ray.go \
    option/v2ray_transport.go \
    option/v2ray_xhttp.go \
    transport/v2ray/transport.go \
    transport/v2rayxhttp/client.go \
    > "$PATCH_DIR/0002-xhttp-client.patch"
  echo "Rewrote patches in $PATCH_DIR from $SRC"
}

bind_aar() {
  if [[ ! -x "$GOPATH/bin/gomobile" || ! -x "$GOPATH/bin/gobind" ]]; then
    echo "Installing sagernet gomobile $GOMOBILE_VERSION..."
  else
    echo "Ensuring sagernet gomobile $GOMOBILE_VERSION..."
  fi
  go install "github.com/sagernet/gomobile/cmd/gomobile@$GOMOBILE_VERSION"
  go install "github.com/sagernet/gomobile/cmd/gobind@$GOMOBILE_VERSION"
  gomobile init >/dev/null 2>&1 || gomobile init

  local display="${VERSION#v}-layer"
  local ldflags="-s -w -buildid= -checklinkname=0 -X github.com/sagernet/sing-box/constant.Version=${display} -X internal/godebug.defaultGODEBUG=multipathtcp=0"

  mkdir -p "$(dirname "$DEST")"
  rm -f "$DEST"

  echo "Binding libbox $VERSION ($BIND_TARGET, tags: $TAGS)..."
  (
    cd "$SRC"
    gomobile bind \
      -v \
      -o "$DEST" \
      -target "$BIND_TARGET" \
      -androidapi "$ANDROID_API" \
      -javapkg=io.nekohasekai \
      -libname=box \
      -trimpath \
      -ldflags "$ldflags" \
      -tags "$TAGS" \
      ./experimental/libbox
  )

  if [[ ! -s "$DEST" ]]; then
    echo "gomobile bind produced an empty AAR" >&2
    exit 1
  fi

  echo "Saved $DEST ($(du -h "$DEST" | awk '{print $1}'))"
  echo "sing-box $display  target=$BIND_TARGET  gomobile=$GOMOBILE_VERSION"
}

case "$COMMAND" in
  apply)
    ensure_source
    apply_patches
    ;;
  refresh)
    refresh_patches
    ;;
  build)
    ensure_source
    apply_patches
    bind_aar
    ;;
  *)
    echo "usage: $0 [build|apply|refresh]" >&2
    exit 1
    ;;
esac
