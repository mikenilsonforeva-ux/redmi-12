# Сборка, установка и откат (Redmi 12 / MT6631)

Устанавливать только через Magisk-модуль `revamped_fmradio`. Не использовать `adb install -r`, не копировать APK в `/system` или `/system_ext`, не менять `sharedUserId`, package name и SELinux-механизм.

## 1. Бэкап текущего состояния (обязательно, это же и откат)

```bash
# копия установленного модуля вместе с sepolicy.rule, system.prop и libfmjni.so
adb shell su -c 'rm -rf /sdcard/revamped_fmradio_backup && cp -a /data/adb/modules/revamped_fmradio /sdcard/revamped_fmradio_backup'
adb pull /sdcard/revamped_fmradio_backup ./revamped_fmradio_backup
```

Модуль `fmradio_policy_persist` не трогаем.

## 2. Сборка APK

В этой версии изменились только Java-исходники в `src/`, ресурсы в `res/` и манифест. **Новых зависимостей и изменений native-кода нет**, поэтому ваш прежний ручной рецепт сборки (JDK 21, `android-36`, build-tools `36.0.0`, framework-классы с телефона, AndroidX CardView и Core, локальный ключ) работает без изменений: новые файлы подхватываются маской `src/**/*.java`.

Изменённые и добавленные файлы:

- новые: `FmSignalActivity.java`, `FmSignalMonitor.java`, `FmSignalHistory.java`, `views/FmSignalGraphView.java`, `res/layout/fm_signal_activity.xml`, `res/layout/fm_signal_row.xml`, `res/drawable/signal_card_bg.xml`;
- изменены: `AndroidManifest.xml` (одна новая `<activity>`), `res/menu/fm_action_bar.xml` (один пункт), `FmMainActivity.java` (обработчик пункта), `res/values/colors.xml`, `res/values-night/colors.xml`, `res/values/strings.xml`, `res/values-ru/strings.xml`.

Подпись: **тем же ключом, которым подписан установленный APK.** Сравните отпечатки:

```bash
apksigner verify --print-certs старый.apk | grep SHA-256
apksigner verify --print-certs новый.apk  | grep SHA-256
```

Если ключ утерян, подписи будут различаться. Как именно система поведёт себя при смене подписи системного приложения, я проверить не мог: в этом случае сначала проверьте на отдельной сборке и держите бэкап из п. 1.

Проверка собранного APK:

```bash
aapt2 dump badging новый.apk | grep -E "package:|uses-permission|sdkVersion"
unzip -l новый.apk | grep -E "lib/|AndroidManifest"   # lib/ в APK быть не должно: libfmjni.so лежит в модуле
```

Ожидается `package: name='com.android.fmradio'`, прежний набор разрешений, отсутствие `sharedUserId`.

## 3. Magisk ZIP

Скрипт берёт копию вашего **существующего** модуля и подменяет в ней только APK, остальное (`sepolicy.rule`, `system.prop`, `libfmjni.so`) остаётся байт-в-байт:

```bash
tools/make_magisk_update.sh ./revamped_fmradio_backup RevampedFMRadio.apk RevampedFMRadio-signal-update.zip
adb push RevampedFMRadio-signal-update.zip /sdcard/Download/
```

В приложении Magisk: «Модули» → «Установить из хранилища» → выбрать ZIP → перезагрузить телефон.

Быстрая альтернатива без ZIP (тот же эффект, модуль обновляется на месте):

```bash
adb push RevampedFMRadio.apk /sdcard/RevampedFMRadio.apk
adb shell su -c 'cp /sdcard/RevampedFMRadio.apk /data/adb/modules/revamped_fmradio/system/system_ext/priv-app/RevampedFMRadio/RevampedFMRadio.apk'
adb reboot
```

Проверьте реальный путь внутри модуля командой `adb shell su -c 'find /data/adb/modules/revamped_fmradio -name "*.apk"'` до копирования.

## 4. Проверка после установки

Чек-лист в `docs/AUDIT.md`, раздел 4. Минимум: радио играет, seek/scan/запись работают, меню «⋮» → «Диагностика сигнала» открывается, после перезагрузки радио работает.

## 5. Откат

Вариант A, вернуть старый APK из бэкапа:

```bash
adb push ./revamped_fmradio_backup/system/system_ext/priv-app/RevampedFMRadio/RevampedFMRadio.apk /sdcard/old.apk
adb shell su -c 'cp /sdcard/old.apk /data/adb/modules/revamped_fmradio/system/system_ext/priv-app/RevampedFMRadio/RevampedFMRadio.apk'
adb reboot
```

Вариант B, восстановить модуль целиком: скопировать `revamped_fmradio_backup` обратно в `/data/adb/modules/revamped_fmradio` (через `su`, с сохранением прав `cp -a`) и перезагрузиться.

Если телефон не загружается: Magisk отключает модули в безопасном режиме (удерживать кнопку уменьшения громкости при загрузке), после чего модуль можно откатить. Прошивать ничего не нужно.

## 6. Тесты без Android SDK

```bash
tests/run_tests.sh   # юнит-тест буфера истории RSSI на обычной JVM
```
