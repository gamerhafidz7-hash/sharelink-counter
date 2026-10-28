package com.hafidz.jamcounter

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/**
 * Statistik gaya Shopee. Aturan sumbu: rentang 1 hari = per jam, 2 hari atau lebih = per hari.
 *  - Angka harian = nilai counter di tanggal itu.
 *  - Grafik jam = catatan jam milik counter tanggal itu.
 * Dibuka dari kartu (ikon grafik) => langsung ke tanggal kartu; dari menu => 7 hari terakhir.
 */
class StatsActivity : Activity() {

    companion object {
        const val EXTRA_DAY = "day"
        private const val MAX_CUSTOM_DAYS = 92
        private val ACCENT = Color.parseColor("#EE4D2D")
        private val GREEN = Color.parseColor("#1DA85A")
        private val GRAY = Color.parseColor("#757575")
        private val PAGE_BG = Color.parseColor("#F0F0F0")
    }

    private enum class Kind { TODAY, YESTERDAY, DATE, D7, D30, CUSTOM, MONTH }

    private lateinit var db: Db
    private lateinit var btnToday: TextView
    private lateinit var btn7: TextView
    private lateinit var btn30: TextView
    private lateinit var btnMore: TextView
    private lateinit var prevBtn: TextView
    private lateinit var nextBtn: TextView
    private lateinit var rangeLabel: TextView
    private lateinit var boxTotal: MetricBox
    private lateinit var boxMid: MetricBox
    private lateinit var boxPeak: MetricBox
    private lateinit var chartTitle: TextView
    private lateinit var chart: LineChartView
    private lateinit var noteView: TextView

    private var kind = Kind.D7
    private var from: LocalDate = LocalDate.now()
    private var to: LocalDate = LocalDate.now()

    private val listener: () -> Unit = { runOnUiThread { reload() } }

    // ------------------------------------------------------------------ siklus hidup

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = Db.get(this)
        title = "Statistik"
        actionBar?.setBackgroundDrawable(ColorDrawable(Color.parseColor("#FFC107")))
        actionBar?.setDisplayHomeAsUpEnabled(true)

        val extraDay = intent.getLongExtra(EXTRA_DAY, Long.MIN_VALUE)
        if (extraDay != Long.MIN_VALUE) setSingle(LocalDate.ofEpochDay(extraDay)) else setLast(7)

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(PAGE_BG)
        }

        // ---- baris filter ----
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(8), dp(10), dp(8), dp(10))
        }
        btnToday = filterButton("Hari ini") {
            setSingle(Days.today())
            reload()
        }
        btn7 = filterButton("7 Hari") {
            setLast(7)
            reload()
        }
        btn30 = filterButton("30 Hari") {
            setLast(30)
            reload()
        }
        btnMore = filterButton("Lainnya ▾") { showMoreMenu() }
        for (b in listOf(btnToday, btn7, btn30, btnMore)) {
            bar.addView(
                b,
                LinearLayout.LayoutParams(0, dp(44), 1f).apply { setMargins(dp(4), 0, dp(4), 0) }
            )
        }
        page.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // ---- kartu ringkasan + grafik ----
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Palette.rounded(Color.WHITE, dp(16).toFloat())
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }

        card.addView(
            TextView(this).apply {
                text = "Ringkasan"
                textSize = 18f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.BLACK)
            }
        )

        // navigasi rentang: ‹ label ›
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        prevBtn = navButton("‹") { shift(-1) }
        nextBtn = navButton("›") { shift(1) }
        rangeLabel = TextView(this).apply {
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(GRAY)
            setOnClickListener { onRangeLabelClick() }
        }
        nav.addView(prevBtn, LinearLayout.LayoutParams(dp(44), dp(40)))
        nav.addView(rangeLabel, LinearLayout.LayoutParams(0, dp(40), 1f))
        nav.addView(nextBtn, LinearLayout.LayoutParams(dp(44), dp(40)))
        card.addView(
            nav,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(4) }
        )

        // tiga kotak metrik
        val metrics = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        boxTotal = MetricBox(this, true)
        boxMid = MetricBox(this, false)
        boxPeak = MetricBox(this, false)
        for (b in listOf(boxTotal, boxMid, boxPeak)) {
            metrics.addView(
                b.view,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { setMargins(dp(3), 0, dp(3), 0) }
            )
        }
        card.addView(
            metrics,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(10) }
        )
        card.addView(
            TextView(this).apply {
                text = "Persen = dibanding periode sebelumnya"
                textSize = 11f
                setTextColor(Color.parseColor("#9E9E9E"))
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(6) }
        )

        chartTitle = TextView(this).apply {
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(GRAY)
        }
        card.addView(
            chartTitle,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(18) }
        )
        chart = LineChartView(this)
        card.addView(
            chart,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(250)).apply { topMargin = dp(4) }
        )
        noteView = TextView(this).apply {
            textSize = 12f
            setTextColor(GRAY)
        }
        card.addView(
            noteView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(6) }
        )

        page.addView(
            card,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(dp(10), dp(10), dp(10), dp(16)) }
        )

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(PAGE_BG)
        scroll.addView(page)
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        db.listeners.add(listener)
        reload()
    }

    override fun onPause() {
        super.onPause()
        db.listeners.remove(listener)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) finish()
        return true
    }

    // ------------------------------------------------------------------ pilihan rentang

    private fun setSingle(d: LocalDate) {
        from = d
        to = d
        val today = Days.today()
        kind = when (d) {
            today -> Kind.TODAY
            today.minusDays(1) -> Kind.YESTERDAY
            else -> Kind.DATE
        }
    }

    /** 7/30 hari terakhir, mulai KEMARIN (hari ini tidak ikut). */
    private fun setLast(n: Int) {
        to = Days.today().minusDays(1)
        from = to.minusDays(n - 1L)
        kind = if (n == 7) Kind.D7 else Kind.D30
    }

    private fun setCustom(a: LocalDate, b: LocalDate) {
        if (a == b) {
            setSingle(a)
        } else {
            from = a
            to = b
            kind = Kind.CUSTOM
        }
    }

    private fun setMonth(ym: YearMonth) {
        from = ym.atDay(1)
        to = ym.atEndOfMonth()
        kind = Kind.MONTH
    }

    private fun canNext(): Boolean {
        val today = Days.today()
        return when (kind) {
            Kind.TODAY, Kind.YESTERDAY, Kind.DATE -> from.isBefore(today)
            Kind.D7, Kind.D30 -> to.isBefore(today.minusDays(1))
            Kind.CUSTOM -> to.isBefore(today)
            Kind.MONTH -> !YearMonth.from(from).plusMonths(1).atDay(1).isAfter(today)
        }
    }

    private fun shift(dir: Int) {
        if (dir > 0 && !canNext()) return
        val today = Days.today()
        when (kind) {
            Kind.TODAY, Kind.YESTERDAY, Kind.DATE -> setSingle(from.plusDays(dir.toLong()))
            Kind.MONTH -> setMonth(YearMonth.from(from).plusMonths(dir.toLong()))
            Kind.D7, Kind.D30, Kind.CUSTOM -> {
                val len = ChronoUnit.DAYS.between(from, to) + 1
                val cap = if (kind == Kind.CUSTOM) today else today.minusDays(1)
                var newTo = to.plusDays(dir * len)
                if (newTo.isAfter(cap)) newTo = cap
                to = newTo
                from = newTo.minusDays(len - 1)
            }
        }
        reload()
    }

    private fun showMoreMenu() {
        val pm = PopupMenu(this, btnMore)
        pm.menu.add(0, 1, 0, "Kemarin")
        pm.menu.add(0, 2, 1, "Pilih tanggal")
        pm.menu.add(0, 3, 2, "Custom tanggal")
        pm.menu.add(0, 4, 3, "Pilih bulan")
        pm.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    setSingle(Days.today().minusDays(1))
                    reload()
                }
                2 -> pickSingleDate()
                3 -> pickCustom()
                4 -> pickMonth()
            }
            true
        }
        pm.show()
    }

    private fun onRangeLabelClick() {
        when (kind) {
            Kind.TODAY, Kind.YESTERDAY, Kind.DATE -> pickSingleDate()
            Kind.CUSTOM -> pickCustom()
            Kind.MONTH -> pickMonth()
            else -> {}
        }
    }

    private fun showDatePicker(
        initial: LocalDate,
        min: LocalDate?,
        max: LocalDate,
        title: String,
        onPick: (LocalDate) -> Unit
    ) {
        var start = initial
        if (start.isAfter(max)) start = max
        if (min != null && start.isBefore(min)) start = min
        val dlg = DatePickerDialog(
            this,
            { _, y, m, d -> onPick(LocalDate.of(y, m + 1, d)) },
            start.year, start.monthValue - 1, start.dayOfMonth
        )
        if (min != null) dlg.datePicker.minDate = Days.startMillis(min)
        dlg.datePicker.maxDate = Days.endMillis(max)
        dlg.setTitle(title)
        dlg.show()
    }

    private fun pickSingleDate() {
        showDatePicker(from, null, Days.today(), "Pilih tanggal") { d ->
            setSingle(d)
            reload()
        }
    }

    private fun pickCustom() {
        val today = Days.today()
        showDatePicker(from, null, today, "Dari tanggal") { a ->
            val initialTo = if (to.isBefore(a)) a else to
            showDatePicker(initialTo, a, today, "Sampai tanggal") { b ->
                var end = b
                val maxEnd = a.plusDays(MAX_CUSTOM_DAYS - 1L)
                if (end.isAfter(maxEnd)) {
                    end = maxEnd
                    Toast.makeText(this, "Maksimal $MAX_CUSTOM_DAYS hari, rentang dipotong", Toast.LENGTH_LONG).show()
                }
                setCustom(a, end)
                reload()
            }
        }
    }

    private fun pickMonth() {
        val now = Days.today()
        val maxYear = now.year
        val earliest = db.minDay()?.let { LocalDate.ofEpochDay(it).year } ?: maxYear
        val minYear = minOf(earliest, maxYear)
        var year = if (kind == Kind.MONTH) from.year else maxYear
        year = year.coerceIn(minYear, maxYear)

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val prev = TextView(this).apply {
            text = "‹"
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(Color.BLACK)
        }
        val yearTv = TextView(this).apply {
            textSize = 18f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.BLACK)
        }
        val next = TextView(this).apply {
            text = "›"
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(Color.BLACK)
        }
        head.addView(prev, LinearLayout.LayoutParams(dp(48), dp(44)))
        head.addView(yearTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(next, LinearLayout.LayoutParams(dp(48), dp(44)))
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(head)
        box.addView(grid)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Pilih bulan")
            .setView(box)
            .setNegativeButton("Batal", null)
            .create()

        val strokePx = (resources.displayMetrics.density * 1.5f).toInt()

        fun rebuild() {
            yearTv.text = year.toString()
            prev.alpha = if (year > minYear) 1f else 0.25f
            next.alpha = if (year < maxYear) 1f else 0.25f
            grid.removeAllViews()
            for (r in 0 until 4) {
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                for (cc in 0 until 3) {
                    val m = r * 3 + cc + 1
                    val ym = YearMonth.of(year, m)
                    val future = ym.atDay(1).isAfter(now)
                    val total = if (future) 0 else db.sumBetween(ym.atDay(1).toEpochDay(), ym.atEndOfMonth().toEpochDay())
                    val selected = kind == Kind.MONTH && YearMonth.from(from) == ym
                    val name = Fmt.monthsFull[m - 1]
                    val sub = if (future) "–" else total.toString()
                    val sp = SpannableString("$name\n$sub")
                    sp.setSpan(RelativeSizeSpan(0.8f), name.length + 1, sp.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sp.setSpan(ForegroundColorSpan(GRAY), name.length + 1, sp.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    val cell = TextView(this).apply {
                        text = sp
                        gravity = Gravity.CENTER
                        textSize = 15f
                        setTextColor(Color.BLACK)
                        background = if (selected) {
                            Palette.outline(Color.WHITE, ACCENT, dp(8).toFloat(), strokePx)
                        } else {
                            Palette.rounded(Color.parseColor("#F5F5F5"), dp(8).toFloat())
                        }
                        alpha = if (future) 0.3f else if (total == 0) 0.6f else 1f
                        setOnClickListener {
                            if (!future) {
                                setMonth(ym)
                                dialog.dismiss()
                                reload()
                            }
                        }
                    }
                    row.addView(
                        cell,
                        LinearLayout.LayoutParams(0, dp(60), 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) }
                    )
                }
                grid.addView(row)
            }
        }

        prev.setOnClickListener {
            if (year > minYear) {
                year--
                rebuild()
            }
        }
        next.setOnClickListener {
            if (year < maxYear) {
                year++
                rebuild()
            }
        }
        rebuild()
        dialog.show()
    }

    // ------------------------------------------------------------------ tampilan

    private fun filterButton(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 14f
        gravity = Gravity.CENTER
        maxLines = 1
        setOnClickListener { onClick() }
    }

    private fun navButton(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 28f
        gravity = Gravity.CENTER
        setTextColor(Color.BLACK)
        setOnClickListener { onClick() }
    }

    private fun styleFilter(tv: TextView, active: Boolean) {
        if (active) {
            val stroke = (resources.displayMetrics.density * 1.5f).toInt()
            tv.background = Palette.outline(Color.WHITE, ACCENT, dp(8).toFloat(), stroke)
            tv.setTextColor(ACCENT)
        } else {
            tv.background = Palette.rounded(Color.parseColor("#F5F5F5"), dp(8).toFloat())
            tv.setTextColor(Color.parseColor("#424242"))
        }
    }

    private fun updateFilters() {
        styleFilter(btnToday, kind == Kind.TODAY)
        styleFilter(btn7, kind == Kind.D7)
        styleFilter(btn30, kind == Kind.D30)
        val inMore = kind == Kind.YESTERDAY || kind == Kind.DATE || kind == Kind.CUSTOM || kind == Kind.MONTH
        styleFilter(btnMore, inMore)
        btnMore.text = when (kind) {
            Kind.YESTERDAY -> "Kemarin ▾"
            Kind.DATE -> "Tanggal ▾"
            Kind.CUSTOM -> "Custom ▾"
            Kind.MONTH -> "Bulan ▾"
            else -> "Lainnya ▾"
        }
    }

    private fun updateRangeLabel() {
        rangeLabel.text = when {
            kind == Kind.MONTH -> Fmt.monthsFull[from.monthValue - 1] + " " + from.year
            from == to -> Fmt.dmy(from)
            else -> Fmt.dmy(from) + " – " + Fmt.dmy(to)
        }
        nextBtn.alpha = if (canNext()) 1f else 0.25f
    }

    /** Persen perubahan + warna (hijau naik, merah turun, abu sama/tidak ada pembanding). */
    private fun percent(cur: Int, prev: Int): Pair<String, Int> {
        if (prev == 0) return if (cur == 0) Pair("0%", GRAY) else Pair("—", GRAY)
        val p = Math.round((cur - prev) * 100f / prev)
        val text = (if (p > 0) "+" else "") + p + "%"
        val color = if (p > 0) GREEN else if (p < 0) ACCENT else GRAY
        return Pair(text, color)
    }

    private fun reload() {
        updateFilters()
        updateRangeLabel()
        val days = ChronoUnit.DAYS.between(from, to) + 1
        if (days == 1L) loadHourly() else loadDaily(days.toInt())
    }

    /** Satu hari: grafik per jam dari catatan jam counter tanggal itu. */
    private fun loadHourly() {
        val day = from.toEpochDay()
        val c = db.counterByDay(day)
        val hours = IntArray(24)
        var events = 0
        if (c != null) {
            for (ts in db.eventTimes(c.id)) {
                hours[Days.hourOf(ts)]++
                events++
            }
        }
        val nowHour = if (from == Days.today()) LocalTime.now().hour else 23
        val series = IntArray(24) { if (it <= nowHour) hours[it] else -1 }
        val labels = (0 until 24).map { Fmt.hour(it) }
        val tips = (0 until 24).map { Fmt.hour(it) + " · " + hours[it] }
        chart.setData(series, labels, tips)
        chartTitle.text = "Grafik per jam"

        val total = c?.value ?: 0
        val prevTotal = db.counterByDay(day - 1)?.value ?: 0
        val (pText, pColor) = percent(total, prevTotal)
        boxTotal.set("Total", total.toString(), pText, pColor)

        var activeHours = 0
        var peakHour = -1
        var peakVal = 0
        for (h in 0 until 24) {
            if (hours[h] > 0) activeHours++
            if (hours[h] > peakVal) {
                peakVal = hours[h]
                peakHour = h
            }
        }
        boxMid.set("Jam aktif", activeHours.toString(), "", GRAY)
        if (peakHour >= 0) {
            boxPeak.set("Puncak", Fmt.hour(peakHour), peakVal.toString(), GRAY)
        } else {
            boxPeak.set("Puncak", "–", "", GRAY)
        }

        noteView.text = when {
            c == null -> "Belum ada counter di tanggal ini."
            total > events -> "${total - events} dari isian awal (Start value), tanpa data jam."
            events == 0 -> "Belum ada catatan."
            else -> ""
        }
    }

    /** 2 hari atau lebih: grafik per hari dari nilai counter tiap tanggal. */
    private fun loadDaily(n: Int) {
        val map = db.valuesBetween(from.toEpochDay(), to.toEpochDay())
        val today = Days.today()
        val series = IntArray(n)
        val labels = ArrayList<String>(n)
        val tips = ArrayList<String>(n)
        var total = 0
        var peakIdx = -1
        var peakVal = 0
        for (i in 0 until n) {
            val d = from.plusDays(i.toLong())
            val v = map[d.toEpochDay()]
            series[i] = if (d.isAfter(today)) -1 else (v ?: 0)
            labels.add(if (kind == Kind.MONTH) d.dayOfMonth.toString() else "${d.dayOfMonth}/${d.monthValue}")
            tips.add(Fmt.dateLong(d) + " · " + (v?.toString() ?: "tidak ada counter"))
            if (v != null && !d.isAfter(today)) {
                total += v
                if (v > peakVal) {
                    peakVal = v
                    peakIdx = i
                }
            }
        }
        chart.setData(series, labels, tips)
        chartTitle.text = "Grafik per hari"

        val activeDays = map.keys.count { !LocalDate.ofEpochDay(it).isAfter(today) }

        val prevFrom: LocalDate
        val prevTo: LocalDate
        if (kind == Kind.MONTH) {
            val pm = YearMonth.from(from).minusMonths(1)
            prevFrom = pm.atDay(1)
            prevTo = pm.atEndOfMonth()
        } else {
            prevTo = from.minusDays(1)
            prevFrom = prevTo.minusDays(n - 1L)
        }
        val prevTotal = db.sumBetween(prevFrom.toEpochDay(), prevTo.toEpochDay())
        val (pText, pColor) = percent(total, prevTotal)
        boxTotal.set("Total", total.toString(), pText, pColor)

        if (activeDays > 0) {
            boxMid.set("Rata-rata/hari", Fmt.decimal(total.toDouble() / activeDays), "hari aktif", GRAY)
        } else {
            boxMid.set("Rata-rata/hari", "–", "", GRAY)
        }
        if (peakIdx >= 0 && peakVal > 0) {
            boxPeak.set("Puncak", Fmt.dayMonth(from.plusDays(peakIdx.toLong())), peakVal.toString(), GRAY)
        } else {
            boxPeak.set("Puncak", "–", "", GRAY)
        }

        noteView.text = if (activeDays == 0) {
            "Belum ada counter di rentang ini."
        } else {
            "$activeDays dari $n hari ada counter."
        }
    }

    /** Kotak metrik kecil: label, nilai besar, keterangan/persen. */
    private class MetricBox(act: Activity, highlight: Boolean) {
        val view = LinearLayout(act)
        private val labelTv = TextView(act)
        private val valueTv = TextView(act)
        private val subTv = TextView(act)

        init {
            view.orientation = LinearLayout.VERTICAL
            view.setPadding(act.dp(10), act.dp(10), act.dp(8), act.dp(10))
            val stroke = if (highlight) ACCENT else Color.parseColor("#EEEEEE")
            view.background = Palette.outline(Color.parseColor("#FAFAFA"), stroke, act.dp(8).toFloat(), act.dp(1))

            labelTv.textSize = 12f
            labelTv.maxLines = 1
            labelTv.setTextColor(if (highlight) ACCENT else GRAY)
            valueTv.textSize = 20f
            valueTv.maxLines = 1
            valueTv.setTypeface(valueTv.typeface, Typeface.BOLD)
            valueTv.setTextColor(Color.parseColor("#212121"))
            subTv.textSize = 12f
            subTv.maxLines = 1

            view.addView(labelTv)
            view.addView(valueTv)
            view.addView(subTv)
        }

        fun set(label: String, value: String, sub: String, subColor: Int) {
            labelTv.text = label
            valueTv.text = value
            subTv.text = if (sub.isEmpty()) " " else sub
            subTv.setTextColor(subColor)
        }
    }
}
