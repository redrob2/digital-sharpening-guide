# Companion app BLE protocol (v1)

The stick advertises as `SharpGuide-XXXX` (last two MAC bytes, hex) with one
primary GATT service. All multi-byte integers are **little-endian**.

| What | UUID |
|---|---|
| Service | `5347a000-0b1e-4a6c-9d2e-6b6e69666521` |
| LIVE (read, notify) | `5347a001-0b1e-4a6c-9d2e-6b6e69666521` |
| SETTINGS (read, write, notify) | `5347a002-0b1e-4a6c-9d2e-6b6e69666521` |
| INFO (read) | `5347a003-0b1e-4a6c-9d2e-6b6e69666521` |
| POWERLOG (read) | `5347a004-0b1e-4a6c-9d2e-6b6e69666521` |
| OTA_CTRL (write, notify) | `5347a010-0b1e-4a6c-9d2e-6b6e69666521` |
| OTA_DATA (write with response) | `5347a011-0b1e-4a6c-9d2e-6b6e69666521` |

The app should request MTU 517 and `CONNECTION_PRIORITY_HIGH` right after
connecting, then enable notifications (CCCD `0x2902`) on LIVE, SETTINGS and
OTA_CTRL.

## LIVE — 16 bytes, notified on change (checked every 20 ms tick), at least every 200 ms

| Off | Type | Field |
|---|---|---|
| 0 | u8 | protocol version (= 1) |
| 1 | u8 | state (see below) |
| 2 | i16 | live angle × 100 (centi-degrees). `-32768` = no live angle on this screen |
| 4 | u16 | target angle × 10 |
| 6 | u8 | tolerance: 0 TIGHT ±2°, 1 NORMAL ±3°, 2 EASY ±5° |
| 7 | u8 | colour: 0 GREEN (on angle), 1 BLUE (too low), 2 RED (too high), 255 = none (not coaching) |
| 8 | u8 | current side: 0 A, 1 B |
| 9 | u8 | flags: bit0 buzzer, bit1 steady, bit2 tenths, bit3 on external power (USB present or charging) |
| 10 | u16 | strokes side A |
| 12 | u16 | strokes side B |
| 14 | u8 | on-angle % this session (0..100) |
| 15 | i8 | battery bars 0..4, -1 unknown |

The colour is the stick's own classification (with its hysteresis), so the app
should paint exactly that, not re-derive it from the angle.

State values: 0 BOOT, 1 SET_TARGET, 2 SET_TOLERANCE, 3 ZERO_CAL, 4 ACTIVE,
5 REZERO, 6 VERIFY, 7 SUMMARY, 8 HISTORY, 9 FAULT, 10 RESUME_PROMPT, 11 SLEEP.

The live angle is present in SET_TARGET (the angle you're about to set),
ACTIVE (the bevel being coached) and VERIFY (accuracy check reading).

## SETTINGS — 8 bytes (read / write / notify on change)

| Off | Type | Field |
|---|---|---|
| 0 | u8 | protocol version (= 1) |
| 1 | u8 | tolerance (0..2) |
| 2 | u16 | target × 10, clamped by the stick to 50..450 (5.0°..45.0°) |
| 4 | u8 | flags: bit0 buzzer, bit1 steady, bit2 tenths |
| 5 | u8 | screen brightness, 10..100 % of normal. Write 0 to leave it unchanged |
| 6..7 | — | reserved, write 0 |

Writing the full 8 bytes applies them right away (target included, even
mid-session) and saves tolerance/buzzer/steady/tenths to the stick's flash.
The stick notifies SETTINGS back with the values it actually applied.

## INFO — UTF-8 text

`fw=1.2.0;board=S3;proto=1;slot=app0` — `board` is `PLUS`, `PLUS2` or `S3`; `slot` is the running app partition (informational). Parse it as `;`-separated `key=value` pairs and ignore unknown keys.

## POWERLOG — battery voltage history (diagnostic)

The stick's power chip reports voltage, not current, so battery drain is read
from how fast the voltage falls. The stick records one sample every 10 s
(up to 120 = 20 min, oldest dropped) whether or not anything is connected.

`[version=1, count, period_s, 0]` then `count` entries, oldest first:
u16 battery mV, u8 backlight % actually applied, u8 flags (bit0 a central is
connected, bit1 on external power, bit2 full-rate LIVE forced, bits 4-7 state).

## Firmware update (OTA)

The image sent is the **app image** (`firmware.bin`, starts with byte `0xE9`).
A merged web-flasher image (`sharpening-guide-<board>-v<ver>.bin`, which has
a partition table at offset 0x8000 — bytes `AA 50`) must have its first
0x10000 bytes stripped by the app first.

Every firmware build embeds the ASCII tags `SG-BOARD:<PLUS|PLUS2|S3>;` and
`SG-FW:<version>;` (for example `SG-FW:1.2.0;`), the second so the app can show
a file's version before sending it. The app
must find that tag in the image and refuse to send it if it doesn't match
INFO's `board`.

OTA_CTRL commands (app → stick):

| Bytes | Meaning |
|---|---|
| `01` + u32 size | BEGIN. The stick shows "UPDATE FIRMWARE?" and waits up to 20 s for button A; only then does it erase the spare OTA slot (a few more seconds) and reply. Allow 30 s for the reply |
| `03` + u32 crc32 | END. CRC-32 (IEEE, same as `java.util.zip.CRC32`) of all bytes sent |
| `05` | ABORT |
| `09` + u8 minutes + u8 full + u8 flip_s | Developer builds only (`-D SG_DIAG`). DIAG (power measurements): stay awake and undimmed for `minutes` (0 cancels); if `full` = 1, send LIVE every tick even when unchanged; if `flip_s` > 0, flip the backlight between 100 % and 10 % every `flip_s` seconds. Replies `[09, 00]` |
| `07` + u8 mode | Developer builds only (`-D SG_DIAG`). RESTART the stick (mode 0 = normal restart, 1 = 200 ms deep-sleep wake, 2 = power off / sleep, as the power button does). Replies `[07, 00]`, restarts ~0.5 s later |

Stick → app notifications on OTA_CTRL: `[cmd, status]` where status 0 = OK,
1 = bad state/command, 2 = flash error, 3 = size/crc mismatch, 4 = wrong chip,
5 = battery too low (BEGIN only: 0 bars and not on USB power — plug the stick in),
6 = declined (BEGIN only: B was pressed on the stick, or nobody pressed A in time).

The link has no pairing. Settings can be changed by anything in range; a
firmware update cannot start without that button press on the stick.
After a successful END reply the stick reboots into the new firmware about
one second later (the BLE link drops — that's expected).

Between BEGIN and END, write the image to OTA_DATA in order, in chunks of up
to min(MTU − 3, 512) bytes, using write **with** response so the stick's flash writes
pace the transfer.
