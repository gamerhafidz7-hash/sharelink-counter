package com.hafidz.jamcounter

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Pengaturan: bubble (ukuran, kecepatan tahan, tempel tepi), getaran, dan pembaruan aplikasi. */
class SettingsActivity : Activity() {

    private lateinit var col: LinearLayout
    private val sizeNames = arrayOf("Kecil", "Sedang", "Besar")
    private val holdNames = arrayOf("Cepat (0,3 detik)", "Normal (0,45 detik)", "Lambat (0,7 detik)")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Pengaturan"
        actionBar?.setBackgroundDrawable(ColorDrawable(Color.parseColor("#FFC107")))
        actionBar?.setDisplayHomeAsUpEnabled(true)
        col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(24))
        }
        val scroll = ScrollView(this)
        scroll.addView(col)
        setContentView(scroll)
        build()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) finish()
        return true
    }

    private fun put(key: String, v: Int) = Cfg.prefs(this).edit().putInt(key, v).apply()
    private fun put(key: String, v: Boolean) = Cfg.prefs(this).edit().putBoolean(key, v).apply()

    /** Beri tahu bubble yang sedang aktif agar memuat ulang ukuran/pengaturan. */
    private fun reloadBubble() {
        if (BubbleService.running) {
            startService(Intent(this, BubbleService::class.java).setAction(BubbleService.ACTION_RELOAD))
        }
    }

    private fun build() {
        col.removeAllViews()
        header("Bubble")
        row("Ukuran bubble", sizeNames[Cfg.bubbleIndex(this)]) {
            choose("Ukuran bubble", sizeNames, Cfg.bubbleIndex(this)) {
                put("bubble_size", it)
                reloadBubble()
            }
        }
        row("Kecepatan tahan (tombol minus/reset)", holdNames[Cfg.holdIndex(this)]) {
            choose("Kecepatan tahan", holdNames, Cfg.holdIndex(this)) {
                put("hold", it)
            }
        }
        switchRow("Tempel ke tepi layar", "Bubble menempel ke sisi terdekat setelah digeser", Cfg.snap(this)) {
            put("snap", it)
        }

        header("Umum")
        switchRow("Getaran", "Getar saat tambah, kurangi, reset, dan geser", Cfg.vibrate(this)) {
            put("vibrate", it)
            if (it) Haptics.play(this, Haptics.CLICK)
        }

        header("Pembaruan aplikasi")
        row("Repo GitHub", Cfg.repo(this).ifBlank { "Belum diisi (contoh: namakamu/FloatingCounter)" }) { editRepo() }
        switchRow("Cek otomatis", "Saat aplikasi dibuka (maks. 6 jam sekali)", Cfg.autoUpdate(this)) {
            put("auto_update", it)
        }
        row("Cek pembaruan sekarang", "Terpasang: ${Updater.currentName(this)} (kode ${Updater.currentCode(this)})") {
            checkNow()
        }
    }

    private fun header(text: String) {
        col.addView(
            TextView(this).apply {
                this.text = text
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.parseColor("#F57C00"))
                setPadding(dp(16), dp(18), dp(16), dp(4))
            }
        )
    }

    private fun row(title: String, subtitle: String, onClick: () -> Unit) {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            isClickable = true
            setBackgroundResource(android.R.drawable.list_selector_background)
            setOnClickListener { onClick() }
        }
        r.addView(TextView(this).apply {
            text = title
            textSize = 16f
            setTextColor(Color.BLACK)
        })
        r.addView(TextView(this).apply {
            text = subtitle
            textSize = 13f
            setTextColor(Color.parseColor("#757575"))
        })
        col.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun switchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this).apply {
            text = title
            textSize = 16f
            setTextColor(Color.BLACK)
        })
        texts.addView(TextView(this).apply {
            text = subtitle
            textSize = 13f
            setTextColor(Color.parseColor("#757575"))
        })
        r.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val sw = Switch(this)
        sw.isChecked = checked
        sw.setOnCheckedChangeListener { _, v -> onChange(v) }
        r.addView(sw)
        col.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun choose(title: String, names: Array<String>, current: Int, onPick: (Int) -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(names, current) { d, which ->
                onPick(which)
                d.dismiss()
                build()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun editRepo() {
        val input = EditText(this)
        input.hint = "pemilik/repo"
        input.setSingleLine()
        input.setText(Cfg.repo(this))
        val box = LinearLayout(this)
        box.setPadding(dp(20), dp(8), dp(20), 0)
        box.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        AlertDialog.Builder(this)
            .setTitle("Repo GitHub (publik)")
            .setMessage("Tempat rilis APK. Format: pemilik/repo")
            .setView(box)
            .setPositiveButton("Simpan") { _, _ ->
                val v = input.text.toString().trim()
                    .removePrefix("https://github.com/").removePrefix("github.com/").trim('/')
                Cfg.prefs(this).edit().putString("repo", v).putLong("last_check", 0L).apply()
                build()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun checkNow() {
        val repo = Cfg.repo(this)
        if (repo.isBlank()) {
            Toast.makeText(this, "Isi Repo GitHub dulu", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "Memeriksa pembaruan…", Toast.LENGTH_SHORT).show()
        Updater.check(this, repo) { rel, err ->
            when {
                err != null -> Toast.makeText(this, err, Toast.LENGTH_LONG).show()
                rel == null -> Toast.makeText(this, "Sudah versi terbaru", Toast.LENGTH_SHORT).show()
                else -> Updater.promptInstall(this, rel)
            }
        }
    }
}
