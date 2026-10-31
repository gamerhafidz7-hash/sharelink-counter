package com.hafidz.jamcounter

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

object Palette {
    val BLACK: Int = Color.parseColor("#212121")
    val GREEN: Int = Color.parseColor("#1DA85A")
    val YELLOW: Int = Color.parseColor("#FFC107")
    val BORDER_DARK_GRAY: Int = Color.parseColor("#505050")

    /**
     * Tanggal kampanye utama (Peak Day):
     * - Tanggal kembar (1.1, 2.2, ..., 9.9, 10.10, 11.11, 12.12)
     * - Tanggal 25 setiap bulan (Payday sale)
     */
    fun isPeakDay(d: LocalDate): Boolean =
        d.dayOfMonth == d.monthValue || d.dayOfMonth == 25

    /**
     * Default warna bubble / counter berdasarkan tanggal:
     * - Tanggal kembar & tanggal 25 -> HIJAU
     * - H-1 dan H+1 dari tanggal kembar / 25 -> KUNING
     * - Hari lainnya (tiap hari reguler) -> HITAM
     */
    fun defaultColorFor(date: LocalDate): Int {
        if (isPeakDay(date)) return GREEN
        if (isPeakDay(date.plusDays(1)) || isPeakDay(date.minusDays(1))) return YELLOW
        return BLACK
    }

    val colors: IntArray = intArrayOf(
        BLACK,
        GREEN,
        YELLOW,
        Color.parseColor("#FF9800"),
        Color.parseColor("#FF5722"),
        Color.parseColor("#4CAF50"),
        Color.parseColor("#00BCD4"),
        Color.parseColor("#2196F3"),
        Color.parseColor("#9C27B0"),
        Color.parseColor("#E91E63")
    )

    /** Palet besar untuk pemilih warna: 12 rona x 4 tingkat + deretan netral/cokelat. */
    val all: IntArray = run {
        val out = ArrayList<Int>()
        val tones = arrayOf(
            floatArrayOf(0.85f, 0.95f), floatArrayOf(0.60f, 1.00f),
            floatArrayOf(0.90f, 0.65f), floatArrayOf(0.30f, 1.00f)
        )
        for (t in tones) {
            for (h in 0 until 12) out.add(Color.HSVToColor(floatArrayOf(h * 30f, t[0], t[1])))
        }
        for (hex in arrayOf(
            "#000000", "#424242", "#757575", "#BDBDBD", "#EEEEEE", "#FFFFFF",
            "#3E2723", "#5D4037", "#795548", "#A1887F", "#D7CCC8", "#FFE0B2"
        )) out.add(Color.parseColor(hex))
        out.toIntArray()
    }

    /** Terangkan warna sedikit (0..1) untuk efek gradien. */
    fun lighten(color: Int, amount: Float): Int {
        val r = Color.red(color) + ((255 - Color.red(color)) * amount).toInt()
        val g = Color.green(color) + ((255 - Color.green(color)) * amount).toInt()
        val b = Color.blue(color) + ((255 - Color.blue(color)) * amount).toInt()
        return Color.rgb(r, g, b)
    }

    /** Latar kartu: gradien halus dari atas (lebih terang) ke bawah. */
    fun gradient(color: Int, radiusPx: Float): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(lighten(color, 0.14f), color)).apply {
            cornerRadius = radiusPx
        }

    /** Warna teks/ikon yang kontras di atas warna latar [bg]. */
    fun onColor(bg: Int): Int {
        val lum = 0.299 * Color.red(bg) + 0.587 * Color.green(bg) + 0.114 * Color.blue(bg)
        return if (lum > 140) Color.BLACK else Color.WHITE
    }

    fun rounded(color: Int, radiusPx: Float): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusPx
        }

    fun oval(color: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }

    /** Kotak bergaris tepi (dipakai untuk kolom isian dan tombol filter aktif). */
    fun outline(fill: Int, stroke: Int, radiusPx: Float, strokePx: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            setStroke(strokePx, stroke)
            cornerRadius = radiusPx
        }
}

/** Pembantu tanggal/jam. Semua hari disimpan sebagai epoch-day (LocalDate.toEpochDay). */
object Days {
    private val zone: ZoneId get() = ZoneId.systemDefault()

    fun today(): LocalDate = LocalDate.now()

    /** Waktu (ms) untuk jam [hour]:00 di hari [day]. */
    fun millisAt(day: Long, hour: Int): Long =
        LocalDate.ofEpochDay(day).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    fun dayOf(ts: Long): Long =
        Instant.ofEpochMilli(ts).atZone(zone).toLocalDate().toEpochDay()

    fun hourOf(ts: Long): Int = Instant.ofEpochMilli(ts).atZone(zone).hour

    fun startMillis(d: LocalDate): Long = d.atStartOfDay(zone).toInstant().toEpochMilli()

    fun endMillis(d: LocalDate): Long = d.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
}

/** Format teks tanggal berbahasa Indonesia (tanpa bergantung pada Locale HP). */
object Fmt {
    val monthsFull = arrayOf(
        "Januari", "Februari", "Maret", "April", "Mei", "Juni",
        "Juli", "Agustus", "September", "Oktober", "November", "Desember"
    )
    val monthsShort = arrayOf(
        "Jan", "Feb", "Mar", "Apr", "Mei", "Jun", "Jul", "Agu", "Sep", "Okt", "Nov", "Des"
    )
    private val daysFull = arrayOf("Senin", "Selasa", "Rabu", "Kamis", "Jumat", "Sabtu", "Minggu")
    private val daysShort = arrayOf("Sen", "Sel", "Rab", "Kam", "Jum", "Sab", "Min")

    fun pad2(n: Int): String = if (n < 10) "0$n" else n.toString()

    /** 27-10-2026 */
    fun dmy(d: LocalDate): String = pad2(d.dayOfMonth) + "-" + pad2(d.monthValue) + "-" + d.year

    /** 27 Okt */
    fun dayMonth(d: LocalDate): String = "${d.dayOfMonth} ${monthsShort[d.monthValue - 1]}"

    /** Judul kartu: "27 Okt" (tahun hanya muncul kalau bukan tahun ini). */
    fun cardDate(d: LocalDate): String {
        val base = dayMonth(d)
        return if (d.year != LocalDate.now().year) "$base ${d.year}" else base
    }

    /** Sel, 27 Okt 2026 */
    fun dateLong(d: LocalDate): String =
        "${daysShort[d.dayOfWeek.value - 1]}, ${d.dayOfMonth} ${monthsShort[d.monthValue - 1]} ${d.year}"

    /** Selasa, 27 Oktober 2026 */
    fun dateFull(d: LocalDate): String =
        "${daysFull[d.dayOfWeek.value - 1]}, ${d.dayOfMonth} ${monthsFull[d.monthValue - 1]} ${d.year}"

    fun hour(h: Int): String = pad2(h) + ":00"

    /** 12,5 (koma desimal, tanpa ",0"). */
    fun decimal(v: Double): String {
        val r = Math.round(v * 10) / 10.0
        return if (r == r.toLong().toDouble()) r.toLong().toString() else r.toString().replace('.', ',')
    }
}

fun resetTitle(c: Counter): String = "Reset ${Fmt.cardDate(LocalDate.ofEpochDay(c.day))}?"

fun resetMessage(c: Counter, events: Int): String =
    "Nilai ${c.value} dan $events catatan jam di tanggal ini akan dihapus. " +
        "Nilai kembali ke ${c.startValue}. Tidak bisa dibatalkan."

/** Dialog pilih warna: palet besar (60 warna) + tombol warna kustom. */
fun Activity.showColorPicker(onPick: (Int) -> Unit) {
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(8), dp(12), dp(8))
    }
    var dialog: AlertDialog? = null
    Palette.all.toList().chunked(6).forEach { rowColors ->
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        rowColors.forEach { col ->
            val dot = View(this).apply {
                background = Palette.oval(col).apply { setStroke(dp(1), Color.parseColor("#33000000")) }
                setOnClickListener {
                    onPick(col)
                    dialog?.dismiss()
                }
            }
            row.addView(
                dot,
                LinearLayout.LayoutParams(dp(40), dp(40)).apply { setMargins(dp(5), dp(5), dp(5), dp(5)) }
            )
        }
        root.addView(row)
    }
    val custom = TextView(this).apply {
        text = "Warna kustom…"
        textSize = 15f
        gravity = Gravity.CENTER
        setTextColor(Color.BLACK)
        background = Palette.rounded(Color.parseColor("#EEEEEE"), dp(8).toFloat())
        setPadding(dp(12), dp(12), dp(12), dp(12))
        setOnClickListener {
            dialog?.dismiss()
            showCustomColor(onPick)
        }
    }
    root.addView(
        custom,
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) }
    )
    val scroll = ScrollView(this)
    scroll.addView(root)
    val d = AlertDialog.Builder(this)
        .setTitle("Pilih warna")
        .setView(scroll)
        .setNegativeButton("Batal", null)
        .create()
    dialog = d
    d.show()
}

/** Pemilih warna bebas: slider Rona, Kejenuhan, Terang + pratinjau. */
private fun Activity.showCustomColor(onPick: (Int) -> Unit) {
    val hsv = floatArrayOf(30f, 0.9f, 1f)
    val preview = View(this)
    val hex = TextView(this).apply {
        gravity = Gravity.CENTER
        textSize = 16f
        setTextColor(Color.BLACK)
    }
    fun refresh() {
        val c = Color.HSVToColor(hsv)
        preview.background = Palette.oval(c).apply { setStroke(dp(1), Color.parseColor("#33000000")) }
        hex.text = String.format("#%06X", 0xFFFFFF and c)
    }
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(12), dp(20), dp(4))
    }
    box.addView(preview, LinearLayout.LayoutParams(dp(72), dp(72)).apply { gravity = Gravity.CENTER_HORIZONTAL })
    box.addView(hex)
    fun slider(label: String, max: Int, start: Int, onChange: (Int) -> Unit) {
        box.addView(TextView(this).apply {
            text = label
            textSize = 13f
            setTextColor(Color.parseColor("#616161"))
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })
        val sb = SeekBar(this)
        sb.max = max
        sb.progress = start
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                onChange(p)
                refresh()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        box.addView(sb)
    }
    slider("Rona", 360, 30) { hsv[0] = it.toFloat() }
    slider("Kejenuhan", 100, 90) { hsv[1] = it / 100f }
    slider("Terang", 100, 100) { hsv[2] = it / 100f }
    refresh()
    AlertDialog.Builder(this)
        .setTitle("Warna kustom")
        .setView(box)
        .setPositiveButton("Pilih") { _, _ -> onPick(Color.HSVToColor(hsv)) }
        .setNegativeButton("Batal", null)
        .show()
}
