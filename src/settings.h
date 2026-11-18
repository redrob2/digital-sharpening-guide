#pragma once
#include "types.h"

namespace settings {
    void begin();

    Tolerance  load_tolerance();
    void       save_tolerance(Tolerance t);

    bool       load_buzzer();
    void       save_buzzer(bool on);

    // Steady mode: accel-reference smoothing + display/colour hysteresis.
    // Defaults ON — it is the better behaviour; the setting exists so a tester can
    // A/B it against the old response on one device without reflashing.
    bool       load_steady();
    void       save_steady(bool on);

    // Show the live ACTIVE angle to 0.1 deg instead of whole degrees.
    // Defaults OFF; toggled by A-hold on the TOLERANCE screen or from the app.
    bool       load_tenths();
    void       save_tenths(bool on);

    // Last target angle the user picked (preset or companion app), so the stick
    // starts where they left off. Stored as tenths of a degree; kDefaultTargetDeg
    // on a stick that has never saved one. Written only when the value changes.
    constexpr float kDefaultTargetDeg = 14.0f;
    float      load_target();
    void       save_target(float deg);

    Vec3       load_gyro_bias();
    void       save_gyro_bias(Vec3 bias_dps);

    // Session history, newest first. Written ONCE per session (never per stroke —
    // NVS has finite write endurance). Returns how many records were filled.
    int        load_session_history(SessionRecord* out, int max);
    void       push_session_record(const SessionRecord& r);
    void       clear_session_history();
}
