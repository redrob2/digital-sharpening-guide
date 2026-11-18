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
    // True from OTA BEGIN until END/ABORT/timeout. While active the main loop
    // shows the update screen and must not sleep.
    bool ota_active();
    int  ota_percent();     // 0..100
}
