#!/usr/bin/env bash
# Build a slim official sing-box libbox.aar for Layer.
# Tags keep TUN + VLESS + Reality/uTLS + gVisor + clash/command IPC.
# Drops QUIC, WireGuard, Tailscale, Naive, OpenVPN, USBIP.
# After checkout, patches Reality client version and a stream-one XHTTP client.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/app/libs/libbox.aar"
VERSION="${SINGBOX_VERSION:-v1.14.0}"
SRC="${SINGBOX_SRC:-$ROOT/.cache/sing-box}"
ANDROID_API="${ANDROID_API:-24}"
BIND_TARGET="${BIND_TARGET:-android/arm64}"

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

if [[ ! -x "$GOPATH/bin/gomobile" || ! -x "$GOPATH/bin/gobind" ]]; then
  echo "Installing sagernet gomobile v0.1.12..."
  go install github.com/sagernet/gomobile/cmd/gomobile@v0.1.12
  go install github.com/sagernet/gomobile/cmd/gobind@v0.1.12
fi

if [[ ! -d "$SRC/.git" ]]; then
  echo "Cloning sing-box $VERSION..."
  mkdir -p "$(dirname "$SRC")"
  git clone --depth 1 --branch "$VERSION" https://github.com/SagerNet/sing-box.git "$SRC"
else
  git -C "$SRC" fetch --depth 1 origin "refs/tags/$VERSION:refs/tags/$VERSION" 2>/dev/null || true
  git -C "$SRC" checkout --force "$VERSION"
fi

# Xray-core 26.7.11+ defaults Reality minClientVer to 26.3.27. Upstream sing-box
# still writes 1.8.1 into the session-id, so the server falls back to dest and
# the client sees "reality verification failed". Happ/v2rayNG work because they
# report a current Xray version. Report 26.8.1 so Layer passes the gate.
python3 - "$SRC/common/tls/reality_client.go" <<'PY'
from pathlib import Path
import sys
path = Path(sys.argv[1])
text = path.read_text()
old = "\thello.SessionId[0] = 1\n\thello.SessionId[1] = 8\n\thello.SessionId[2] = 1"
new = "\thello.SessionId[0] = 26\n\thello.SessionId[1] = 8\n\thello.SessionId[2] = 1"
if new in text:
    print("Reality client version already 26.8.1")
elif old in text:
    path.write_text(text.replace(old, new, 1))
    print("Patched Reality client version 1.8.1 -> 26.8.1")
else:
    raise SystemExit(f"reality_client.go: expected SessionId version bytes not found in {path}")
PY

python3 - "$SRC" "$ROOT/scripts/libbox-xhttp" <<'PY'
from pathlib import Path
import shutil
import sys

src = Path(sys.argv[1])
bundle = Path(sys.argv[2])

const_path = src / "constant" / "v2ray.go"
const_text = const_path.read_text()
if "V2RayTransportTypeXHTTP" not in const_text:
    needle = '\tV2RayTransportTypeHTTPUpgrade = "httpupgrade"\n'
    insert = needle + '\tV2RayTransportTypeXHTTP       = "xhttp"\n'
    if needle not in const_text:
        raise SystemExit(f"constant/v2ray.go: HTTPUpgrade const not found")
    const_path.write_text(const_text.replace(needle, insert, 1))
    print("Patched constant/v2ray.go with xhttp")
else:
    print("constant/v2ray.go already has xhttp")

opt_path = src / "option" / "v2ray_transport.go"
opt_text = opt_path.read_text()
if "XHTTPOptions" not in opt_text:
    opt_text = opt_text.replace(
        'enum:"http,ws,quic,grpc,httpupgrade"',
        'enum:"http,ws,quic,grpc,httpupgrade,xhttp"',
        1,
    )
    opt_text = opt_text.replace(
        "\tHTTPUpgradeOptions V2RayHTTPUpgradeOptions `json:\"-\"`\n}",
        "\tHTTPUpgradeOptions V2RayHTTPUpgradeOptions `json:\"-\"`\n"
        "\tXHTTPOptions       V2RayXHTTPOptions       `json:\"-\"`\n}",
        1,
    )
    marshal_case = """	case C.V2RayTransportTypeHTTPUpgrade:
		v = o.HTTPUpgradeOptions
	case "":
"""
    marshal_new = """	case C.V2RayTransportTypeHTTPUpgrade:
		v = o.HTTPUpgradeOptions
	case C.V2RayTransportTypeXHTTP:
		v = o.XHTTPOptions
	case "":
"""
    if marshal_case not in opt_text:
        raise SystemExit("option/v2ray_transport.go: marshal HTTPUpgrade case not found")
    opt_text = opt_text.replace(marshal_case, marshal_new, 1)
    unmarshal_case = """	case C.V2RayTransportTypeHTTPUpgrade:
		v = &o.HTTPUpgradeOptions
	default:
"""
    unmarshal_new = """	case C.V2RayTransportTypeHTTPUpgrade:
		v = &o.HTTPUpgradeOptions
	case C.V2RayTransportTypeXHTTP:
		v = &o.XHTTPOptions
	default:
"""
    if unmarshal_case not in opt_text:
        raise SystemExit("option/v2ray_transport.go: unmarshal HTTPUpgrade case not found")
    opt_text = opt_text.replace(unmarshal_case, unmarshal_new, 1)
    opt_path.write_text(opt_text)
    print("Patched option/v2ray_transport.go with xhttp")
else:
    print("option/v2ray_transport.go already has xhttp")

tr_path = src / "transport" / "v2ray" / "transport.go"
tr_text = tr_path.read_text()
if "v2rayxhttp" not in tr_text:
    tr_text = tr_text.replace(
        '\t"github.com/sagernet/sing-box/transport/v2rayhttpupgrade"\n',
        '\t"github.com/sagernet/sing-box/transport/v2rayhttpupgrade"\n'
        '\t"github.com/sagernet/sing-box/transport/v2rayxhttp"\n',
        1,
    )
    client_case = """	case C.V2RayTransportTypeHTTPUpgrade:
		return v2rayhttpupgrade.NewClient(ctx, dialer, serverAddr, options.HTTPUpgradeOptions, tlsConfig)
	default:
"""
    client_new = """	case C.V2RayTransportTypeHTTPUpgrade:
		return v2rayhttpupgrade.NewClient(ctx, dialer, serverAddr, options.HTTPUpgradeOptions, tlsConfig)
	case C.V2RayTransportTypeXHTTP:
		return v2rayxhttp.NewClient(ctx, dialer, serverAddr, options.XHTTPOptions, tlsConfig)
	default:
"""
    if client_case not in tr_text:
        raise SystemExit("transport/v2ray/transport.go: HTTPUpgrade client case not found")
    tr_text = tr_text.replace(client_case, client_new, 1)
    tr_path.write_text(tr_text)
    print("Patched transport/v2ray/transport.go with xhttp")
else:
    print("transport/v2ray/transport.go already has xhttp")

shutil.copyfile(bundle / "v2ray_xhttp.go", src / "option" / "v2ray_xhttp.go")
xhttp_dir = src / "transport" / "v2rayxhttp"
xhttp_dir.mkdir(parents=True, exist_ok=True)
shutil.copyfile(bundle / "client.go", xhttp_dir / "client.go")
print("Installed Layer XHTTP client")
PY

# gomobile init is needed once for the NDK toolchain.
gomobile init >/dev/null 2>&1 || gomobile init

TAGS="with_gvisor,with_utls,with_clash_api,badlinkname,tfogo_checklinkname0"
DISPLAY_VERSION="${VERSION#v}-layer"
LDFLAGS="-s -w -buildid= -checklinkname=0 -X github.com/sagernet/sing-box/constant.Version=${DISPLAY_VERSION} -X internal/godebug.defaultGODEBUG=multipathtcp=0"

mkdir -p "$(dirname "$DEST")"
rm -f "$DEST"

echo "Binding libbox $VERSION ($BIND_TARGET, tags: $TAGS)..."
cd "$SRC"
gomobile bind \
  -v \
  -o "$DEST" \
  -target "$BIND_TARGET" \
  -androidapi "$ANDROID_API" \
  -javapkg=io.nekohasekai \
  -libname=box \
  -trimpath \
  -ldflags "$LDFLAGS" \
  -tags "$TAGS" \
  ./experimental/libbox

if [[ ! -s "$DEST" ]]; then
  echo "gomobile bind produced an empty AAR" >&2
  exit 1
fi

echo "Saved $DEST ($(du -h "$DEST" | awk '{print $1}'))"
echo "sing-box $DISPLAY_VERSION  target=$BIND_TARGET"
