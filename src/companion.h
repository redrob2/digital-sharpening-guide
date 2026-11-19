#pragma once
#include <cstdint>
#include "power.h"

class App;

// BLE link to the Android companion app: live angle stream, remote settings
// and over-the-air firmware update. Protocol: docs/companion-protocol.md.
//
// Everything that touches App happens in update(), which the main loop calls
// once per tick. The BLE stack's own task only copies bytes into mailboxes
// (settings) or writes the OTA partition, which never touches App.
namespace companion {
    void begin();
    void update(App& app, uint32_t now_ms, const power::BatteryView& batt);

    bool connected();
    // Diagnostic (power measurements): true while a "stay awake for N minutes"
    // request from the link is still running.
    bool hold_awake(uint32_t now_ms);
    // Diagnostic: 0 = no override, else the backlight % the power test wants
    // right now (it flips between 100 and 10 on a fixed period).
    uint8_t brightness_override(uint32_t now_ms);
    // True from OTA BEGIN until END/ABORT/timeout. While active the main loop
    // shows the update screen and must not sleep.
    // A firmware update has been requested over the link and is waiting for a
    // button press on the stick. Nothing is erased or written until the user
    // answers; no answer within the timeout counts as "no".
    bool ota_confirm_pending();
    void ota_confirm_answer(bool yes);
    bool ota_active();
    int  ota_percent();     // 0..100
}
