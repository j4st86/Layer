Place libbox.aar here. Build it from official sing-box:

```bash
./scripts/build-libbox.sh
```

The script checks out the tag in `sing-box.properties` (currently v1.14.1),
applies `scripts/libbox/patches`, and gomobile-binds a slim Android AAR
(arm64, TUN + VLESS + Reality/uTLS + gVisor). It does not include
WireGuard, Tailscale, QUIC/Hysteria, Naive or OpenVPN.
