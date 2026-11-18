#include "filter.h"
#include <cmath>

static inline float inv_sqrt(float x) {
    return 1.0f / std::sqrt(x);
}

void MahonyFilter::begin(float sample_hz, float kp, float ki) {
    q0_ = 1.0f; q1_ = q2_ = q3_ = 0.0f;
    ix_ = iy_ = iz_ = 0.0f;
    kp_ = (kp > 0.0f) ? kp : 0.5f;
    ki_ = ki;
    dt_ = 1.0f / sample_hz;
    a_lp_valid_ = false;   // drop the smoothed accel; a_tau_ (a config) survives
    motion_hold_ = 0;
}

void MahonyFilter::reset() {
    begin(1.0f / dt_, kp_, ki_);
}

void MahonyFilter::update(Vec3 gyro_dps, Vec3 accel_g) {
    const float DEG2RAD = (float)M_PI / 180.0f;
    float gx = (gyro_dps.x - bias_.x) * DEG2RAD;
    float gy = (gyro_dps.y - bias_.y) * DEG2RAD;
    float gz = (gyro_dps.z - bias_.z) * DEG2RAD;

    // Gravity reference. When smoothing is enabled this is a low-passed accel, so
    // the oscillatory (zero-mean) part of a sharpening stroke averages out before
    // it can steer the orientation. The gyro terms above are deliberately NOT
    // smoothed — they carry the fast response.
    Vec3 a_ref = accel_g;
    if (a_tau_ > 0.0f) {
        if (!a_lp_valid_) {
            a_lp_       = accel_g;      // seed on first sample: no start-up ramp
            a_lp_valid_ = true;
        } else {
            const float al = dt_ / (a_tau_ + dt_);
            a_lp_.x += al * (accel_g.x - a_lp_.x);
            a_lp_.y += al * (accel_g.y - a_lp_.y);
            a_lp_.z += al * (accel_g.z - a_lp_.z);
        }
        a_ref = a_lp_;

        // Motion gate. The raw accel departing from its own slow average means
        // the device is being pushed around (a stroke) or re-oriented; in both
        // cases the accelerometer is the wrong thing to steer by and the gyro
        // is the right one. Hold the verdict for MOTION_HOLD_TICKS so the gate
        // does not flicker open at every stroke turnaround.
        const float dx = accel_g.x - a_lp_.x, dy = accel_g.y - a_lp_.y, dz = accel_g.z - a_lp_.z;
        if (dx*dx + dy*dy + dz*dz > mahony::MOTION_DEV_G * mahony::MOTION_DEV_G) {
            motion_hold_ = mahony::MOTION_HOLD_TICKS;
        } else if (motion_hold_ > 0) {
            --motion_hold_;
        }
    } else {
        motion_hold_ = 0;   // raw mode (steady off): the pre-gate behaviour, for A/B
        // Smoothing off: keep the raw sample so accel_reference() honours its
        // contract (last raw accel) instead of reporting {0,0,0} to diagnostics.
        a_lp_       = accel_g;
        a_lp_valid_ = true;
    }

    float ax = a_ref.x, ay = a_ref.y, az = a_ref.z;

    float an = ax*ax + ay*ay + az*az;
    // Trust the accel as gravity only when its magnitude is near 1g; during a
    // stroke it carries linear acceleration and must not steer the estimate.
    if (an > 0.0f && std::fabs(std::sqrt(an) - 1.0f) < mahony::ACCEL_TRUST_TOL_G) {
        float inv = inv_sqrt(an);
        ax *= inv; ay *= inv; az *= inv;

        float vx = 2.0f * (q1_*q3_ - q0_*q2_);
        float vy = 2.0f * (q0_*q1_ + q2_*q3_);
        float vz = q0_*q0_ - q1_*q1_ - q2_*q2_ + q3_*q3_;

        float ex = -(ay*vz - az*vy);
        float ey = -(az*vx - ax*vz);
        float ez = -(ax*vy - ay*vx);

        const bool moving = motion_hold_ > 0;
        if (ki_ > 0.0f) {
            // Do not learn "bias" from a stroke: freeze the integral while moving.
            if (!moving) {
                ix_ += ki_ * ex * dt_;
                iy_ += ki_ * ey * dt_;
                iz_ += ki_ * ez * dt_;
            }
            gx += ix_;
            gy += iy_;
            gz += iz_;
        }

        const float kp = moving ? kp_ * mahony::MOTION_KP_SCALE : kp_;
        gx += kp * ex;
        gy += kp * ey;
        gz += kp * ez;
    }

    gx *= 0.5f * dt_;
    gy *= 0.5f * dt_;
    gz *= 0.5f * dt_;

    float qa = q0_, qb = q1_, qc = q2_;
    q0_ += -qb*gx - qc*gy - q3_*gz;
    q1_ +=  qa*gx + qc*gz - q3_*gy;
    q2_ +=  qa*gy - qb*gz + q3_*gx;
    q3_ +=  qa*gz + qb*gy - qc*gx;

    float inv = inv_sqrt(q0_*q0_ + q1_*q1_ + q2_*q2_ + q3_*q3_);
    q0_ *= inv; q1_ *= inv; q2_ *= inv; q3_ *= inv;
}

Vec3 MahonyFilter::gravity() const {
    float vx = 2.0f * (q1_*q3_ - q0_*q2_);
    float vy = 2.0f * (q0_*q1_ + q2_*q3_);
    float vz = q0_*q0_ - q1_*q1_ - q2_*q2_ + q3_*q3_;
    return {-vx, -vy, -vz};
}

void MahonyFilter::nudge_to_gravity(Vec3 accel_g) {
    float mag = std::sqrt(accel_g.x*accel_g.x + accel_g.y*accel_g.y + accel_g.z*accel_g.z);
    if (mag < 1e-3f) return;                 // degenerate sample — leave orientation untouched
    float tz = accel_g.z / mag;

    // Build the shortest-arc orientation whose gravity() equals the normalized
    // accel, choosing yaw = 0 (yaw is unobservable from gravity and unused
    // downstream). Derivation: gravity() returns {-vx,-vy,-vz}, so we aim the
    // rotation arc at u = the desired v-vector (before that sign flip), i.e.
    // u = (tx, ty, -tz). The shortest arc from +Z to u then yields gravity()
    // = (tx, ty, tz) exactly. Note u_z = -tz, hence denom = 1 + u_z = 1 - tz.
    float denom = 1.0f - tz;                 // == 1 + u_z; ->0 only when accel ≈ +Z
    if (denom < 1e-6f) {
        // Singular: target points straight up the body +Z axis (device inverted).
        // q = (0,1,0,0) gives gravity() = (0,0,1), the matching pose.
        q0_ = 0.0f; q1_ = 1.0f; q2_ = 0.0f; q3_ = 0.0f;
    } else {
        q0_ = denom;
        q1_ = -accel_g.y / mag;
        q2_ =  accel_g.x / mag;
        q3_ = 0.0f;
        float inv = inv_sqrt(q0_*q0_ + q1_*q1_ + q2_*q2_ + q3_*q3_);
        q0_ *= inv; q1_ *= inv; q2_ *= inv; q3_ *= inv;
    }
    ix_ = iy_ = iz_ = 0.0f;                   // re-anchor: drop accumulated integral term
    // Re-seed the smoothed accel too. Without this the low-pass still holds the
    // pre-snap history and would drag the freshly re-anchored orientation back.
    a_lp_       = accel_g;
    a_lp_valid_ = true;
    motion_hold_ = 0;
}

bool mahony::should_snap(Vec3 g_filter, Vec3 accel_g, Vec3 gyro_dps) {
    float gyro_mag = std::sqrt(gyro_dps.x*gyro_dps.x + gyro_dps.y*gyro_dps.y + gyro_dps.z*gyro_dps.z);
    if (gyro_mag >= SNAP_GYRO_DPS) return false;          // still moving — trust the filter

    float a_mag = std::sqrt(accel_g.x*accel_g.x + accel_g.y*accel_g.y + accel_g.z*accel_g.z);
    if (std::fabs(a_mag - 1.0f) >= SNAP_ACCEL_TOL_G) return false;  // not pure gravity

    float g_mag = std::sqrt(g_filter.x*g_filter.x + g_filter.y*g_filter.y + g_filter.z*g_filter.z);
    if (g_mag < 1e-3f) return false;          // degenerate filter state (a_mag already ≈ 1g here)

    float d = (g_filter.x*accel_g.x + g_filter.y*accel_g.y + g_filter.z*accel_g.z) / (g_mag * a_mag);
    if (d >  1.0f) d =  1.0f;
    if (d < -1.0f) d = -1.0f;
    float div_deg = std::acos(d) * (180.0f / (float)M_PI);
    return div_deg >= SNAP_DIVERGENCE_DEG;
}

bool mahony::StillGate::update(Vec3 a, Vec3 g) {
    const float gyro_mag = std::sqrt(g.x*g.x + g.y*g.y + g.z*g.z);
    const float a_mag    = std::sqrt(a.x*a.x + a.y*a.y + a.z*a.z);
    bool still = gyro_mag < SNAP_GYRO_DPS
              && std::fabs(a_mag - 1.0f) < SNAP_STILL_ACCEL_TOL;
    if (still && have_prev_) {
        const float dx = a.x - prev_.x, dy = a.y - prev_.y, dz = a.z - prev_.z;
        still = std::sqrt(dx*dx + dy*dy + dz*dz) < SNAP_STILL_ACCEL_DELTA;
    }
    prev_      = a;
    have_prev_ = true;
    if (!still) { ticks_ = 0; return false; }
    if (ticks_ < SNAP_STILL_TICKS) ++ticks_;
    return ticks_ >= SNAP_STILL_TICKS;
}
