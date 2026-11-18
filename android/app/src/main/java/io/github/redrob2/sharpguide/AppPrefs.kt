package io.github.redrob2.sharpguide

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.redrob2.sharpguide.firmware.GitHub

/** How the Live screen shows decimals. */
enum class TenthsMode(val label: String) { FOLLOW_STICK("Auto"), WHOLE("0"), TENTHS("0.0") }

class AppPrefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("app", Context.MODE_PRIVATE)

    private var tenthsIdx by mutableIntStateOf(sp.getInt("tenths_mode", 0))
    var tenthsMode: TenthsMode
        get() = TenthsMode.entries.getOrElse(tenthsIdx) { TenthsMode.FOLLOW_STICK }
        set(v) { tenthsIdx = v.ordinal; sp.edit().putInt("tenths_mode", v.ordinal).apply() }

    private var _immersive by mutableStateOf(sp.getBoolean("immersive", false))
    var immersive: Boolean
        get() = _immersive
        set(v) { _immersive = v; sp.edit().putBoolean("immersive", v).apply() }

    private var _repo by mutableStateOf(sp.getString("repo", GitHub.DEFAULT_REPO) ?: GitHub.DEFAULT_REPO)
    var repo: String
        get() = _repo
        set(v) { _repo = v; sp.edit().putString("repo", v).apply() }
}
