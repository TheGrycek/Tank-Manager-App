# Tank Manager App

Android client for a camper/RV water tank management system. It talks over Bluetooth LE to an ESP32
controller that monitors the gray and clean water tanks, drives the gray-tank drain valve and a
tank heater, and reports vehicle tilt.

The firmware and board live in a separate repo,
[Tank-Manager-Design](https://github.com/TheGrycek/Tank-Manager-Design) (`esp32/` MicroPython firmware, `PCB-design/` and `PCB-front/` KiCad projects, `temp_sensor/` NTC
calibration). The firmware is split into `main.py` (asyncio tasks), `sensors.py` (level,
temperature, MPU), `peripherals.py` (OLED, valve, heater, buzzer, encoder) and `setup.py`
(settings, menu, Bluetooth) and `history.py` (level history); `esp32/README.md` there describes it.
The BLE protocol below is a contract between the two repos — change both sides together.

## Project assumptions

The system consists of:

- a gray water tank with a liquid level sensor, a drain valve, a heating foil and a temperature
  sensor,
- a clean water tank with a liquid level sensor,
- an MPU that reads the vehicle's position (tilt),
- an OLED display showing the system state,
- a rotary encoder with push button for the settings menu and manual valve control,
- an ESP32 controller with BLE,
- this Android app for remote control.

Requirements and hardware facts:

- **Valve**: 3-wire motorized ball valve (12-24 V DC). The common (green-yellow) wire is on GND; +12 V
  on the blue wire opens it, on the brown wire closes it. An internal limit switch disconnects the
  motor once fully open or fully closed. A move takes about 22 s at 15-45 mA (up to 100 mA at the start), measured without water pressure. (The first valve, an SKT 2012-02, had the opposite polarity
  and was replaced.)
- **Heating foil** must draw less than 5 A; measured 1.8 A at 12.8 V (about 7.1 Ω, 2.0 A at
  14.4 V while the LiFePO4 battery charges), fused with 3.15 A T.
- **Temperature sensor** is an NTC10k; the **MPU** is an MPU-9250.
- **App** (phone or tablet) must be able to open and close the valve and read the current liquid
  level in both tanks and the vehicle's position.
- **Buzzer** sounds when the gray water level exceeds the threshold set by the user (it can be
  switched off in the settings).
- **Encoder menu** allows opening the gray water valve manually and changing the settings.
- **Security**: connecting to the ESP32 must require a password, to protect the system against
  break-ins.

## Build

Single-module Gradle project (`:app`), Java only (no Kotlin sources), XML layouts, no DI or
architecture libraries.

- Gradle 9.8.1 (wrapper), AGP 9.4.1, compileSdk/targetSdk 35, minSdk 24, Java 11 source level.
- Dependency versions are in `gradle/libs.versions.toml`.
- If there is no `java` on PATH, point `JAVA_HOME` at Android Studio's bundled JDK (the `jbr`
  directory of the installation; currently JDK 25, which requires Gradle >= 9.1):

```bash
export JAVA_HOME=<android-studio>/jbr
./gradlew assembleDebug          # build
./gradlew test                   # JVM unit tests (ProtocolTest)
./gradlew connectedAndroidTest   # needs a device/emulator
./gradlew lint
```

BLE cannot be exercised on an emulator; real behaviour needs a phone plus the ESP32 board.

## Code layout

Everything is under `app/src/main/java/com/example/tank_manager/` (package, namespace and
`applicationId` are `com.example.tank_manager`).

- `TankService` — owns the single `BluetoothGatt` and the whole connection. While a device is
  selected it runs as a foreground service (type `connectedDevice`, with a notification that has a
  Disconnect action), so the link survives closing the screen. After a link that has worked is lost
  it reconnects with `autoConnect` for as long as needed; it also waits for Bluetooth to be switched
  back on. A failed first attempt ends the session. It caches the last value of every
  characteristic for a screen that binds later. Callbacks to the screen (`TankService.Listener`)
  are always on the main thread.
- `MainActivity` — the launcher and the only real screen; it binds to `TankService` and holds no
  Bluetooth objects. Three tabs (`TabLayout`): "Status" (tank values with the value right of each label, valve
  switch, heater indicator light), "Settings" (buzzer, thresholds, auto-heating, clean tank fill
  mode, sent through the SETTINGS characteristic) and "History" (chart `HistoryChart` for 24 h /
  7 / 30 days and the statistics, read from `HistoryStore`, also without a connection). A
  `selected_device` intent extra (a MAC address) starts a session; the extra is consumed. The valve
  switch is enabled only after the first VALVE value, shows orange until a command is acknowledged
  and reverts if the write fails. A label between the "Valve" text and the switch (`valve_state_txt`) shows the actual state. The connection state is a pill next to the
  buttons (`connection_pill`): border, light fill and light in the state colour (green / orange,
  pulsing / red).
- `TiltView` — custom view drawing the camper from the side (pitch), from behind (roll) or from
  above on a north-up compass (heading), selected with `app:tiltMode`; animated, grey without a
  value. The Position section shows one per column with the value under it, coloured green /
  orange / red by how level the camper is (< 1°, < 3°, more).
- `Protocol` — UUIDs and payload decoding/encoding (pure Java, covered by `ProtocolTest`).
- `BTListActivity` — BLE scan list. Lists only devices advertising the manager service UUID and on
  tap hands `selected_device` to the existing `MainActivity`. Permissions depend on the SDK level:
  API 31+ needs BLUETOOTH_SCAN (`neverForLocation`) and BLUETOOTH_CONNECT; API 24–30 needs
  ACCESS_FINE_LOCATION and Location Services switched on.
- `adapter/RecyclerAdapter`, `model/RecyclerModel`, `BTLEDevice` — scan list plumbing. The field
  called `uuid` in these actually holds the device MAC address.
- `Utils` — `toast()` helper.
- All icons are our own vector drawings (no third-party artwork): `drawable/ic_logo`, `ic_devices`,
  `ic_disconnect`, `ic_back` and the adaptive launcher foreground `ic_launcher_foreground` on the sand
  background `#F4EDCF`. They are generated by `tools/gen_icons.py` together with the legacy PNG
  launcher icons in `mipmap-*` and `ic_launcher-playstore.png`; change the script, not the files.
- Layouts: `activity_main.xml`, `activity_btlist.xml`, `bt_item.xml`. Click handlers are wired with
  `android:onClick` (`openBTList`, `breakBTConnection`, `returnMain`), so those public
  `(View)` methods must keep their names.
- Some view IDs contain typos that the code depends on: `heate_on_light` (heater light),
  `bluetootl_list_btn`, `gray_water_lvl_temp`.

## BLE protocol

Peripheral advertises as `Mati&Mati` with service `19b10000-e8f2-537e-4f6c-d104768a1214`.
Characteristics are `00000000-0000-0000-0000-00000000000N`; all values are little-endian.

| N | Name | Payload | Access | Meaning |
|---|------|---------|--------|---------|
| 1 | CLEAN_LEVEL_SENSOR | uint8 | read, notify | clean tank level, 0–100 % |
| 2 | GRAY_LEVEL_SENSOR | uint8 | read, notify | gray tank level, 0–100 % |
| 3 | GRAY_TEMP_SENSOR | int16 | read, notify | gray tank temperature in °C × 100; `-32768` = sensor fault (app shows `-- °C`) |
| 4 | MPU_SENSOR | 3 × float32 | read, notify | pitch (> 0 front up), roll (> 0 right side down), heading of the front (0–360, clockwise from magnetic north), degrees; not sent until the MPU is calibrated, nor during a calibration |
| 5 | VALVE | uint8 | read, write, notify, indicate | app writes 0 = close, 1 = open; firmware echoes the accepted command |
| 6 | VALVE_STATE | uint8 | read, notify, indicate | actual valve state: 0 closed, 1 open, 2 closing, 3 opening, 4 error (was VALVE_LOCKED) |
| 7 | HEATER | uint8 | read, notify, indicate | 1 = heater on (read-only in the app) |
| 8 | SETTINGS | 5 × uint8 | read, write, notify | buzzer on (0/1), gray level alarm threshold (50–100 %), auto-heating (0/1), heating threshold (1–5 °C), clean tank fill mode (0/1; not stored, a 4-byte payload leaves it unchanged) |
| 9 | HISTORY | see below | write, notify | level history download |

- Sensor values are pushed about once per second; the app never polls with reads. VALVE,
  VALVE_STATE and HEATER are indicated on change and additionally re-sent as plain notifications
  about every 2 s, so an app that subscribes late (reconnect) still gets the current state. VALVE
  always carries the target (1 = open or opening); VALVE_STATE carries the movement.
- All characteristics require an authenticated (bonded, MITM, LE Secure) link. The ESP32 requests
  pairing on connect; the 6-digit passkey is random for every pairing and shown on the OLED. Bonds
  are stored on the ESP32 in `ble_secrets.json`. Android shows the system pairing dialog; the app
  has no bonding code of its own. Phones connect from a resolvable private address that changes with every
  connection; the ESP32 controller does not resolve it under MicroPython, so the firmware does it in
  software (`Bluetooth._bonded_key`, AES with the phone's stored IRK) and maps bond lookups to the
  phone's identity address. Without that a bonded phone has to pair again on every connection.
- The ESP32 accepts one connection and does not advertise while connected, so the app must fully
  release its GATT client (`disconnect()` + `close()`) to make reconnecting possible. `TankService`
  owns exactly one `BluetoothGatt`; keep it that way.
- After service discovery the app enables updates one characteristic at a time through a queue
  (`enableNextNotification`), because Android allows only one pending GATT operation. Keep any new
  GATT operations serialized the same way.
- The CCCD value (notification or indication) is chosen from the characteristic's properties.
- **History**: the firmware stores a sample every 10 min (average gray/clean level and temperature)
  in a 30-day ring buffer (`history.bin`). After the notifications `TankService` asks for an MTU of
  247 (`onMtuChanged` only acts on its own request: Android 14+ exchanges the MTU by itself), then
  writes HISTORY `<I first seq it lacks, I Unix time>`; only after that write is the client `ready`.
  The controller dates its samples from that time and answers with notifications `<I first seq,
  B count>` + count × `<I time, B gray, B clean, h temp×100>` (time with bit 31 set = undated,
  skipped; bit 7 of gray = driving, of clean = tilted, from the MPU: such samples are drawn faint and
  left out of the statistics), ending with a count-0 packet whose seq is the next sample; new samples are pushed while
  connected. `HistoryStore` (SQLite, keyed by time, single instance) keeps everything and the next
  seq; `HistoryStats` (pure Java, `HistoryTest`) computes rates, forecasts ("Full in" = to the gray alarm
  threshold, "Empty in") and refill/emptying events (jumps of >= 10 % between steady samples).
- Before every service discovery `TankService` clears Android's GATT cache (hidden
  `BluetoothGatt.refresh()` by reflection). The controller never sends Service Changed, so a
  bonded phone would otherwise keep the attribute handles of an older firmware and show values
  under the wrong characteristics. The firmware registers the characteristics in a fixed order
  (`Bluetooth._CHARACTERISTIC_ORDER`); add new ones at the end.

## Firmware behaviour the app relies on

- **Valve**: the firmware powers the motor until the valve's limit switch cuts its current (read
  on the shunt) and auto-closes 600 s after opening (also after a failed opening). Internal
  states are 0 closed, 1 open, 2 closing, 3 opening, 4 error. Error: no motor current within 1 s
  (wire, fuse, MOSFET) or the motor still running after 40 s (`MAX_MOVE_MS`); the motor is stopped,
  the OLED blinks VALVE ERROR with the cause, the buzzer beeps three times every 2 s (if enabled)
  and the next open/close command is a new attempt. The switch shows the 0/1 target (VALVE, kept
  in the error state); the label under it shows the actual state (VALVE_STATE), pulsing orange
  while the valve moves and pulsing red "ERROR" on an error. After every start-up the firmware
  drives the valve closed; no current then means it already is closed, not an error.
- **Front panel**: a rotary encoder with push button drives a settings menu on the OLED (press to
  open, turn to select, press to toggle or to edit a value, 15 s timeout). Items: buzzer, gray level
  threshold, auto-heating, heating threshold, fill mode, valve, MPU (starts the calibration), level
  (takes the current pitch and roll as zero). The settings are in the SETTINGS characteristic: the
  app writes all five bytes, the firmware clamps them, stores the first four in `settings.json` and
  answers with the values in force; panel changes are notified to the app. The display switches
  off after 60 s without input (the first touch only wakes it); the pairing code, the calibration
  and the open menu keep it on. The CPU runs at 80 MHz to save power.
- **Fill mode** (clean tank): the buzzer beeps faster as the clean level rises — silent up to 5 %,
  a 60 ms beep with a pause shrinking from 1.2 s to 60 ms, continuous from 95 %. It overrides the
  gray tank alarm while on, is off after a restart and switches itself off after 30 min (the app
  sees it through the SETTINGS notification).
- **Heater**: when auto-heating is enabled it turns on at <= threshold and off at >= threshold + 1 °C
  (gray tank NTC10k); it is forced off when auto-heating is disabled or the reading is implausible. The app shows it with an indicator light
  (`heate_on_light`, a round `View`: green on, red off, grey without a value).
- **Levels**: the sensor resistance is computed from the 200 Ω divider and mapped linearly between
  the calibrated empty and full resistance (defaults still need measuring).
- **Tilt** comes from an MPU9250 (accelerometer + magnetometer), sent as pitch/roll/yaw. The MPU
  is calibrated from the panel menu only (rotate the device for 20 s, then lay it flat); the result
  is kept on the ESP32 in `mpu_calibration.json`. Without it the firmware sends no MPU values, so
  the app keeps showing `--` for the angles (or the last values while a calibration runs). The
  angles are in the camper's axes (x forward, y left, z up; the sensor is mounted with Z towards
  the rear, Y towards the floor, X towards the left side). Pitch and roll are relative to the
  level set from the menu (SET ZERO); the heading is tilt-compensated and magnetic, not true north.

ESP32 pins, for reference (firmware and schematic agree): level ADC gray 34 / clean 35, temp ADC
32, encoder A 17 / B 23 / switch 5 (internal pull-ups, active low), valve open 13 / close 27, valve
current ADC 33 (10 Ω shunt, 10 mV = 1 mA, only logged so far), heater 25, buzzer 26 (BS170), MPU I2C 22/21, OLED (SH1106) I2C 18/19. All outputs are active-high and the board is
off-by-default: GPIO low means the load is unpowered. The valve is switched on the +12 V side
(P-MOSFETs; connector pin 1 = open (blue), pin 2 = common GND (green-yellow), pin 3 = close (brown)). The buzzer double-beeps every second while the
gray level is above the threshold with the valve closed, if enabled in the settings.

The main board takes 12 V through a reverse polarity P-MOSFET; a Pololu D24V10F5 makes 5 V for the
ESP32 module's VIN and the module's regulator makes the 3.3 V. The sensor inputs have 4.7 kΩ + 100 nF
RC filters and the MOSFET gates 15 V zener clamps.

The hardware is split into two boards in the design repo: `PCB-design/` (main board) and
`PCB-front/` (front panel: OLED and EC12 rotary encoder on the front, MPU-9250 on the back), joined
by a 9-wire cable with JST XH (2.54 mm) plugs on J7/J1 (3.3 V, GND, ENC_A, ENC_SW, OLED_SCK,
OLED_SDA, MPU_SDA, MPU_SCL, ENC_B).

## Known rough edges

- `TankService` uses the Android 13+ GATT calls that carry the value, and falls back to the
  deprecated ones on older systems (`writeCharacteristic`, `writeDescriptor`, both
  `onCharacteristicChanged` overloads). Keep both paths.
- The session ends only with the Disconnect button (in the app or the notification); nothing
  stops it automatically, and the controller accepts no other phone meanwhile.
- The app never reads characteristics; it relies on the firmware re-sending all state about every
  2 s, so values appear a moment after connecting.
- The theme is light-only because the screens use a fixed light palette.
