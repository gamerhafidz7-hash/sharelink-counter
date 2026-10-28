package com.hafidz.jamcounter

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.time.LocalDate

class MainActivity : Activity() {

    private lateinit var db: Db
    private lateinit var list: LinearLayout
    private lateinit var scroll: ScrollView
    private var firstRender = true
    private val cardViews = HashMap<Long, View>()
    private val lastValues = HashMap<Long, Int>()
    private val knownIds = HashSet<Long>()

    private val listener: () -> Unit = { runOnUiThread { render() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = Db.get(this)
        actionBar?.setBackgroundDrawable(ColorDrawable(Color.parseColor("#FFC107")))

        val root = FrameLayout(this)
        scroll = ScrollView(this)
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(96))
        }
        scroll.addView(list)
        root.addView(
            scroll,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        val fab = TextView(this).apply {
            text = "+"
            textSize = 30f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = Palette.oval(Color.parseColor("#00BCD4"))
            elevation = dp(6).toFloat()
            setOnClickListener { startActivity(Intent(this@MainActivity, CounterFormActivity::class.java)) }
        }
        root.addView(
            fab,
            FrameLayout.LayoutParams(dp(56), dp(56), Gravity.BOTTOM or Gravity.END).apply {
                setMargins(0, 0, dp(20), dp(24))
            }
        )
        setContentView(root)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        Updater.autoCheck(this)
    }

    override fun onResume() {
        super.onResume()
        db.listeners.add(listener)
        render()
    }

    override fun onPause() {
        super.onPause()
        db.listeners.remove(listener)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "Statistik")
        menu.add(0, 2, 1, "Tutup bubble")
        menu.add(0, 3, 2, "Izin tampil di atas aplikasi lain")
        menu.add(0, 4, 3, "Pengaturan")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            1 -> startActivity(Intent(this, StatsActivity::class.java))
            2 -> startService(
                Intent(this, BubbleService::class.java).setAction(BubbleService.ACTION_HIDE)
            )
            3 -> openOverlaySettings()
            4 -> startActivity(Intent(this, SettingsActivity::class.java))
        }
        return true
    }

    private fun render() {
        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        val bubbleId = if (BubbleService.running) prefs.getLong("bubble_id", -1L) else -1L
        list.removeAllViews()
        cardViews.clear()
        var idx = 0
        for (c in db.counters()) {
            val changed = lastValues[c.id]?.let { it != c.value } ?: false
            val v = card(c, c.id == bubbleId, changed)
            cardViews[c.id] = v
            list.addView(v)
            if (knownIds.add(c.id)) { // kartu baru: muncul dengan geser + pudar
                v.alpha = 0f
                v.translationY = dp(14).toFloat()
                v.animate().alpha(1f).translationY(0f).setDuration(240)
                    .setStartDelay(minOf(idx * 35L, 300L)).start()
            }
            lastValues[c.id] = c.value
            idx++
        }
        if (firstRender) {
            firstRender = false
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
        val target = CounterFormActivity.lastSavedId
        if (target >= 0) {
            CounterFormActivity.lastSavedId = -1L
            cardViews[target]?.let { v -> scroll.post { scroll.smoothScrollTo(0, v.top) } }
        }
    }

    private fun cardTitle(c: Counter): String {
        val base = Fmt.cardDate(LocalDate.ofEpochDay(c.day))
        return if (c.note.isBlank()) base else "$base · ${c.note}"
    }

    /** Angka "membal" saat nilainya berubah. */
    private fun pop(v: View) {
        v.scaleX = 0.8f
        v.scaleY = 0.8f
        v.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(OvershootInterpolator(3f)).start()
    }

    private fun card(c: Counter, bubbleOn: Boolean, animateValue: Boolean): View {
        val fg = Palette.onColor(c.color)
        val dim = if (c.readOnly) 0.3f else 1f
        val card = RelativeLayout(this)
        card.background = Palette.gradient(c.color, dp(20).toFloat())
        card.elevation = dp(3).toFloat()
        card.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(124)
        ).apply { setMargins(dp(4), dp(5), dp(4), dp(5)) }
        card.setOnLongClickListener {
            cardMenu(c)
            true
        }

        val name = TextView(this).apply {
            text = cardTitle(c)
            textSize = 16f
            setTextColor(fg)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        card.addView(
            name,
            RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                addRule(RelativeLayout.ALIGN_PARENT_TOP)
                setMargins(dp(76), dp(12), dp(76), 0)
            }
        )

        val value = TextView(this).apply {
            text = c.value.toString()
            textSize = 42f
            setTextColor(fg)
            gravity = Gravity.CENTER
        }
        if (animateValue) pop(value)
        card.addView(
            value,
            RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { addRule(RelativeLayout.CENTER_IN_PARENT) }
        )

        // kiri atas: kurangi (menghapus catatan jam terbaru)
        card.addView(
            iconButton(R.drawable.ic_minus, fg, 10) {
                if (c.readOnly) readOnlyToast() else if (db.change(c.id, -1)) Haptics.play(this, Haptics.TICK)
            }.apply { alpha = dim },
            RelativeLayout.LayoutParams(dp(52), dp(52)).apply {
                addRule(RelativeLayout.ALIGN_PARENT_TOP)
                addRule(RelativeLayout.ALIGN_PARENT_START)
                setMargins(dp(6), dp(6), 0, 0)
            }
        )
        // kanan atas: tambah
        card.addView(
            iconButton(R.drawable.ic_plus, fg, 12) {
                if (c.readOnly) readOnlyToast() else if (db.change(c.id, 1)) Haptics.play(this, Haptics.TICK)
            }.apply { alpha = dim },
            RelativeLayout.LayoutParams(dp(72), dp(60)).apply {
                addRule(RelativeLayout.ALIGN_PARENT_TOP)
                addRule(RelativeLayout.ALIGN_PARENT_END)
                setMargins(0, dp(4), dp(6), 0)
            }
        )
        // kiri bawah: reset tanggal ini (dengan konfirmasi)
        card.addView(
            iconButton(R.drawable.ic_undo, fg, 12) {
                if (c.readOnly) readOnlyToast() else confirmReset(c)
            }.apply { alpha = dim },
            RelativeLayout.LayoutParams(dp(52), dp(52)).apply {
                addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
                addRule(RelativeLayout.ALIGN_PARENT_START)
                setMargins(dp(6), 0, 0, dp(6))
            }
        )
        // kanan bawah: bubble melayang
        val bubbleBg: Drawable? = if (bubbleOn) Palette.oval(0x33000000) else null
        card.addView(
            iconButton(R.drawable.ic_bubble, fg, 12, bubbleBg) { toggleBubble(c) },
            RelativeLayout.LayoutParams(dp(52), dp(52)).apply {
                addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
                addRule(RelativeLayout.ALIGN_PARENT_END)
                setMargins(0, 0, dp(6), dp(6))
            }
        )
        // sebelah bubble: statistik (langsung ke tanggal kartu ini)
        card.addView(
            iconButton(R.drawable.ic_chart, fg, 12) {
                startActivity(
                    Intent(this, StatsActivity::class.java).putExtra(StatsActivity.EXTRA_DAY, c.day)
                )
            },
            RelativeLayout.LayoutParams(dp(52), dp(52)).apply {
                addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
                addRule(RelativeLayout.ALIGN_PARENT_END)
                setMargins(0, 0, dp(62), dp(6))
            }
        )
        return card
    }

    private fun iconButton(
        res: Int,
        tint: Int,
        paddingDp: Int,
        content: Drawable? = null,
        onClick: () -> Unit
    ): ImageView {
        return ImageView(this).apply {
            setImageResource(res)
            imageTintList = ColorStateList.valueOf(tint)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(paddingDp), dp(paddingDp), dp(paddingDp), dp(paddingDp))
            background = RippleDrawable(
                ColorStateList.valueOf(0x44000000), content, ColorDrawable(Color.WHITE)
            )
            setOnClickListener { onClick() }
        }
    }

    private fun readOnlyToast() {
        Haptics.play(this, Haptics.DOUBLE)
        Toast.makeText(this, "Counter ini Read Only", Toast.LENGTH_SHORT).show()
    }

    private fun confirmReset(c: Counter) {
        AlertDialog.Builder(this)
            .setTitle(resetTitle(c))
            .setMessage(resetMessage(c, db.eventCount(c.id)))
            .setPositiveButton("Reset") { _, _ ->
                if (db.reset(c.id)) Haptics.play(this, Haptics.HEAVY)
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun toggleBubble(c: Counter) {
        Haptics.play(this, Haptics.CLICK)
        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        val activeHere = BubbleService.running && prefs.getLong("bubble_id", -1L) == c.id
        if (activeHere) {
            startService(Intent(this, BubbleService::class.java).setAction(BubbleService.ACTION_HIDE))
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(
                this,
                "Aktifkan izin \"Tampil di atas aplikasi lain\", lalu ketuk tombol bubble lagi",
                Toast.LENGTH_LONG
            ).show()
            openOverlaySettings()
            return
        }
        startForegroundService(
            Intent(this, BubbleService::class.java)
                .setAction(BubbleService.ACTION_SHOW)
                .putExtra(BubbleService.EXTRA_ID, c.id)
        )
    }

    private fun openOverlaySettings() {
        startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        )
    }

    private fun cardMenu(c: Counter) {
        AlertDialog.Builder(this)
            .setTitle(cardTitle(c))
            .setItems(arrayOf("Ubah", "Hapus")) { _, which ->
                when (which) {
                    0 -> startActivity(
                        Intent(this, CounterFormActivity::class.java)
                            .putExtra(CounterFormActivity.EXTRA_ID, c.id)
                    )
                    1 -> AlertDialog.Builder(this)
                        .setTitle("Hapus ${Fmt.cardDate(LocalDate.ofEpochDay(c.day))}?")
                        .setMessage("Nilai dan riwayat jamnya ikut terhapus.")
                        .setPositiveButton("Hapus") { _, _ -> db.delete(c.id) }
                        .setNegativeButton("Batal", null)
                        .show()
                }
            }
            .show()
    }
}
