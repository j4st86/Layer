Place libbox.aar here. Build it from official sing-box:

```bash
./scripts/build-libbox.sh
```

The script clones sing-box v1.14.0 and gomobile-binds a slim Android AAR
(arm64, TUN + VLESS + Reality/uTLS + gVisor). It does not include
WireGuard, Tailscale, QUIC/Hysteria, Naive or OpenVPN.
