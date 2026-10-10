# SoftSSTP

[English](README.md) · **Русский**

Открытый SSTP VPN-клиент для Android на Flutter, интерфейс в стиле Material Design 3.
Работает с любым сервером, который поддерживает протокол Microsoft SSTP (MS-SSTP).
Чаще всего это [SoftEther VPN Server](https://www.softether.org/) и
[VPN Azure Cloud](https://www.vpnazure.net/).

[![Build status](https://github.com/MrKsey/SoftSSTP/actions/workflows/release.yml/badge.svg)](https://github.com/MrKsey/SoftSSTP/actions/workflows/release.yml)
![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-green.svg)
![Flutter](https://img.shields.io/badge/Flutter-3.x-blue.svg)
![License](https://img.shields.io/badge/license-GPLv3-blue.svg)

## Скриншоты

<p align="center">
  <img src="docs/screenshots/screenshot-1.jpg" height="520" alt="Главный экран SoftSSTP">&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;
  <img src="docs/screenshots/screenshot-2.jpg" height="520" alt="SoftSSTP подключён">&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;
  <img src="docs/screenshots/screenshot-3.jpg" height="520" alt="Раздельное туннелирование">
</p>

## На основе

SoftSSTP - форк проекта [CluvexStudio/ZedPass](https://github.com/CluvexStudio/ZedPass).

| Слой | Проект | Роль |
|------|--------|------|
| SSTP / PPP / TLS | [kittoku/Open-SSTP-Client](https://github.com/kittoku/Open-SSTP-Client) (MIT) | Исходный Android-клиент SSTP, отсюда взят движок протокола. |
| Плагин Flutter | [NavidShokoufeh/sstp_flutter](https://github.com/NavidShokoufeh/sstp_flutter) | Flutter-обёртка над этим движком. |
| Базовое приложение | [CluvexStudio/ZedPass](https://github.com/CluvexStudio/ZedPass) | Проект, с которого начался форк. |

## Возможности

- Material Design 3 с темой Material You. Дневной, ночной и авто режим, иконка в
  левом верхнем углу переключает между ними.
- Несколько профилей VPN. В каждом свои сервер, DNS, прокси, PPP, маршрутизация,
  раздельное туннелирование, автопереподключение и дополнительные параметры, плюс
  имя и цвет иконки. Активный профиль выбирается на главном экране, новый
  сохраняется по кнопке Save.
- Публичные профили из VPN Gate. В списке видно страну, качество линии и пинг,
  измеренный с вашего устройства. Любой сервер добавляется одним нажатием, логин и
  пароль `vpn`.
- Подключение и отключение с контролем состояния на главном экране и в уведомлении.
- Текущая скорость в битах и общий объём трафика в байтах.
- Публичный IP и геолокация до и после подключения, плюс внутренний IP, который
  выдал сервер.
- Раздельное туннелирование. В туннель идут только выбранные приложения, есть поиск
  по списку. Выбор сохраняется, даже если правило выключить.
- Автопереподключение с настройкой числа попыток и интервала.
- Возврат VPN-слота. Если другое VPN (например AdGuard) отбирает слот, SoftSSTP
  забирает его обратно и переподключается.
- Восстановление при смене сети. Переключение между Wi-Fi и мобильными данными
  пересобирает туннель автоматически.
- Настройка DNS, прокси, SSL и PPP. Свой DNS, HTTP-прокси, свой SNI, версия SSL,
  проверка имени хоста и сертификата, MTU/MRU, протокол аутентификации, IPv4/IPv6,
  статический IP и маршрутизация.
- Счётчики трафика переживают выключение экрана и пересоздание приложения.

## Требования

- Android 8.0 (API 26) или новее
- Доступный SSTP-сервер с включённым DHCP
- Только PAP и MS-CHAPv2, без EAP

## Установка

1. Откройте страницу [Releases](https://github.com/MrKsey/SoftSSTP/releases).
2. Скачайте APK под своё устройство:
   - `SoftSSTP-<версия>-arm64-v8a.apk` для современных 64-битных устройств
     (рекомендуется)
   - `SoftSSTP-<версия>-armeabi-v7a.apk` для старых 32-битных устройств
3. Установите APK. Если система попросит, разрешите установку из браузера или
   файлового менеджера.
4. Откройте SoftSSTP, зайдите в Настройки, введите данные сервера и подключитесь.

Все релизы подписаны одним сертификатом, поэтому обновления ставятся поверх
предыдущей версии без удаления.

## Использование

1. Нажмите на шестерёнку, чтобы открыть Настройки. Блок Profiles добавляет,
   редактирует, включает и удаляет профили, там же лежат Diagnostics и About.
2. Откройте профиль и укажите Хост, Порт (по умолчанию 443), Логин и Пароль. При
   желании задайте имя и цвет иконки, затем нажмите Save.
3. При необходимости настройте DNS, прокси, SSL, PPP, маршрутизацию и раздельное
   туннелирование.
4. Для готового сервера нажмите Add public profile и выберите один из списка
   VPN Gate.
5. На главном экране нажмите на карточку сервера, чтобы сменить профиль, затем
   нажмите кнопку подключения. Повторное нажатие или касание уведомления отключает
   VPN.

## Решение проблем

### Работает другое VPN (AdGuard)

Android разрешает активным только один VPN одновременно. Перед подключением
SoftSSTP занимает VPN-слот, поэтому обычно вытесняет другое VPN сам. Если то VPN
снова перехватывает слот (у него есть сторож) или туннель переподключается на
глазах, так и задумано. SoftSSTP дожидается, пока другое VPN запустится, забирает
слот и снова поднимает туннель, а уведомление показывает, что происходит.

Если слот удержать не удаётся, у другого приложения, скорее всего, включён
постоянный VPN (Always-on VPN), который система не разрешает вытеснять:

- отключите постоянный VPN у другого приложения (Настройки, Сеть и интернет, VPN,
  значок шестерёнки);
- или добавьте SoftSSTP в список исключений (в AdGuard: Настройки, Управление
  приложениями, SoftSSTP, Обход);
- или переключите AdGuard в режим прокси вместо режима VPN.

### Смена сети (Wi-Fi и мобильные данные)

Сокет SSTP привязан к текущей сети, поэтому её смена разрывает туннель. SoftSSTP
следит за сетью и пересобирает туннель в новой сам, подключаться вручную не нужно.
Число попыток для этого и для возврата слота ограничено, так что мигающая сеть или
постоянный перехват не зациклят подключение.

## Сборка из исходников

```bash
# Требуется: Flutter (stable), JDK 17, Android SDK

git clone https://github.com/MrKsey/SoftSSTP.git
cd SoftSSTP/SoftSSTP

flutter pub get
dart run flutter_launcher_icons

# Универсальный APK
flutter build apk --release

# APK по архитектурам (arm64 и arm)
flutter build apk --release --split-per-abi \
  --target-platform android-arm64,android-arm
```

APK появятся в `build/app/outputs/apk/release/`.

Пуш в `main` или тег `v*` запускает CI. Он собирает обе архитектуры и публикует
GitHub Release. Версия берётся из отправленного тега или растёт от последней
существующей. Сборки подписываются ключом из секретов репозитория.

## Донаты

Поддержать проект можно донатом на любой из кошельков ниже.

<table>
  <tr>
    <td align="center">
      <img src="donate/bitcoin.jpg" width="220" alt="QR-код Bitcoin"><br>
      <b>Bitcoin</b><br>
      <code>bc1qdf9wryj6tnyyr7ed4dkfv03nn0dxjf27xshfcr</code>
    </td>
    <td align="center">
      <img src="donate/ethereum.jpg" width="220" alt="QR-код Ethereum"><br>
      <b>Ethereum</b><br>
      <code>0xE22FAB5337A5506D9e7Ef9D4914De4d963Fbbf88</code>
    </td>
  </tr>
  <tr>
    <td align="center">
      <img src="donate/solana.jpg" width="220" alt="QR-код Solana"><br>
      <b>Solana</b><br>
      <code>7XcNM2M9rndFbJdjRtyycxkQXq8dPXEP87toiQX4EZfx</code>
    </td>
    <td align="center">
      <img src="donate/tron.jpg" width="220" alt="QR-код Tron"><br>
      <b>Tron</b><br>
      <code>TCYpTxSmdPN8p4fmRwtPGk3HHBf77FypeA</code>
    </td>
  </tr>
</table>

## Лицензия

SoftSSTP распространяется под GNU General Public License v3.0, текст в
[`SoftSSTP/LICENSE`](SoftSSTP/LICENSE). Входящие компоненты сохраняют свои
лицензии. Гарантий нет.
