Patches applied onto the stock sing-box tag from `sing-box.properties`.

- `0001-reality-client-version-26.8.1.patch` — report Reality client 26.8.1
  so Xray `minClientVer` does not fall back to dest.
- `0002-xhttp-client.patch` — Layer's stream-one XHTTP client. The Go
  sources also live in `scripts/libbox-xhttp` for editing; `refresh`
  rewrites this patch from a dirty checkout.

```bash
./scripts/build-libbox.sh apply     # checkout tag + apply
./scripts/build-libbox.sh refresh   # dump current tree back into these files
./scripts/build-libbox.sh           # apply + bind AAR
```
