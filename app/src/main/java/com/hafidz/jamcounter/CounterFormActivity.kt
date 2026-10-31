package com.hafidz.jamcounter

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.time.LocalDate
import java.time.Month
import java.time.YearMonth

/**
 * Form counter baru / ubah counter. Tanggal dipilih dengan dua wheel (tanggal + bulan).
 * Tahun otomatis: tanggal terbaru yang tidak di masa depan.
 */
class CounterFormActivity : Activity() {

    companion object {
        const val EXTRA_ID = "id"

        /** Id counter yang baru disimpan; daftar utama akan menggulir ke kartu ini. */
        @Volatile
        var lastSavedId = -1L
    }

    private lateinit var db: Db
    private var editId = -1L
    private var original: Counter? = null

    private lateinit var dayPicker: NumberPicker
    private lateinit var monthPicker: NumberPicker
    private lateinit var resolvedLabel: TextView
    private lateinit var warning: TextView
    private lateinit var noteInput: EditText
    private lateinit var startInput: EditText
    private lateinit var readOnlySwitch: Switch
    private lateinit var colorDot: View

    private var color = Palette.BLACK
    private var isColorCustomized = false
    private var chosenDate: LocalDate = LocalDate.now()
    private var canSave = true
    private var saveItem: MenuItem? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = Db.get(this)

        editId = intent.getLongExtra(EXTRA_ID, -1L)
        if (editId >= 0) {
            original = db.counter(editId)
            if (original == null) {
                finish()
                return
            }
        }

        title = if (editId >= 0) "Ubah counter" else "Counter baru"
        actionBar?.setBackgroundDrawable(ColorDrawable(Color.parseColor("#FFC107")))
        actionBar?.setDisplayHomeAsUpEnabled(true)
        actionBar?.setHomeAsUpIndicator(R.drawable.ic_close)

        val initialDate = original?.let { LocalDate.ofEpochDay(it.day) } ?: LocalDate.now()
        color = original?.color ?: Palette.defaultColorFor(initialDate)
        isColorCustomized = (original != null)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(24))
        }

        // ---- wheel tanggal + bulan ----
        col.addView(sectionLabel("Tanggal"))
        val wheels = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        dayPicker = NumberPicker(this).apply {
            minValue = 1
            maxValue = Month.of(initialDate.monthValue).maxLength()
            value = initialDate.dayOfMonth
            wrapSelectorWheel = true
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
        monthPicker = NumberPicker(this).apply {
            minValue = 1
            maxValue = 12
            displayedValues = Fmt.monthsFull
            value = initialDate.monthValue
            wrapSelectorWheel = true
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
        wheels.addView(
            dayPicker,
            LinearLayout.LayoutParams(0, dp(150), 1f).apply { setMargins(dp(8), 0, dp(8), 0) }
        )
        wheels.addView(
            monthPicker,
            LinearLayout.LayoutParams(0, dp(150), 2f).apply { setMargins(dp(8), 0, dp(8), 0) }
        )
        col.addView(wheels)

        monthPicker.setOnValueChangedListener { _, _, newMonth ->
            dayPicker.maxValue = Month.of(newMonth).maxLength()
            update()
        }
        dayPicker.setOnValueChangedListener { _, _, _ -> update() }

        resolvedLabel = TextView(this).apply {
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#616161"))
        }
        col.addView(resolvedLabel, matchWrap().apply { topMargin = dp(4) })

        warning = TextView(this).apply {
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#D32F2F"))
            visibility = View.GONE
        }
        col.addView(warning, matchWrap().apply { topMargin = dp(4) })

        // ---- catatan ----
        noteInput = EditText(this).apply {
            hint = "Catatan (opsional)"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
            filters = arrayOf(InputFilter.LengthFilter(40))
            background = Palette.outline(Color.WHITE, Color.parseColor("#79747E"), dp(8).toFloat(), dp(1))
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setText(original?.note ?: "")
        }
        col.addView(noteInput, matchWrap().apply { topMargin = dp(20) })

        // ---- start value ----
        startInput = EditText(this).apply {
            hint = "Start value"
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine()
            filters = arrayOf(InputFilter.LengthFilter(6))
            background = Palette.outline(Color.WHITE, Color.parseColor("#79747E"), dp(8).toFloat(), dp(1))
            setPadding(dp(14), dp(14), dp(14), dp(14))
            val sv = original?.startValue ?: 0
            if (sv > 0) setText(sv.toString())
        }
        col.addView(startInput, matchWrap().apply { topMargin = dp(12) })

        // ---- read only ----
        val roRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        roRow.addView(
            TextView(this).apply {
                text = "Read Only"
                textSize = 16f
                setTextColor(Color.BLACK)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        readOnlySwitch = Switch(this).apply { isChecked = original?.readOnly ?: false }
        roRow.addView(readOnlySwitch)
        col.addView(roRow, matchWrap().apply { topMargin = dp(16) })

        // ---- warna ----
        val colorRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        colorRow.addView(
            TextView(this).apply {
                text = "Pilih warna"
                textSize = 16f
                setTextColor(Color.BLACK)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        colorDot = View(this).apply {
            background = Palette.oval(color)
            elevation = dp(3).toFloat()
            setOnClickListener {
                showColorPicker {
                    color = it
                    isColorCustomized = true
                    background = Palette.oval(color)
                }
            }
        }
        colorRow.addView(colorDot, LinearLayout.LayoutParams(dp(44), dp(44)))
        col.addView(colorRow, matchWrap().apply { topMargin = dp(20) })

        val scroll = ScrollView(this)
        scroll.addView(col)
        setContentView(scroll)

        update()
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun sectionLabel(text: String) = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.parseColor("#616161"))
    }

    /** Ubah angka wheel jadi tanggal lengkap: kemunculan terbaru yang tidak di masa depan. */
    private fun resolve(d: Int, m: Int): LocalDate {
        original?.let {
            val od = LocalDate.ofEpochDay(it.day)
            if (od.dayOfMonth == d && od.monthValue == m) return od // tahun asli dipertahankan
        }
        val today = LocalDate.now()
        for (k in 0..8) {
            val y = today.year - k
            if (d <= YearMonth.of(y, m).lengthOfMonth()) {
                val cand = LocalDate.of(y, m, d)
                if (!cand.isAfter(today)) return cand
            }
        }
        return today
    }

    private fun update() {
        chosenDate = resolve(dayPicker.value, monthPicker.value)
        resolvedLabel.text = Fmt.dateFull(chosenDate)
        if (!isColorCustomized) {
            color = Palette.defaultColorFor(chosenDate)
            colorDot.background = Palette.oval(color)
        }
        val dup = db.dayTaken(chosenDate.toEpochDay(), editId)
        warning.text = "Tanggal ini sudah punya counter. Pilih tanggal lain."
        warning.visibility = if (dup) View.VISIBLE else View.GONE
        canSave = !dup
        applySaveState()
    }

    private fun applySaveState() {
        val item = saveItem ?: return
        item.isEnabled = canSave
        item.icon?.mutate()?.alpha = if (canSave) 255 else 70
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        val item = menu.add(0, 1, 0, "Simpan")
        item.setIcon(R.drawable.ic_save)
        item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        saveItem = item
        applySaveState()
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> finish()
            1 -> save()
        }
        return true
    }

    private fun save() {
        if (!canSave) return
        val day = chosenDate.toEpochDay()
        val note = noteInput.text.toString().trim()
        val start = startInput.text.toString().toIntOrNull()?.coerceIn(0, 999999) ?: 0
        val readOnly = readOnlySwitch.isChecked

        if (editId < 0) {
            val id = db.addCounter(day, note, color, start, readOnly)
            if (id < 0) {
                Toast.makeText(this, "Tanggal ini sudah punya counter", Toast.LENGTH_SHORT).show()
                return
            }
            lastSavedId = id
        } else {
            if (!db.updateCounter(editId, day, note, color, start, readOnly)) {
                Toast.makeText(this, "Gagal menyimpan: tanggal sudah dipakai", Toast.LENGTH_SHORT).show()
                return
            }
            lastSavedId = editId
        }
        finish()
    }
}
