# Layer

A personal Android VPN client. This is not a VPN service: you bring your own `vless://` link or HTTPS subscription.

Личный VPN-клиент для Android: Kotlin + Jetpack Compose + Android VpnService + [sing-box](https://github.com/SagerNet/sing-box) (libbox).

Это пет-проект, не коммерческий продукт и не магазин ключей. Готового VPN «из коробки» нет: UUID, адрес сервера и подписка в исходниках не хранятся. Гарантий нет — пользуйтесь на свой риск.

По умолчанию трафик идёт **напрямую**. Через VLESS уходят только ресурсы из автоматического списка, выбранные приложения и выбранные домены.

Сборка рассчитана на **arm64** (телефоны на Snapdragon / Tensor / Exynos и т.п.). Эмулятор x86_64 и 32-битные устройства не поддерживаются.

`app/libs/libbox.aar` в git нет: его нужно собрать локально скриптом ниже.

## Сборка

1. Откройте проект в Android Studio или поставьте JDK 17 и Android SDK 36.
2. Соберите `libbox.aar` из официального sing-box:

```bash
./scripts/build-libbox.sh
```

3. Соберите приложение:

```bash
./gradlew :core:test :app:assembleDebug
```

4. Установите APK и добавьте VLESS-ссылку или подписку через «+» на главном экране.

## Always-on VPN

Включается только в системных настройках Android: Сеть и интернет → VPN → Layer → «Всегда включённый VPN».

## libbox

Официальный AAR собирается из sing-box **v1.14.0** только с нужными тегами
(TUN, VLESS, Reality/uTLS, gVisor). Нужны Go 1.25+, JDK 17 и Android NDK 28:

```bash
brew install go
sdkmanager --install "ndk;28.0.13004108"
./scripts/build-libbox.sh
```

Скрипт кладёт `app/libs/libbox.aar` (arm64). QUIC, WireGuard, Tailscale и Naive в сборку не входят.

## Лицензия

Layer распространяется под [GNU GPL v3](LICENSE). Ядро VPN — sing-box / libbox, тоже GPL-3.0: из-за линковки тот же тип лицензии обязателен и для этого приложения.
