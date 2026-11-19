#include "companion.h"

#ifndef UNIT_TEST
#include <Arduino.h>
#include <NimBLEDevice.h>
#include <M5Unified.h>
#include <esp_ota_ops.h>
#include <esp_system.h>
#include <esp_sleep.h>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <string>

#include "app.h"
#include "board.h"
#include "version.h"
#include "ui.h"

#if defined(SG_BOARD_PLUS)
  #define SG_BOARD_NAME "PLUS"
#elif defined(SG_BOARD_PLUS2)
  #define SG_BOARD_NAME "PLUS2"
#else
  #define SG_BOARD_NAME "S3"
#endif

// Embedded in every image so an OTA sender (the app, and this file's own
// receiver) can tell which stick an image was built for. Plus and Plus2 are
// both plain ESP32s, so the image header's chip id cannot tell them apart.
extern "C" __attribute__((used)) const char kSgBoardTag[] = "SG-BOARD:" SG_BOARD_NAME ";";
// Same idea for the version: the app shows this for a file before uploading
// it. (The ESP-IDF app descriptor's version field is no use here; Arduino
// builds fill it with the IDF version.)
extern "C" __attribute__((used)) const char kSgFwTag[] = "SG-FW:" SG_FW_VERSION ";";

namespace {

constexpr const char* SVC_UUID      = "5347a000-0b1e-4a6c-9d2e-6b6e69666521";
constexpr const char* LIVE_UUID     = "5347a001-0b1e-4a6c-9d2e-6b6e69666521";
constexpr const char* SETTINGS_UUID = "5347a002-0b1e-4a6c-9d2e-6b6e69666521";
constexpr const char* INFO_UUID     = "5347a003-0b1e-4a6c-9d2e-6b6e69666521";
constexpr const char* POWERLOG_UUID = "5347a004-0b1e-4a6c-9d2e-6b6e69666521";
constexpr const char* OTA_CTRL_UUID = "5347a010-0b1e-4a6c-9d2e-6b6e69666521";
constexpr const char* OTA_DATA_UUID = "5347a011-0b1e-4a6c-9d2e-6b6e69666521";

constexpr uint8_t PROTO_VERSION = 1;

enum OtaStatus : uint8_t { OK = 0, BAD_STATE = 1, FLASH_ERR = 2, MISMATCH = 3, WRONG_BOARD = 4,
                            LOW_BATTERY = 5, DECLINED = 6 };
enum OtaCmd    : uint8_t { BEGIN = 0x01, END = 0x03, ABORT = 0x05, RESTART = 0x07, DIAG = 0x09 };

NimBLEServer*         s_server   = nullptr;
NimBLECharacteristic* s_live     = nullptr;
NimBLECharacteristic* s_settings = nullptr;
NimBLECharacteristic* s_ota_ctrl = nullptr;

volatile int  s_conn_count = 0;            // phone and PC can both be connected
// Latest battery view from the main loop, for the OTA gate (BLE task reads it).
volatile int8_t s_batt_bars     = -1;       // 0..4, -1 unknown
volatile bool   s_batt_powered  = false;   // charging or on USB power
volatile uint16_t s_ota_conn = 0xFFFF;      // connection that owns the OTA transfer
// Newest connection, and when to ask it for a faster link (0 = nothing pending).
volatile uint16_t s_new_conn       = 0xFFFF;
volatile uint32_t s_fast_params_at = 0;
// ---- power log ------------------------------------------------------------
// The M5PM1 reports voltages only (no current), so battery drain is judged
// from how fast VBAT falls. One sample every POWERLOG_PERIOD_S, newest last,
// readable in one go over BLE (4 + 4*120 bytes fits a 512-byte attribute).
constexpr uint32_t POWERLOG_PERIOD_S = 10;
constexpr int      POWERLOG_N        = 120;       // 20 minutes
struct PowerSample { uint16_t mv; uint8_t backlight; uint8_t flags; };
PowerSample s_plog[POWERLOG_N];
int         s_plog_count   = 0;
uint32_t    s_plog_next_ms = 0;
NimBLECharacteristic* s_powerlog = nullptr;
// Diagnostic overrides set by the DIAG command.
volatile uint32_t s_hold_awake_until = 0;         // millis(), 0 = none
volatile bool     s_full_rate        = false;     // LIVE every tick even if unchanged
volatile uint8_t  s_cycle_s          = 0;         // backlight 100/10 flip period, 0 = off
volatile uint32_t s_cycle_t0         = 0;

// Last LIVE packet sent and when, for send-on-change.
uint8_t  s_last_live[16];
uint32_t s_last_live_ms = 0;
bool     s_live_valid   = false;
// A reading that has not changed is re-sent this often so the app can tell
// "steady" from "link lost"; a changed one goes out the same tick.
constexpr uint32_t LIVE_HEARTBEAT_MS = 200;

// ---- settings mailbox (BLE task -> main loop) ------------------------------
portMUX_TYPE  s_mux = portMUX_INITIALIZER_UNLOCKED;
uint8_t       s_pending[8];
volatile bool s_pending_valid = false;
uint32_t      s_sent_rev      = UINT32_MAX;   // settings_rev last notified
bool          s_was_connected = false;

// ---- OTA (runs entirely in the BLE task) -----------------------------------
struct Ota {
    volatile bool    active   = false;
    esp_ota_handle_t handle   = 0;
    const esp_partition_t* part = nullptr;
    uint32_t         size     = 0;
    volatile uint32_t recv    = 0;
    uint32_t         crc      = 0xFFFFFFFFu;
    size_t           tag_pos  = 0;
    bool             tag_seen = false;
    volatile uint32_t last_ms = 0;
};
Ota s_ota;
volatile uint32_t s_reboot_at = 0;   // millis() to restart at, 0 = none

// ---- update confirmation ----------------------------------------------------
// The link has no pairing, so anything in radio range can talk to it. Changing
// a setting is harmless; replacing the firmware is not. An update therefore
// only starts after someone presses A on the stick itself. BEGIN parks the
// request here (BLE task); the main loop shows the prompt, collects the answer
// and performs the erase.
constexpr uint32_t OTA_CONFIRM_TIMEOUT_MS = 20000;
volatile bool     s_confirm_pending  = false;
volatile int8_t   s_confirm_answer   = 0;        // 0 none yet, 1 yes, -1 no
volatile uint32_t s_confirm_size     = 0;
volatile uint32_t s_confirm_deadline = 0;
// True while END is validating the image (esp_ota_end hashes the whole slot,
// ~1 s). ota_active() must stay true through it: if the main loop saw neither
// `active` nor a pending reboot it would report UPDATE FAILED and restart
// mid-validation.
volatile bool s_finishing = false;
// How to restart: 0 = esp_restart(), 1 = 200 ms deep-sleep timer wake,
// 2 = power off / sleep exactly as the power button does.
// Diagnostic for the StickS3, whose PMIC sometimes stays off after a reset.
volatile uint8_t s_restart_mode = 0;

uint32_t crc32_update(uint32_t crc, const uint8_t* p, size_t n) {
    // Bitwise IEEE CRC-32 (zlib / java.util.zip.CRC32). Init 0xFFFFFFFF,
    // final xor applied by the caller. Slow but tiny; ~1.3 MB per update.
    while (n--) {
        crc ^= *p++;
        for (int k = 0; k < 8; k++) crc = (crc >> 1) ^ (0xEDB88320u & (0u - (crc & 1u)));
    }
    return crc;
}

// Rolling search for this board's own tag across chunk boundaries.
void tag_scan(const uint8_t* p, size_t n) {
    const size_t L = sizeof(kSgBoardTag) - 1;
    for (size_t i = 0; i < n && !s_ota.tag_seen; i++) {
        const char c = (char)p[i];
        if (c == kSgBoardTag[s_ota.tag_pos]) {
            if (++s_ota.tag_pos == L) s_ota.tag_seen = true;
        } else {
            s_ota.tag_pos = (c == kSgBoardTag[0]) ? 1 : 0;
        }
    }
}

void ota_reply(uint8_t cmd, uint8_t status) {
    uint8_t r[2] = {cmd, status};
    s_ota_ctrl->setValue(r, 2);
    s_ota_ctrl->notify();
}

void ota_abort() {
    if (s_ota.active) esp_ota_abort(s_ota.handle);
    s_ota.active = false;
}

uint32_t rd_u32(const uint8_t* p) {
    return (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
}

class ServerCb : public NimBLEServerCallbacks {
    void onConnect(NimBLEServer*, ble_gap_conn_desc* desc) override {
        ++s_conn_count;
        s_live_valid = false;                 // newcomer gets a packet right away
        if (desc) {
            // Ask for the fastest link once the central has finished its own
            // setup (MTU, discovery, its own priority request).
            s_new_conn       = desc->conn_handle;
            s_fast_params_at = millis() + 3000;
        }
        // Keep advertising so a second central (phone + PC) can join.
        if (s_conn_count < CONFIG_BT_NIMBLE_MAX_CONNECTIONS) NimBLEDevice::startAdvertising();
    }
    void onDisconnect(NimBLEServer*, ble_gap_conn_desc* desc) override {
        if (s_conn_count > 0) --s_conn_count;
        // A half-written image is useless; free the slot for the next try —
        // but only if it was the uploader that left.
        if (s_ota.active && desc && desc->conn_handle == s_ota_conn) ota_abort();
        if (s_confirm_pending && desc && desc->conn_handle == s_ota_conn) s_confirm_pending = false;
        NimBLEDevice::startAdvertising();
    }
};

class SettingsCb : public NimBLECharacteristicCallbacks {
    void onWrite(NimBLECharacteristic* c) override {
        NimBLEAttValue v = c->getValue();
        if (v.length() < 5 || v.data()[0] != PROTO_VERSION) return;
        portENTER_CRITICAL(&s_mux);
        std::memset(s_pending, 0, sizeof s_pending);
        std::memcpy(s_pending, v.data(), v.length() < 8 ? v.length() : 8);
        s_pending_valid = true;
        portEXIT_CRITICAL(&s_mux);
    }
};

class OtaCtrlCb : public NimBLECharacteristicCallbacks {
    void onWrite(NimBLECharacteristic* c, ble_gap_conn_desc* desc) override {
        NimBLEAttValue v = c->getValue();
        if (v.length() < 1) return;
        const uint8_t* d = v.data();
        switch (d[0]) {
            case BEGIN: {
                ota_abort();
                if (v.length() < 5) { ota_reply(BEGIN, BAD_STATE); return; }
                // An update is ~1 min of radio plus flash erase/write. On an
                // empty cell that browns the stick out mid-transfer (seen: 0
                // bars, on battery, died right after an update). The old image
                // would survive, but refuse up front and say why. Unknown
                // level (-1) is allowed: no reading is not "empty". On USB
                // power the cell does not matter.
                if (s_batt_bars == 0 && !s_batt_powered) { ota_reply(BEGIN, LOW_BATTERY); return; }
                const uint32_t size = rd_u32(d + 1);
                const esp_partition_t* part = esp_ota_get_next_update_partition(nullptr);
                if (!part || size == 0 || size > part->size) { ota_reply(BEGIN, MISMATCH); return; }
                if (s_confirm_pending) { ota_reply(BEGIN, BAD_STATE); return; }
                // Park the request; the reply comes from the main loop once the
                // user has pressed A (or B / nothing) on the stick.
                s_ota_conn         = desc ? desc->conn_handle : 0xFFFF;
                s_confirm_size     = size;
                s_confirm_answer   = 0;
                s_confirm_deadline = millis() + OTA_CONFIRM_TIMEOUT_MS;
                s_confirm_pending  = true;
                return;
            }
            case END: {
                if (!s_ota.active || v.length() < 5) { ota_reply(END, BAD_STATE); return; }
                const uint32_t want = rd_u32(d + 1);
                const uint32_t got  = s_ota.crc ^ 0xFFFFFFFFu;
                if (s_ota.recv != s_ota.size || got != want) {
                    ota_abort(); ota_reply(END, MISMATCH); return;
                }
                if (!s_ota.tag_seen) { ota_abort(); ota_reply(END, WRONG_BOARD); return; }
                s_finishing  = true;             // set BEFORE clearing active
                s_ota.active = false;
                // esp_ota_end validates the whole image (header, segments, hash).
                const bool ok = esp_ota_end(s_ota.handle) == ESP_OK
                             && esp_ota_set_boot_partition(s_ota.part) == ESP_OK;
                if (ok) s_reboot_at = millis() + 1000;   // let the reply reach the phone
                s_finishing = false;
                ota_reply(END, ok ? OK : FLASH_ERR);
                return;
            }
            case ABORT:
                s_confirm_pending = false;
                ota_abort();
                ota_reply(ABORT, OK);
                return;
#ifdef SG_DIAG
            // Developer builds only: these let anyone in radio range restart,
            // power off or override the stick, so release firmware omits them.
            case DIAG: {
                // [09, stay-awake minutes (0 = cancel), full-rate LIVE 0/1,
                //  backlight flip period in seconds (0 = off)]
                const uint8_t mins = (v.length() >= 2) ? d[1] : 0;
                s_hold_awake_until = mins ? (millis() + (uint32_t)mins * 60000u) : 0;
                s_full_rate        = (v.length() >= 3) && d[2];
                s_cycle_s          = (v.length() >= 4) ? d[3] : 0;
                s_cycle_t0         = millis();
                ota_reply(DIAG, OK);
                return;
            }
            case RESTART:
                // Diagnostic: restart now via the chosen path (see s_restart_mode).
                if (s_ota.active) { ota_reply(RESTART, BAD_STATE); return; }
                s_restart_mode = (v.length() >= 2) ? d[1] : 0;
                ota_reply(RESTART, OK);
                s_reboot_at = millis() + 500;
                return;
#endif
            default:
                ota_reply(d[0], BAD_STATE);
                return;
        }
    }
};

class OtaDataCb : public NimBLECharacteristicCallbacks {
    void onWrite(NimBLECharacteristic* c) override {
        if (!s_ota.active) return;
        NimBLEAttValue v = c->getValue();
        const uint8_t* d = v.data();
        const size_t   n = v.length();
        if (s_ota.recv == 0) {
            // Image header: magic 0xE9, chip id (u16) at offset 12.
            const uint16_t chip = (n >= 14) ? (uint16_t)(d[12] | (d[13] << 8)) : 0xFFFF;
            if (n < 14 || d[0] != 0xE9 || chip != CONFIG_IDF_FIRMWARE_CHIP_ID) {
                ota_abort(); ota_reply(END, WRONG_BOARD); return;
            }
        }
        if (s_ota.recv + n > s_ota.size) { ota_abort(); ota_reply(END, MISMATCH); return; }
        if (esp_ota_write(s_ota.handle, d, n) != ESP_OK) {
            ota_abort(); ota_reply(END, FLASH_ERR); return;
        }
        s_ota.crc = crc32_update(s_ota.crc, d, n);
        tag_scan(d, n);
        s_ota.recv += n;
        s_ota.last_ms = millis();
    }
};

void put_i16(uint8_t* p, int16_t v)  { p[0] = (uint8_t)v; p[1] = (uint8_t)((uint16_t)v >> 8); }
void put_u16(uint8_t* p, uint16_t v) { p[0] = (uint8_t)v; p[1] = (uint8_t)(v >> 8); }

uint8_t flags_of(const App::RemoteSettings& s) {
    return (s.buzzer ? 1 : 0) | (s.steady ? 2 : 0) | (s.tenths ? 4 : 0);
}

void fill_settings(uint8_t out[8], const App::RemoteSettings& s) {
    std::memset(out, 0, 8);
    out[0] = PROTO_VERSION;
    out[1] = (uint8_t)s.tol;
    put_u16(out + 2, (uint16_t)std::lround(s.target_deg * 10.0f));
    out[4] = flags_of(s);
    out[5] = s.brightness;
}

} // namespace

namespace companion {

void begin() {
    uint8_t mac[6] = {0};
    esp_efuse_mac_get_default(mac);
    char name[20];
    std::snprintf(name, sizeof name, "SharpGuide-%02X%02X", mac[4], mac[5]);

    NimBLEDevice::init(name);
    NimBLEDevice::setMTU(517);
    s_server = NimBLEDevice::createServer();
    s_server->setCallbacks(new ServerCb());

    NimBLEService* svc = s_server->createService(SVC_UUID);
    s_live = svc->createCharacteristic(LIVE_UUID, NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::NOTIFY);
    s_settings = svc->createCharacteristic(SETTINGS_UUID,
        NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::NOTIFY);
    s_settings->setCallbacks(new SettingsCb());
    s_powerlog = svc->createCharacteristic(POWERLOG_UUID, NIMBLE_PROPERTY::READ, 512);
    NimBLECharacteristic* info = svc->createCharacteristic(INFO_UUID, NIMBLE_PROPERTY::READ);
    // std::string, not the literal: the const char[N] overload sends the NUL too.
    // Referenced so the linker's section GC keeps the tag in the image
    // (the `used` attribute alone does not survive --gc-sections here).
    static const char* volatile s_keep_fw_tag = kSgFwTag;
    (void)s_keep_fw_tag;
    const esp_partition_t* running = esp_ota_get_running_partition();
    info->setValue(std::string("fw=" SG_FW_VERSION ";board=" SG_BOARD_NAME ";proto=1;slot=")
                   + (running ? running->label : "?"));
    s_ota_ctrl = svc->createCharacteristic(OTA_CTRL_UUID, NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::NOTIFY);
    s_ota_ctrl->setCallbacks(new OtaCtrlCb());
    NimBLECharacteristic* data = svc->createCharacteristic(OTA_DATA_UUID, NIMBLE_PROPERTY::WRITE, 512);
    data->setCallbacks(new OtaDataCb());
    svc->start();

    // 128-bit service UUID in the advert (for filtered scans), name in the
    // scan response — both together exceed the 31-byte advert.
    NimBLEAdvertising* adv = NimBLEDevice::getAdvertising();
    NimBLEAdvertisementData ad, sr;
    ad.setFlags(BLE_HS_ADV_F_DISC_GEN | BLE_HS_ADV_F_BREDR_UNSUP);
    ad.setCompleteServices(NimBLEUUID(SVC_UUID));
    sr.setName(name);
    adv->setAdvertisementData(ad);
    adv->setScanResponseData(sr);
    adv->setMinInterval(160);   // 100 ms
    adv->setMaxInterval(320);   // 200 ms
    adv->start();
}

void update(App& app, uint32_t now_ms, const power::BatteryView& batt) {
    // Power log: one sample every POWERLOG_PERIOD_S, connected or not.
    if ((int32_t)(now_ms - s_plog_next_ms) >= 0 && !s_ota.active) {
        s_plog_next_ms = now_ms + POWERLOG_PERIOD_S * 1000u;
        const int16_t mv = M5.Power.getBatteryVoltage();
        PowerSample ps;
        ps.mv        = (mv > 0) ? (uint16_t)mv : 0;
        ps.backlight = ui::backlight_percent();
        ps.flags     = (s_conn_count > 0 ? 1 : 0) | ((batt.usb_power || batt.charging) ? 2 : 0)
                     | (s_full_rate ? 4 : 0) | ((uint8_t)app.current() << 4);
        if (s_plog_count == POWERLOG_N) {
            std::memmove(s_plog, s_plog + 1, sizeof(PowerSample) * (POWERLOG_N - 1));
            --s_plog_count;
        }
        s_plog[s_plog_count++] = ps;
        uint8_t out[4 + 4 * POWERLOG_N];
        out[0] = PROTO_VERSION;
        out[1] = (uint8_t)s_plog_count;
        out[2] = (uint8_t)POWERLOG_PERIOD_S;
        out[3] = 0;
        for (int i = 0; i < s_plog_count; i++) {
            put_u16(out + 4 + 4 * i, s_plog[i].mv);
            out[4 + 4 * i + 2] = s_plog[i].backlight;
            out[4 + 4 * i + 3] = s_plog[i].flags;
        }
        s_powerlog->setValue(out, 4 + 4 * s_plog_count);
    }

    s_batt_bars     = (int8_t)batt.bars;
    s_batt_powered  = batt.charging || batt.usb_power;
    if (s_reboot_at && (int32_t)(millis() - s_reboot_at) >= 0) {
        // Never reset the chip mid-transfer to the LCD: a panel left halfway
        // through an SPI write can come back blank after a soft reset (seen on
        // a StickS3 after an OTA). Finish the draw, then blank the backlight.
        M5.Display.waitDisplay();
        M5.Display.setBrightness(0);
        delay(20);
        if (s_restart_mode == 1) {
            esp_sleep_enable_timer_wakeup(200 * 1000);
            esp_deep_sleep_start();
        }
        if (s_restart_mode == 2) {
            // Same path as the power button / idle timeout: sleep (Plus,
            // Plus2) or PMIC power-off (S3).
            app.flush_session_for_sleep();
            power::enter_deep_sleep();
        }
        power::restart();
    }
    // Pending update request: act on the user's answer, or give up.
    if (s_confirm_pending) {
        const int8_t ans = s_confirm_answer;
        if (ans == 1) {
            const uint32_t size = s_confirm_size;
            const esp_partition_t* part = esp_ota_get_next_update_partition(nullptr);
            // Erases just `size` bytes of the spare slot — a few seconds.
            if (!part || esp_ota_begin(part, size, &s_ota.handle) != ESP_OK) {
                s_confirm_pending = false;
                ota_reply(BEGIN, FLASH_ERR);
            } else {
                s_ota.part     = part;
                s_ota.size     = size;
                s_ota.recv     = 0;
                s_ota.crc      = 0xFFFFFFFFu;
                s_ota.tag_pos  = 0;
                s_ota.tag_seen = false;
                s_ota.last_ms  = millis();
                s_ota.active   = true;       // set before dropping `pending`: no gap for the main loop
                s_confirm_pending = false;
                ota_reply(BEGIN, OtaStatus::OK);
            }
        } else if (ans == -1 || (int32_t)(millis() - s_confirm_deadline) >= 0) {
            s_confirm_pending = false;
            ota_reply(BEGIN, DECLINED);
        }
    }

    // A sender that vanished mid-transfer without disconnecting.
    if (s_ota.active && millis() - s_ota.last_ms > 15000) {
        ota_abort();
    }

    // Remote settings -> App (main-loop thread only).
    if (s_pending_valid) {
        uint8_t b[8];
        portENTER_CRITICAL(&s_mux);
        std::memcpy(b, s_pending, 8);
        s_pending_valid = false;
        portEXIT_CRITICAL(&s_mux);
        App::RemoteSettings s = app.current_settings();
        if (b[1] <= (uint8_t)Tolerance::EASY) s.tol = (Tolerance)b[1];
        const uint16_t t10 = (uint16_t)(b[2] | (b[3] << 8));
        if (t10 != 0) s.target_deg = t10 / 10.0f;
        s.buzzer = b[4] & 1;
        s.steady = b[4] & 2;
        s.tenths = b[4] & 4;
        if (b[5] != 0) s.brightness = b[5];   // 0 = not specified (older app)
        app.apply_remote_settings(s, now_ms);
    }

    if (s_fast_params_at && (int32_t)(millis() - s_fast_params_at) >= 0) {
        s_fast_params_at = 0;
        // 7.5-11.25 ms interval, no peripheral latency, 2 s supervision. The
        // central may refuse (it keeps what it had); a phone that accepts
        // delivers each 20 ms reading with under ~10 ms of radio delay.
        if (s_conn_count > 0 && !s_ota.active) s_server->updateConnParams(s_new_conn, 6, 9, 0, 200);
    }

    const bool conn = s_conn_count > 0;
    if (!conn) { s_was_connected = false; return; }
    if (s_ota.active) return;   // keep the link free for image data

    // SETTINGS: keep the readable value current; notify on change or connect.
    if (!s_was_connected || app.settings_rev() != s_sent_rev) {
        uint8_t b[8];
        fill_settings(b, app.current_settings());
        s_settings->setValue(b, 8);
        s_settings->notify();
        s_sent_rev = app.settings_rev();
    }
    s_was_connected = true;

    // LIVE: every tick. A notify the controller can't queue is simply dropped,
    // which is the right thing for a live reading — the next one is 20 ms away.
    const App::RemoteSettings st = app.current_settings();
    uint8_t p[16];
    p[0] = PROTO_VERSION;
    p[1] = (uint8_t)app.current();
    float deg;
    if (app.live_angle(deg) && std::isfinite(deg) && std::fabs(deg) < 327.0f) {
        put_i16(p + 2, (int16_t)std::lround(deg * 100.0f));
    } else {
        put_i16(p + 2, INT16_MIN);
    }
    put_u16(p + 4, (uint16_t)std::lround(st.target_deg * 10.0f));
    p[6] = (uint8_t)st.tol;
    ColorState col;
    p[7] = app.live_color(col) ? (uint8_t)col : 0xFF;
    p[8] = (uint8_t)app.current_side();
    p[9] = flags_of(st) | ((batt.charging || batt.usb_power) ? 8 : 0);
    put_u16(p + 10, (uint16_t)(app.strokes_a() > 0xFFFF ? 0xFFFF : app.strokes_a()));
    put_u16(p + 12, (uint16_t)(app.strokes_b() > 0xFFFF ? 0xFFFF : app.strokes_b()));
    p[14] = app.green_pct();
    p[15] = (uint8_t)(int8_t)batt.bars;
    // Send on change, plus a slow heartbeat. On the menu screens nothing
    // changes, so the radio drops from 50 packets a second to 5; while
    // coaching the angle moves every tick and every tick still goes out.
    if (!s_full_rate && s_live_valid && std::memcmp(p, s_last_live, sizeof p) == 0
        && (now_ms - s_last_live_ms) < LIVE_HEARTBEAT_MS) {
        return;
    }
    std::memcpy(s_last_live, p, sizeof p);
    s_last_live_ms = now_ms;
    s_live_valid   = true;
    s_live->setValue(p, sizeof p);
    s_live->notify();
}

bool connected()   { return s_conn_count > 0; }
bool hold_awake(uint32_t) {
    const uint32_t until = s_hold_awake_until;
    return until != 0 && (int32_t)(until - millis()) > 0;
}
uint8_t brightness_override(uint32_t now_ms) {
    const uint8_t period = s_cycle_s;
    if (period == 0 || !hold_awake(now_ms)) return 0;
    return (((millis() - s_cycle_t0) / (period * 1000u)) % 2u) == 0 ? 100 : 10;
}
bool ota_confirm_pending()        { return s_confirm_pending; }
void ota_confirm_answer(bool yes) { if (s_confirm_pending) s_confirm_answer = yes ? 1 : -1; }
bool ota_active()  { return s_ota.active || s_finishing || s_reboot_at != 0; }
int  ota_percent() {
    if (s_reboot_at) return 100;
    return s_ota.size ? (int)((uint64_t)s_ota.recv * 100u / s_ota.size) : 0;
}

} // namespace companion

#else
// Native unit tests: no radio.
namespace companion {
    void begin() {}
    void update(App&, uint32_t, const power::BatteryView&) {}
    bool connected()  { return false; }
    bool hold_awake(uint32_t) { return false; }
    uint8_t brightness_override(uint32_t) { return 0; }
    bool ota_confirm_pending() { return false; }
    void ota_confirm_answer(bool) {}
    bool ota_active() { return false; }
    int  ota_percent() { return 0; }
}
#endif
