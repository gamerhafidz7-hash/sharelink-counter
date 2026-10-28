package com.hafidz.jamcounter

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator

/** Pengaturan pengguna (SharedPreferences "settings"). */
object Cfg {
    /** Isi "pemilik/repo" GitHub di sini kalau mau repo bawaan; pengguna bisa mengubahnya di Pengaturan. */
    const val DEFAULT_REPO = "gamerhafidz7-hash/sharelink-counter"

    fun prefs(ctx: Context): SharedPreferences = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun bubbleIndex(ctx: Context): Int = prefs(ctx).getInt("bubble_size", 1).coerceIn(0, 2)
    fun bubbleDp(ctx: Context): Int = intArrayOf(46, 58, 72)[bubbleIndex(ctx)]

    fun holdIndex(ctx: Context): Int = prefs(ctx).getInt("hold", 1).coerceIn(0, 2)
    fun holdMs(ctx: Context): Long = longArrayOf(300L, 450L, 700L)[holdIndex(ctx)]

    fun vibrate(ctx: Context): Boolean = prefs(ctx).getBoolean("vibrate", true)
    fun snap(ctx: Context): Boolean = prefs(ctx).getBoolean("snap", true)
    fun autoUpdate(ctx: Context): Boolean = prefs(ctx).getBoolean("auto_update", true)
    fun repo(ctx: Context): String = prefs(ctx).getString("repo", null) ?: DEFAULT_REPO
}

/** Getaran: TICK (ringan), CLICK (sedang), HEAVY (kuat), DOUBLE (dua ketukan: peringatan/info). */
object Haptics {
    const val TICK = 0
    const val CLICK = 1
    const val HEAVY = 2
    const val DOUBLE = 3

    fun play(ctx: Context, kind: Int) {
        if (!Cfg.vibrate(ctx)) return
        val v = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (!v.hasVibrator()) return
        val api29 = Build.VERSION.SDK_INT >= 29
        val effect = when (kind) {
            TICK -> if (api29) VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
            else VibrationEffect.createOneShot(12, 90)
            CLICK -> if (api29) VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
            else VibrationEffect.createOneShot(25, 160)
            HEAVY -> if (api29) VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
            else VibrationEffect.createOneShot(55, 255)
            else -> VibrationEffect.createWaveform(longArrayOf(0, 30, 80, 30), -1)
        }
        v.vibrate(effect)
    }
}
