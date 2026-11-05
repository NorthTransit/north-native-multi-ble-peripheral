# North changes to this fork

Native changes North Transit made on top of upstream `react-native-multi-ble-peripheral`. Bug numbers refer to `North/docs/bugs/`.

| Bug | Change | Version |
|---|---|---|
| #003 | Write events reach JS: listener ids normalized (`f4e35d0`) | 0.1.9 |
| #005 | `stopAdvertising` clears `advertiseCallbacks[id]` so advertising can restart | 0.1.10 |
| #011 | `invalidate()` releases advertisers and GATT servers on a JS reload; one GATT server per peripheral (closed on stop, closed before reopen); callbacks answer on the server that got the request; CCCD writes always answered; no events into a dead React instance; diagnostic logging | unreleased (`fix/reload-cleanup`) |

Logs: `adb logcat -s ReactNativeMultiBlePeripheral`.
