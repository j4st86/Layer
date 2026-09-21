# Third-party lists

Layer is not affiliated with, endorsed by, or a product of the authors below.
Names in repository URLs identify the GPL source; they are not Layer branding.

## DNS ad and tracker hostlist

Optional in-app toggle **Block ads**. Layer redistributes a snapshot of
Hagezi Multi PRO, a GPL-3.0 DNS hostlist:

- Source repository: <https://github.com/hagezi/dns-blocklists>
- Compiled feed: <https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/adblock/pro.txt>
- License: [GNU GPL v3](https://github.com/hagezi/dns-blocklists/blob/main/LICENSE)
- Bundled snapshot: `app/src/main/assets/adblock/hagezi-pro.txt`

The file header (`! Title`, `! Homepage`, `! License`) is kept as required
when conveying a GPL work. Some inputs in that list come from other authors
with their own terms; GPL-3.0 covers the list as published by Hagezi.

Refresh the snapshot with `./scripts/fetch-adblock.sh`.

## VPN domain lists

Automatic split-tunnel lists from [itdoginfo/allow-domains](https://github.com/itdoginfo/allow-domains).
Bundled snapshot: `app/src/main/assets/rule-sets/`. Refresh with
`./scripts/fetch-rule-sets.sh`.

## VPN core

[sing-box / libbox](https://github.com/SagerNet/sing-box), GNU GPL v3.

---

# Сторонние списки

Layer не связан с авторами списков ниже и не является их продуктом.
Имена в URL нужны, чтобы указать GPL-источник, это не бренд Layer.

## DNS-хостлист рекламы и трекеров

Тумблер **Блокировка рекламы**. Снимок Hagezi Multi PRO, хостлист GPL-3.0:

- Репозиторий: <https://github.com/hagezi/dns-blocklists>
- Лента: <https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/adblock/pro.txt>
- Лицензия: [GNU GPL v3](https://github.com/hagezi/dns-blocklists/blob/main/LICENSE)
- Снимок в APK: `app/src/main/assets/adblock/hagezi-pro.txt`

Шапка файла (`! Title`, `! Homepage`, `! License`) сохраняется, как требует GPL.
Часть исходных данных у других авторов со своими условиями; GPL-3.0 покрывает
список в том виде, в каком его публикует Hagezi.

Обновление снимка: `./scripts/fetch-adblock.sh`.

## Списки доменов VPN

Автосписки [itdoginfo/allow-domains](https://github.com/itdoginfo/allow-domains).
Снимок: `app/src/main/assets/rule-sets/`. Обновление: `./scripts/fetch-rule-sets.sh`.

## Ядро VPN

[sing-box / libbox](https://github.com/SagerNet/sing-box), GNU GPL v3.
