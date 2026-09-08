# Third-party lists

Layer is not affiliated with, endorsed by, or a product of the authors below.
Names in repository URLs identify the GPL source; they are not Layer branding.

## DNS ad and tracker hostlist

Optional in-app toggle **Block ads**. Layer redistributes a snapshot of the
GPL-3.0 DNS hostlist published as HostlistsRegistry `filter_1`:

- Source repository: <https://github.com/AdguardTeam/AdguardSDNSFilter>
- Compiled feed: <https://adguardteam.github.io/HostlistsRegistry/assets/filter_1.txt>
- License: [GNU GPL v3](https://github.com/AdguardTeam/AdguardSDNSFilter/blob/master/LICENSE)
- Bundled snapshot: `app/src/main/assets/adblock/dns-ad-filter.txt`

The file header (`! Title`, `! Homepage`) is kept as required when conveying
a GPL work. The compiled list also draws on EasyList and EasyPrivacy
([GPL-3.0 or CC BY-SA 3.0](https://easylist.to/pages/licence.html)).

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

Тумблер **Блокировка рекламы**. Снимок хостлиста GPL-3.0
(HostlistsRegistry `filter_1`):

- Репозиторий: <https://github.com/AdguardTeam/AdguardSDNSFilter>
- Лента: <https://adguardteam.github.io/HostlistsRegistry/assets/filter_1.txt>
- Лицензия: [GNU GPL v3](https://github.com/AdguardTeam/AdguardSDNSFilter/blob/master/LICENSE)
- Снимок в APK: `app/src/main/assets/adblock/dns-ad-filter.txt`

Шапка файла (`! Title`, `! Homepage`) сохраняется, как требует GPL.
В сборку входят также правила EasyList и EasyPrivacy
([GPL-3.0 или CC BY-SA 3.0](https://easylist.to/pages/licence.html)).

Обновление снимка: `./scripts/fetch-adblock.sh`.

## Списки доменов VPN

Автосписки [itdoginfo/allow-domains](https://github.com/itdoginfo/allow-domains).
Снимок: `app/src/main/assets/rule-sets/`. Обновление: `./scripts/fetch-rule-sets.sh`.

## Ядро VPN

[sing-box / libbox](https://github.com/SagerNet/sing-box), GNU GPL v3.
