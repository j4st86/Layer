<p align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="96" height="96" alt="Layer">
</p>

<h1 align="center">Layer</h1>

<p align="center">
  <a href="README.en.md">English</a> ·
  <strong>Русский</strong>
</p>

<p align="center">
  <strong>VPN-клиент с умной маршрутизацией для Android</strong><br>
  Не провайдер доступа: свой <code>vless://</code> или подписка, свой VPS.
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-GPL--3.0-4C6EF5?style=flat-square" alt="GPL-3.0"></a>
  <a href="https://github.com/j4st86/Layer/releases"><img src="https://img.shields.io/github/v/release/j4st86/Layer?style=flat-square&color=2F9E44" alt="Release"></a>
  <img src="https://img.shields.io/badge/Android-14%2B-3DDC84?style=flat-square&logo=android&logoColor=white" alt="Android 14+">
  <img src="https://img.shields.io/badge/arch-arm64-868E96?style=flat-square" alt="arm64">
  <img src="https://img.shields.io/badge/sing--box-1.14.3-495057?style=flat-square" alt="sing-box 1.14.3">
</p>

Layer — клиент под свой сервер. По умолчанию интернет идёт **напрямую**. Через VLESS уходят приложения и домены, которые вы сами отправили в VPN, и сайты из автоматических списков.

Списки рассчитаны на то, что обычно недоступно из России: YouTube, Telegram, Instagram и остальное из [itdoginfo](https://github.com/itdoginfo/allow-domains). Банк, госуслуги и локальная сеть через VPS не идут.

Пет-проект, не магазин ключей. Нужен свой `vless://` или подписка. UUID в репозитории нет. Телефоны на Android 13 и старше не поддерживаются.

## Зачем это

Split-туннель есть у многих клиентов. Layer заточен под Россию: готовые списки, правила на приложения и домены, свой VLESS.

- **Маршрутизация по правилам.** Сначала ваши приложения и домены, потом списки [itdoginfo/allow-domains](https://github.com/itdoginfo/allow-domains). Всё остальное — напрямую.
- **Свой ключ.** Импорт `vless://` или HTTPS-подписки с QR или из буфера. Reality, TLS, Vision, WebSocket, gRPC, XHTTP.
- **Несколько серверов.** Ручные узлы и подписки, заметки, автовыбор живого сервера.
- **Реклама только у выбранных приложений.** Список [Hagezi Multi PRO](https://github.com/hagezi/dns-blocklists). Пустой выбор ничего не блокирует.
- **Пуши остаются на месте.** Сервисы Google Play в туннель не попадают, поэтому уведомления идут обычным путём.
- **Always-on.** Система поднимает VPN после перезагрузки, если включить это в настройках Android.
- **Русский и English.** Язык можно зафиксировать или оставить как у системы.

```text
приложение: Direct / VPN          ← ваше правило важнее списков
        ↓
свой домен: Direct / VPN
        ↓
реклама: только выбранные приложения
        ↓
списки itdoginfo (YouTube, Telegram, …)
        ↓
напрямую
```

Сам сервер VPN, локальная сеть и Layer тоже идут напрямую: иначе туннель замыкается сам на себя.

## Что умеет

- Нативный клиент: Kotlin, Jetpack Compose, Android `VpnService`, ядро [sing-box](https://github.com/SagerNet/sing-box) 1.14.3 (libbox)
- Автосписки: YouTube, Telegram, Instagram / Facebook, X, Discord, TikTok, Google AI, Google Play, Cloudflare, зарубежные СМИ, HDRezka, геоблок. Обновление примерно раз в неделю
- Блокировка рекламы и трекеров по хостлисту, отдельно для каждого выбранного приложения. Список тоже обновляется примерно раз в неделю и только по Wi‑Fi
- После первого сервера Layer один раз предлагает типичные приложения: мессенджеры и видео в VPN, банки и часть местных сервисов — мимо
- Правила приложений и свои домены, в том числе зоны вроде `.рф`
- Смена Wi‑Fi и мобильной сети заново поднимает туннель, когда новая сеть уже есть. Разблокировка экрана туннель не рвёт
- Для фона достаточно режима батареи «Оптимизировано». Разрешение «без ограничений» не запрашивается
- Диагностика без секретов в логах, отчёт в Downloads
- Только **arm64**. Эмулятор x86 и 32-битные устройства не поддерживаются

## Установка

1. Скачайте APK с [Releases](https://github.com/j4st86/Layer/releases/latest).
2. Разрешите установку из браузера или GitHub.
3. Добавьте сервер кнопкой **+** на главном экране.
4. По желанию: Настройки → VPN → Layer → «Всегда включённый VPN».

Ключи и подписка живут только на телефоне.

## Сборка

`libbox.aar` в git нет — его нужно собрать локально.

```bash
./scripts/build-libbox.sh
./gradlew :core:test :app:assembleRelease
```

Нужны JDK 17, Android SDK 37, Go 1.25+ и NDK 28. Скрипт собирает slim sing-box **v1.14.3** (TUN, VLESS, Reality/uTLS, gVisor). В сборку не входят QUIC, WireGuard, Tailscale, Naive и OpenVPN. Версия ядра и патчи задаются в `sing-box.properties` и `scripts/libbox/patches`.

Подпись release читается из локального `keystore.properties` (см. `keystore.properties.example`). Файл ключа в репозиторий не кладётся.

## Always-on VPN

Включается только системой: **Сеть и интернет → VPN → Layer → Всегда включённый VPN**. Приложение само этот переключатель не ставит.

## Лицензия

[GNU GPL v3](LICENSE). Ядро — sing-box / libbox, тоже GPL-3.0; из-за линковки тот же тип лицензии обязателен и для Layer.

Сторонние списки (хостлист рекламы, itdoginfo, ядро) — в [THIRD_PARTY.md](THIRD_PARTY.md). Layer не связан с их авторами.
