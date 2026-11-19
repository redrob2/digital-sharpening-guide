#ifndef UNIT_TEST
#include <Arduino.h>
#include <M5Unified.h>
#include <esp_sleep.h>

#include "app.h"
#include "board.h"
#include "imu.h"
#include "input.h"
#include "settings.h"
#include "session.h"
#include "ui.h"
#include "feedback.h"
#include "power.h"
#include "companion.h"

static App       g_app;
static InputFSM  g_input;

static uint32_t  g_next_tick_ms       = 0;
static constexpr uint32_t TICK_PERIOD_MS = kLoopTickMs; // 50 Hz — ample for human motion (1-5 Hz)
// A single imu::read() returning false just means "no fresh IMU sample this tick"
// (MPU6886 data-ready clear at 50 Hz) — not a fault. Only a sustained run of
// failures (0.5 s) indicates a genuinely unresponsive IMU.
static constexpr uint32_t IMU_FAULT_TICKS = 25;

void setup() {
    auto cfg = M5.config();
    cfg.internal_imu = true;
    cfg.internal_spk = true;
    M5.begin(cfg);
    // After a software reset (OTA, restart) the panel may not have been
    // power-cycled; make sure it is out of sleep and lit before first paint.
    M5.Display.wakeup();
    M5.Display.setBrightness(255);
    setCpuFrequencyMhz(80);  // 80 MHz is ample for 50 Hz loop; saves ~25 mA

    // Safety: refuse to run if this binary was flashed onto a different board than
    // it was built for (e.g. the S3 build onto a Plus2). Each board has a distinct
    // sleep/wake + IMU path, so running the wrong one would misbehave silently.
    {
        m5::board_t expected;
        switch (board::variant()) {
            case board::Variant::PLUS:  expected = m5::board_t::board_M5StickCPlus;  break;
            case board::Variant::PLUS2: expected = m5::board_t::board_M5StickCPlus2; break;
            case board::Variant::S3:    expected = m5::board_t::board_M5StickS3;     break;
        }
        if (M5.getBoard() != expected) {
            M5.Display.setRotation(1);
            M5.Display.fillScreen(TFT_RED);
            M5.Display.setTextColor(TFT_WHITE);
            M5.Display.setTextSize(2);
            M5.Display.setCursor(6, 10);
            M5.Display.print("WRONG FIRMWARE");
            M5.Display.setTextSize(1);
            M5.Display.setCursor(6, 40);
            M5.Display.print("for this device.");
            M5.Display.setCursor(6, 56);
            M5.Display.print("Flash the build that");
            M5.Display.setCursor(6, 68);
            M5.Display.print("matches your stick.");
            // Battery protection: this screen is terminal (the device needs a
            // reflash), so don't hold full backlight on a ~120 mAh cell. Dim to
            // a still-readable level now, display off entirely after a minute.
            M5.Display.setBrightness(64);
            const uint32_t shown_ms = millis();
            bool display_off = false;
            while (true) {
                if (!display_off && millis() - shown_ms >= 60000) {
                    M5.Display.sleep();
                    display_off = true;
                }
                delay(1000);
            }
        }
    }

    // Consume any pending power-key press that woke / powered us, so it does
    // not surface as a BtnPWR click on the first M5.update() and bounce us
    // straight back to sleep (Plus) or power-off (S3). Plus AXP192 and S3
    // M5PM1 (M5Unified 0.2.20+) latch a click that getKeyState() drains.
    // Plus2 is a raw GPIO — getKeyState() is a no-op; the release-gate in
    // loop() covers that board.
    if (board::has_pmic_button()) {
        M5.Power.getKeyState();
    }

    ui::begin();
    feedback::begin();
    power::begin();
    settings::begin();
    session::begin();

    // Detect wake-with-session: only route to RESUME_PROMPT if we actually
    // deep-slept AND the session-state in RTC RAM is marked active.
    esp_sleep_wakeup_cause_t cause = esp_sleep_get_wakeup_cause();
    bool had_session_in_rtc = (cause != ESP_SLEEP_WAKEUP_UNDEFINED) && session::has_session();

    FaultCode fc = imu::begin();

    g_app.begin(had_session_in_rtc);

    // Companion-app BLE link. After App::begin so the first SETTINGS value it
    // publishes is the loaded one.
    companion::begin();
#ifdef SG_IMU_LOG
    Serial.begin(115200);
#endif

    // Push initial fault if IMU failed.
    if (fc != FaultCode::NONE) {
        App::Tick t{millis(), InputEvent::NONE, {0,0,-1}, {0,0,0}, fc};
        g_app.on_tick(t);
    }

    g_next_tick_ms = millis();
}

void loop() {
    uint32_t now = millis();
    if ((int32_t)(now - g_next_tick_ms) < 0) {
        delay(1);
        return;
    }
    // Catch-up clamp: if the scheduler fell more than 5 periods behind (e.g. a
    // long blocking call), resync instead of replaying ticks back-to-back —
    // burst catch-up ticks would over-integrate the Mahony filter, which
    // assumes a fixed 20 ms dt per update.
    if ((int32_t)(now - g_next_tick_ms) > (int32_t)(5 * TICK_PERIOD_MS)) {
        g_next_tick_ms = now;
    }
    g_next_tick_ms += TICK_PERIOD_MS;

    M5.update();
    // Power-key short press = "off" (deep sleep / S3 power-off). The matching
    // "on" is the EXT0 wake on GPIO35 (Plus/Plus2) or a PMIC power-on (S3).
    // Button_Class fires clicked on release, so a hold-to-wake that is still
    // down at the first update() would sleep again the moment the user lets
    // go. Ignore BtnPWR until we have seen it released once after boot.
    static bool pwr_released_since_boot = false;
    if (!pwr_released_since_boot) {
        if (!M5.BtnPWR.isPressed()) pwr_released_since_boot = true;
    } else if (M5.BtnPWR.wasClicked() || power::s3_power_key_clicked(now)) {
        // A live session's tick counters exist only in App until flushed; sleep
        // without this loses everything since the last stroke (RTC-RAM write only).
        g_app.flush_session_for_sleep();
        power::enter_deep_sleep();
    }
    bool a_pressed = M5.BtnA.isPressed();
    bool b_pressed = M5.BtnB.isPressed();
    InputEvent ev = g_input.update(now, a_pressed, b_pressed);

    // imu::read() always populates accel/gyro with the most recent (at most one
    // tick stale) sample; its false return means "no fresh data this tick", which
    // is benign and frequent. Only fault after IMU_FAULT_TICKS consecutive misses.
    static uint32_t imu_read_fails = 0;
    Vec3 accel = {0,0,-1}, gyro = {0,0,0};
    FaultCode fault = FaultCode::NONE;
    if (!imu::read(accel, gyro)) {
        if (++imu_read_fails >= IMU_FAULT_TICKS) fault = FaultCode::E02_SELF_TEST_FAILED;
    } else {
        imu_read_fails = 0;
    }

    power::BatteryView batt = power::battery_sample(now);

    // Firmware update from the companion app: the radio and flash own the
    // device until it reboots into the new image (or the transfer fails and
    // we fall back to the session below — App was frozen, not reset).
    // Update requested over the link: nothing happens until someone presses
    // A here. App is frozen meanwhile (as during the update itself).
    static bool     in_confirm       = false;
    static uint32_t confirm_shown_ms = 0;
    if (companion::ota_confirm_pending()) {
        if (!in_confirm) confirm_shown_ms = now;
        const int left = 20 - (int)((now - confirm_shown_ms) / 1000u);
        ui::draw_ota_confirm(left, !in_confirm);
        in_confirm = true;
        ui::set_backlight(100);
        if (ev == InputEvent::A_SHORT)      companion::ota_confirm_answer(true);
        else if (ev == InputEvent::B_SHORT) companion::ota_confirm_answer(false);
        companion::update(g_app, now, batt);
        return;
    }
    if (in_confirm) {
        in_confirm = false;
        if (!companion::ota_active()) g_app.redraw(now);   // declined or timed out
    }

    static bool in_ota = false;
    if (companion::ota_active()) {
        companion::update(g_app, now, batt);
        ui::draw_ota(companion::ota_percent(), !in_ota);
        in_ota = true;
        ui::set_backlight(100);
        return;
    }
    if (in_ota) {
        // Transfer dropped or was rejected. The running image is untouched;
        // restart rather than try to repaint whatever screen App was on.
        ui::draw_ota_failed();
        delay(1500);
        power::restart();
    }

    App::Tick tick{now, ev, accel, gyro, fault};
    g_app.on_tick(tick);
    companion::update(g_app, now, batt);

#ifdef SG_IMU_LOG
    // Tuning build only: raw IMU + the firmware's own answer over USB serial,
    // so candidate filters can be replayed offline against real strokes.
    if (g_app.current() == State::ACTIVE) {
        static uint32_t n = 0;
        if ((n++ % 50) == 0) {
            const Vec3 f = g_app.g_flat(), e = g_app.edge_axis(), b = g_app.gyro_bias();
            Serial.printf("C,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.5f,%.5f,%.5f,%.1f\n",
                          f.x, f.y, f.z, e.x, e.y, e.z, b.x, b.y, b.z, g_app.target_deg());
        }
        Serial.printf("I,%lu,%.5f,%.5f,%.5f,%.4f,%.4f,%.4f,%.3f\n",
                      (unsigned long)micros(), accel.x, accel.y, accel.z,
                      gyro.x, gyro.y, gyro.z, g_app.live_bevel());
    }
#endif

    // Battery icon goes on after the state's own draw so it sits on top of any
    // full-screen repaint that tick. Sampling is throttled inside power.
    ui::draw_battery(batt.bars, batt.charging);

    // A connected companion app counts as activity, so the stick doesn't power
    // off under someone watching the phone — but only for LINK_AWAKE_MAX_MS
    // after the last real activity, so a phone left connected can't drain the
    // cell. The backlight still dims on App's own timestamps (below).
    static constexpr uint32_t LINK_AWAKE_MAX_MS = 30u * 60u * 1000u;
    uint32_t idle_activity_ms = g_app.last_activity_ms();
    uint32_t idle_stroke_ms   = g_app.last_stroke_ms();
    if (companion::connected()) {
        const uint32_t since_activity = now - g_app.last_activity_ms();
        const uint32_t since_stroke   = now - g_app.last_stroke_ms();
        const uint32_t since_real     = since_activity < since_stroke ? since_activity : since_stroke;
        if (since_real < LINK_AWAKE_MAX_MS) idle_activity_ms = idle_stroke_ms = now;
    }
    // On external (USB) power there is no cell to protect, so the stick does
    // not idle off at all. The idle clock restarts when the cable comes out
    // (held_ms), so unplugging a long-idle stick gives the normal timeout
    // rather than an instant power-off. The power key and SUMMARY's B still
    // turn it off, and the backlight still dims.
    {
        static uint32_t held_ms    = 0;
        static bool     held_valid = false;
        if (batt.usb_power || batt.charging || companion::hold_awake(now)) {
            held_ms = now; held_valid = true;
        }
        if (held_valid) {
            if ((int32_t)(held_ms - idle_activity_ms) > 0) idle_activity_ms = held_ms;
            if ((int32_t)(held_ms - idle_stroke_ms)   > 0) idle_stroke_ms   = held_ms;
        }
    }

    // Sleep check uses App's real last-activity / last-stroke timestamps
    // (or "now" while the companion app holds the stick awake).
    if (power::check_idle(now, g_app.current(),
                          idle_activity_ms,
                          idle_stroke_ms)) {
        // Same as the BtnPWR path: don't drop unflushed session ticks. The
        // State::SLEEP path below deliberately does NOT flush — that session
        // was already closed and cleared by SUMMARY.
        g_app.flush_session_for_sleep();
        power::enter_deep_sleep();
    }
    {
        // Power-test overrides (DIAG): no idle dimming while a stay-awake hold
        // runs, and the test may drive the brightness itself.
        const bool    hold  = companion::hold_awake(now);
        const uint8_t force = companion::brightness_override(now);
        power::update_backlight(now, g_app.current(),
                                hold ? now : g_app.last_activity_ms(),
                                hold ? now : g_app.last_stroke_ms(),
                                force ? force : g_app.brightness_pct());
    }

    // If state entered SLEEP explicitly (e.g., B in SUMMARY), trigger sleep now.
    if (g_app.current() == State::SLEEP) {
        power::enter_deep_sleep();
    }
}
#endif
