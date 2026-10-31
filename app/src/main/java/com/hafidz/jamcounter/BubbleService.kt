package com.hafidz.jamcounter

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Bubble melayang di atas aplikasi lain.
 *  - Ketuk            = +1
 *  - Tahan            = tombol minus + reset muncul di samping bubble; tahan lagi = hilang
 *  - Geser            = pindahkan; muncul X di bawah, lepas di atas X = tutup; lepas di tempat lain = tempel tepi
 *  - Lewat tengah malam = notifikasi pilihan: tetap di hari lama (jam 23:00) atau ganti ke hari baru
 * Ukuran, kecepatan tahan, tempel tepi, dan getaran diatur di Pengaturan (Cfg).
 *
 * Satu jendela overlay berisi container: bubble + 2 tombol, dengan ruang kosong [pad] di semua sisi
 * agar bayangan tidak terpotong. Ukuran jendela diubah saat tombol muncul/hilang dan posisinya
 * digeser supaya bubble tidak bergerak di layar.
 */
class BubbleService : Service() {

    companion object {
        const val ACTION_SHOW = "com.hafidz.jamcounter.SHOW"
        const val ACTION_HIDE = "com.hafidz.jamcounter.HIDE"
        const val ACTION_RELOAD = "com.hafidz.jamcounter.RELOAD"
        const val ACTION_STAY = "com.hafidz.jamcounter.STAY"
        const val ACTION_SWITCH = "com.hafidz.jamcounter.SWITCH"
        const val EXTRA_ID = "id"

        @Volatile
        var running = false

        private const val CHANNEL = "bubble"
        private const val NOTIF_ID = 1
        private const val CHANNEL_DAY = "rollover"
        private const val NOTIF_DAY_ID = 2
    }

    private lateinit var wm: WindowManager
    private lateinit var db: Db
    private val handler = Handler(Looper.getMainLooper())

    private var container: FrameLayout? = null
    private var bubble: TextView? = null
    private var minusBtn: ImageView? = null
    private var resetBtn: ImageView? = null
    private var winLp: WindowManager.LayoutParams? = null

    private var closeView: FrameLayout? = null
    private var hoverClose = false
    private var dialog: AlertDialog? = null
    private var snapAnim: ValueAnimator? = null
    private var collapseRunnable: Runnable? = null

    private var counterId = -1L
    private var expanded = false
    private var buttonsLeft = true
    private var ctrlAlpha = 1f

    /** Tanggal counter bubble kalau counter itu adalah counter "hari ini" saat bubble tampil; null = tidak dipantau. */
    private var watchDay: Long? = null

    private val dateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            checkRollover()
        }
    }

    private val pad get() = dp(12)
    private val bubbleSize get() = dp(Cfg.bubbleDp(this))
    private val btnSize get() = (bubbleSize * 0.7f).toInt()
    private val gapSize get() = dp(2)
    private val closeSize get() = dp(64)

    private val listener: () -> Unit = { handler.post { refresh() } }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        db = Db.get(this)
        running = true
        db.listeners.add(listener)
        val f = IntentFilter()
        f.addAction(Intent.ACTION_DATE_CHANGED)
        f.addAction(Intent.ACTION_TIME_CHANGED)
        f.addAction(Intent.ACTION_TIMEZONE_CHANGED)
        registerReceiver(dateReceiver, f)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_HIDE) {
            stopBubble()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STAY || intent?.action == ACTION_SWITCH) {
            chooseDay(intent.action == ACTION_SWITCH)
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_RELOAD) {
            if (container == null) {
                stopSelf()
            } else {
                collapseNow()
                removeBubble()
                addBubble()
                refresh()
            }
            return START_NOT_STICKY
        }

        startForegroundCompat()

        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        val id = intent?.getLongExtra(EXTRA_ID, -1L) ?: -1L
        counterId = if (id >= 0) id else prefs.getLong("bubble_id", -1L)

        if (counterId < 0 || db.counter(counterId) == null || !Settings.canDrawOverlays(this)) {
            stopBubble()
            return START_NOT_STICKY
        }

        prefs.edit().putLong("bubble_id", counterId).apply()
        val shown = db.counter(counterId)
        watchDay = if (shown != null && shown.day == Days.today().toEpochDay()) shown.day else null
        if (container == null) addBubble()
        refresh()
        db.notifyChanged() // supaya layar utama memperbarui tombol bubble
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        db.listeners.remove(listener)
        try {
            unregisterReceiver(dateReceiver)
        } catch (_: Exception) {
        }
        snapAnim?.cancel()
        dialog?.dismiss()
        dialog = null
        hideCloseTarget()
        removeBubble()
        super.onDestroy()
    }

    private fun stopBubble() {
        running = false
        getSharedPreferences("prefs", MODE_PRIVATE).edit().putLong("bubble_id", -1L).apply()
        snapAnim?.cancel()
        dialog?.dismiss()
        dialog = null
        watchDay = null
        getSystemService(NotificationManager::class.java).cancel(NOTIF_DAY_ID)
        hideCloseTarget()
        removeBubble()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        db.notifyChanged()
    }

    // ------------------------------------------------------------------ pembuatan view

    private fun circleButton(res: Int, onClick: () -> Unit): ImageView = ImageView(this).apply {
        val p = (btnSize * 0.26f).toInt()
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(Color.parseColor("#212121"))
        scaleType = ImageView.ScaleType.FIT_CENTER
        setPadding(p, p, p, p)
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#F5F5F5"))
            setStroke(dp(1), Color.parseColor("#22000000"))
        }
        elevation = dp(5).toFloat()
        setOnClickListener { onClick() }
    }

    private fun addBubble() {
        val b = bubbleSize
        val s = btnSize
        val p = pad
        val metrics = resources.displayMetrics
        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)

        val box = FrameLayout(this)
        box.clipChildren = false

        val tv = TextView(this).apply {
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            elevation = dp(8).toFloat()
        }
        val minus = circleButton(R.drawable.ic_minus) { onMinus() }
        val reset = circleButton(R.drawable.ic_undo) { confirmReset() }
        minus.visibility = View.GONE
        reset.visibility = View.GONE

        box.addView(tv, FrameLayout.LayoutParams(b, b, Gravity.TOP or Gravity.START).apply { setMargins(p, p, 0, 0) })
        box.addView(minus, FrameLayout.LayoutParams(s, s, Gravity.TOP or Gravity.START).apply { setMargins(p, p, 0, 0) })
        box.addView(reset, FrameLayout.LayoutParams(s, s, Gravity.TOP or Gravity.START).apply { setMargins(p, p, 0, 0) })

        val lp = WindowManager.LayoutParams(
            b + 2 * p,
            b + 2 * p,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = prefs.getInt("bx", metrics.widthPixels - b - 2 * p)
        lp.y = prefs.getInt("by", metrics.heightPixels / 3)

        container = box
        bubble = tv
        minusBtn = minus
        resetBtn = reset
        winLp = lp
        expanded = false

        attachTouch(tv, lp)
        wm.addView(box, lp)

        // animasi muncul
        tv.scaleX = 0f
        tv.scaleY = 0f
        tv.animate().scaleX(1f).scaleY(1f).setDuration(320)
            .setInterpolator(OvershootInterpolator(2.5f)).start()
    }

    private fun removeBubble() {
        container?.let {
            try {
                wm.removeView(it)
            } catch (_: Exception) {
            }
        }
        container = null
        bubble = null
        minusBtn = null
        resetBtn = null
        winLp = null
        expanded = false
    }

    /** Atur posisi bubble + 2 tombol di dalam container sesuai status [expanded]/[buttonsLeft]. */
    private fun layoutChildren() {
        val tv = bubble ?: return
        val m = minusBtn ?: return
        val r = resetBtn ?: return
        val b = bubbleSize
        val s = btnSize
        val g = gapSize
        val p = pad
        val vOff = (2 * s + g - b) / 2

        val tvLp = tv.layoutParams as FrameLayout.LayoutParams
        tvLp.leftMargin = p + (if (expanded && buttonsLeft) s + g else 0)
        tvLp.topMargin = p + (if (expanded) vOff else 0)
        tv.layoutParams = tvLp

        val btnLeft = p + (if (buttonsLeft) 0 else b + g)
        val mLp = m.layoutParams as FrameLayout.LayoutParams
        mLp.leftMargin = btnLeft
        mLp.topMargin = p
        m.layoutParams = mLp

        val rLp = r.layoutParams as FrameLayout.LayoutParams
        rLp.leftMargin = btnLeft
        rLp.topMargin = p + s + g
        r.layoutParams = rLp
    }

    private fun popIn(v: View, delay: Long) {
        v.alpha = 0f
        v.scaleX = 0.3f
        v.scaleY = 0.3f
        v.animate().alpha(ctrlAlpha).scaleX(1f).scaleY(1f).setStartDelay(delay).setDuration(220)
            .setInterpolator(OvershootInterpolator(2.5f)).start()
    }

    /** Tampilkan tombol minus + reset. Bubble tetap di tempat yang sama di layar. */
    private fun expand() {
        val box = container ?: return
        val lp = winLp ?: return
        if (expanded) return
        val b = bubbleSize
        val s = btnSize
        val g = gapSize
        val p = pad
        val h = 2 * s + g
        val vOff = (h - b) / 2

        val screenW = resources.displayMetrics.widthPixels
        buttonsLeft = lp.x + b / 2 + p > screenW / 2 // bubble di sisi kanan => tombol di kiri
        val bx = lp.x
        val by = lp.y
        expanded = true
        layoutChildren()
        lp.width = s + g + b + 2 * p
        lp.height = h + 2 * p
        lp.x = if (buttonsLeft) bx - (s + g) else bx
        lp.y = by - vOff
        minusBtn?.visibility = View.VISIBLE
        resetBtn?.visibility = View.VISIBLE
        wm.updateViewLayout(box, lp)
        minusBtn?.let { popIn(it, 0) }
        resetBtn?.let { popIn(it, 50) }
    }

    /** Sembunyikan tombol (dengan animasi singkat) lalu kecilkan jendela. */
    private fun collapse(animate: Boolean) {
        if (!expanded) return
        collapseRunnable?.let { handler.removeCallbacks(it) }
        if (animate) {
            for (v in listOf(minusBtn, resetBtn)) {
                v?.animate()?.alpha(0f)?.scaleX(0.3f)?.scaleY(0.3f)?.setStartDelay(0)?.setDuration(110)?.start()
            }
            val r = Runnable { collapseNow() }
            collapseRunnable = r
            handler.postDelayed(r, 120)
        } else {
            collapseNow()
        }
    }

    private fun collapseNow() {
        collapseRunnable?.let { handler.removeCallbacks(it) }
        collapseRunnable = null
        val box = container ?: return
        val lp = winLp ?: return
        if (!expanded) return
        val b = bubbleSize
        val s = btnSize
        val g = gapSize
        val p = pad
        val vOff = (2 * s + g - b) / 2
        val bx = if (buttonsLeft) lp.x + (s + g) else lp.x
        val by = lp.y + vOff
        expanded = false
        for (v in listOf(minusBtn, resetBtn)) {
            v?.animate()?.cancel()
            v?.visibility = View.GONE
            v?.alpha = ctrlAlpha
            v?.scaleX = 1f
            v?.scaleY = 1f
        }
        layoutChildren()
        lp.width = b + 2 * p
        lp.height = b + 2 * p
        lp.x = bx
        lp.y = by
        wm.updateViewLayout(box, lp)
    }

    private fun refresh() {
        val tv = bubble ?: return
        val c = db.counter(counterId)
        if (c == null) {
            stopBubble()
            return
        }
        val scale = bubbleSize / dp(58).toFloat()
        tv.text = c.value.toString()
        tv.textSize = scale * when {
            c.value >= 10000 -> 12f
            c.value >= 1000 -> 15f
            else -> 20f
        }
        tv.setTextColor(Palette.onColor(c.color))
        tv.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Palette.lighten(c.color, 0.22f), c.color)
        ).apply {
            shape = GradientDrawable.OVAL
            setStroke(dp(2), Palette.BORDER_DARK_GRAY)
        }
        ctrlAlpha = if (c.readOnly) 0.35f else 1f
        if (expanded) {
            minusBtn?.alpha = ctrlAlpha
            resetBtn?.alpha = ctrlAlpha
        }
    }

    private fun popBubble() {
        val tv = bubble ?: return
        tv.animate().cancel()
        tv.scaleX = 0.8f
        tv.scaleY = 0.8f
        tv.animate().scaleX(1f).scaleY(1f).setDuration(260)
            .setInterpolator(OvershootInterpolator(3f)).start()
    }

    // ------------------------------------------------------------------ aksi tombol

    private fun onMinus() {
        val c = db.counter(counterId) ?: return
        if (c.readOnly) {
            Haptics.play(this, Haptics.DOUBLE)
            Toast.makeText(this, "Counter ini Read Only", Toast.LENGTH_SHORT).show()
            return
        }
        if (db.change(counterId, -1)) {
            Haptics.play(this, Haptics.TICK)
            popBubble()
        }
    }

    /** Konfirmasi reset berupa AlertDialog bertipe overlay (service tidak punya Activity). */
    private fun confirmReset() {
        val c = db.counter(counterId) ?: return
        if (c.readOnly) {
            Haptics.play(this, Haptics.DOUBLE)
            Toast.makeText(this, "Counter ini Read Only", Toast.LENGTH_SHORT).show()
            return
        }
        Haptics.play(this, Haptics.CLICK)
        dialog?.dismiss()
        val themed = ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
        val d = AlertDialog.Builder(themed)
            .setTitle(resetTitle(c))
            .setMessage(resetMessage(c, db.eventCount(c.id)))
            .setPositiveButton("Reset") { _, _ ->
                if (db.reset(counterId)) {
                    Haptics.play(this, Haptics.HEAVY)
                    popBubble()
                }
            }
            .setNegativeButton("Batal", null)
            .create()
        d.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        d.setOnDismissListener { if (dialog === d) dialog = null }
        dialog = d
        d.show()
    }

    // ------------------------------------------------------------------ target X (tutup bubble)

    private fun showCloseTarget() {
        if (closeView != null) return
        val size = closeSize
        val v = FrameLayout(this)
        v.background = Palette.oval(Color.parseColor("#CC424242"))
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_close)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        v.addView(icon, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val lp = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        lp.y = dp(56)
        wm.addView(v, lp)
        closeView = v
        hoverClose = false
        v.alpha = 0f
        v.scaleX = 0.5f
        v.scaleY = 0.5f
        v.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180)
            .setInterpolator(OvershootInterpolator(2f)).start()
    }

    private fun hideCloseTarget() {
        closeView?.let {
            it.animate().cancel()
            try {
                wm.removeView(it)
            } catch (_: Exception) {
            }
        }
        closeView = null
        hoverClose = false
    }

    /** Bandingkan pusat bubble dengan pusat X memakai koordinat layar (aman dari beda status bar). */
    private fun updateCloseHover() {
        val cv = closeView ?: return
        val box = container ?: return
        if (!cv.isLaidOut || !box.isLaidOut) return
        val a = IntArray(2)
        val c = IntArray(2)
        box.getLocationOnScreen(a)
        cv.getLocationOnScreen(c)
        val bx = a[0] + box.width / 2f
        val by = a[1] + box.height / 2f
        val cx = c[0] + cv.width / 2f
        val cy = c[1] + cv.height / 2f
        val hit = hypot(bx - cx, by - cy) < dp(64)
        if (hit != hoverClose) {
            hoverClose = hit
            cv.animate().scaleX(if (hit) 1.3f else 1f).scaleY(if (hit) 1.3f else 1f).setDuration(120).start()
            cv.background = Palette.oval(Color.parseColor(if (hit) "#EEE53935" else "#CC424242"))
            bubble?.animate()?.scaleX(if (hit) 0.8f else 1f)?.scaleY(if (hit) 0.8f else 1f)?.setDuration(120)?.start()
            if (hit) Haptics.play(this, Haptics.CLICK)
        }
    }

    private fun closeWithAnimation() {
        val tv = bubble
        if (tv == null) {
            stopBubble()
            return
        }
        tv.animate().scaleX(0f).scaleY(0f).alpha(0f).setDuration(150).withEndAction {
            Haptics.play(this, Haptics.HEAVY)
            stopBubble()
        }.start()
    }

    // ------------------------------------------------------------------ posisi & tempel tepi

    private fun savePos(lp: WindowManager.LayoutParams) {
        getSharedPreferences("prefs", MODE_PRIVATE).edit().putInt("bx", lp.x).putInt("by", lp.y).apply()
    }

    /** Setelah digeser: tempel ke tepi terdekat (kalau diaktifkan) dengan animasi, lalu simpan posisi. */
    private fun finishMove(lp: WindowManager.LayoutParams) {
        val metrics = resources.displayMetrics
        val w = bubbleSize + 2 * pad
        val maxX = maxOf(0, metrics.widthPixels - w)
        val maxY = maxOf(0, metrics.heightPixels - w)
        val ty = lp.y.coerceIn(0, maxY)
        val tx = if (Cfg.snap(this)) {
            if (lp.x + w / 2 < metrics.widthPixels / 2) 0 else maxX
        } else {
            lp.x.coerceIn(0, maxX)
        }
        val sx = lp.x
        val sy = lp.y
        snapAnim?.cancel()
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = 240
        a.interpolator = DecelerateInterpolator(1.8f)
        a.addUpdateListener {
            val t = it.animatedValue as Float
            lp.x = (sx + (tx - sx) * t).toInt()
            lp.y = (sy + (ty - sy) * t).toInt()
            container?.let { c ->
                try {
                    wm.updateViewLayout(c, lp)
                } catch (_: Exception) {
                }
            }
        }
        a.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                savePos(lp)
            }
        })
        snapAnim = a
        a.start()
    }

    // ------------------------------------------------------------------ sentuhan bubble

    @SuppressLint("ClickableViewAccessibility")
    private fun attachTouch(tv: TextView, lp: WindowManager.LayoutParams) {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var startX = 0
        var startY = 0
        var downX = 0f
        var downY = 0f
        var moved = false
        var longFired = false

        val longPress = Runnable {
            if (!moved) {
                longFired = true
                tv.animate().scaleX(1f).scaleY(1f).setDuration(80).start()
                if (expanded) collapse(true) else expand()
                Haptics.play(this, Haptics.CLICK)
            }
        }

        tv.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    snapAnim?.cancel()
                    startX = lp.x
                    startY = lp.y
                    downX = e.rawX
                    downY = e.rawY
                    moved = false
                    longFired = false
                    tv.animate().scaleX(0.92f).scaleY(0.92f).setDuration(80).start()
                    handler.postDelayed(longPress, Cfg.holdMs(this))
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!longFired) { // setelah tahan, geseran di sentuhan yang sama diabaikan
                        val dx = (e.rawX - downX).toInt()
                        val dy = (e.rawY - downY).toInt()
                        if (!moved && (abs(dx) > slop || abs(dy) > slop)) {
                            moved = true
                            handler.removeCallbacks(longPress)
                            if (expanded) collapse(false) // tombol menutup saat mulai digeser
                            startX = lp.x
                            startY = lp.y
                            showCloseTarget()
                            Haptics.play(this, Haptics.TICK)
                        }
                        if (moved) {
                            lp.x = startX + dx
                            lp.y = startY + dy
                            container?.let { wm.updateViewLayout(it, lp) }
                            updateCloseHover()
                        }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPress)
                    if (moved) {
                        val closeIt = hoverClose
                        hideCloseTarget()
                        if (closeIt) closeWithAnimation() else {
                            tv.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                            finishMove(lp)
                        }
                    } else if (!longFired) {
                        checkRollover() // jaga-jaga kalau broadcast pergantian hari telat
                        val c = db.counter(counterId)
                        if (db.change(counterId, 1)) {
                            Haptics.play(this, Haptics.TICK)
                            popBubble()
                        } else {
                            tv.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                            if (c != null && c.readOnly) Haptics.play(this, Haptics.DOUBLE)
                        }
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPress)
                    hideCloseTarget()
                    tv.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                }
            }
            true
        }
    }

    // ------------------------------------------------------------------ pergantian hari (lewat tengah malam)

    /**
     * Dipanggil saat tanggal berganti (atau saat bubble diketuk). Kalau bubble sedang menampilkan
     * counter "hari ini" yang kini sudah kemarin, tampilkan notifikasi pilihan (satu kali).
     * Tanpa pilihan, perilakunya = tetap: klik dicatat jam 23:00 tanggal counter itu (aturan Db.change).
     */
    private fun checkRollover() {
        val w = watchDay ?: return
        if (container == null) return
        val today = Days.today().toEpochDay()
        if (today <= w) return
        watchDay = null // sekali saja per pergantian hari
        val old = db.counter(counterId) ?: return
        showRolloverNotification(old, today)
    }

    private fun showRolloverNotification(old: Counter, today: Long) {
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(CHANNEL_DAY, "Pergantian hari", NotificationManager.IMPORTANCE_DEFAULT)
        ch.setSound(null, null) // tanpa suara, tanpa pop-up; tetap muncul di status bar
        ch.enableVibration(false)
        nm.createNotificationChannel(ch)

        val oldLabel = Fmt.cardDate(LocalDate.ofEpochDay(old.day))
        val newLabel = Fmt.cardDate(LocalDate.ofEpochDay(today))
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val stay = PendingIntent.getService(
            this, 2, Intent(this, BubbleService::class.java).setAction(ACTION_STAY), flags
        )
        val change = PendingIntent.getService(
            this, 3, Intent(this, BubbleService::class.java).setAction(ACTION_SWITCH), flags
        )
        val icon = Icon.createWithResource(this, R.drawable.ic_bubble)
        val n = Notification.Builder(this, CHANNEL_DAY)
            .setSmallIcon(R.drawable.ic_bubble)
            .setContentTitle("Sudah ganti hari ($newLabel)")
            .setContentText("Bubble masih di counter $oldLabel")
            .setStyle(
                Notification.BigTextStyle().bigText(
                    "Bubble masih di counter $oldLabel.\n" +
                        "Tetap: klik dicatat di jam 23:00 tanggal $oldLabel.\n" +
                        "Ganti: pindah ke counter $newLabel (dibuat otomatis kalau belum ada)."
                )
            )
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(icon, "Tetap di $oldLabel", stay).build())
            .addAction(Notification.Action.Builder(icon, "Ganti ke $newLabel", change).build())
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIF_DAY_ID, n)
        Haptics.play(this, Haptics.DOUBLE)
    }

    /** Pilihan dari notifikasi: [switch] false = tetap di hari lama, true = pindah ke counter hari ini. */
    private fun chooseDay(switch: Boolean) {
        getSystemService(NotificationManager::class.java).cancel(NOTIF_DAY_ID)
        if (container == null) { // service baru dibuat oleh tombol ini, bubble tidak sedang aktif
            stopSelf()
            return
        }
        val old = db.counter(counterId)
        val oldLabel = if (old != null) Fmt.cardDate(LocalDate.ofEpochDay(old.day)) else ""
        if (!switch) {
            Toast.makeText(this, "Tetap di $oldLabel (klik dicatat jam 23:00)", Toast.LENGTH_SHORT).show()
            return
        }
        val today = Days.today().toEpochDay()
        var target = db.counterByDay(today)
        if (target == null) {
            val color = Palette.defaultColorFor(LocalDate.ofEpochDay(today))
            val id = db.addCounter(today, "", color, 0, false)
            target = if (id >= 0) db.counter(id) else db.counterByDay(today)
        }
        if (target == null) return
        counterId = target.id
        watchDay = today
        getSharedPreferences("prefs", MODE_PRIVATE).edit().putLong("bubble_id", counterId).apply()
        refresh()
        popBubble()
        db.notifyChanged()
        Toast.makeText(
            this, "Pindah ke ${Fmt.cardDate(LocalDate.ofEpochDay(today))}", Toast.LENGTH_SHORT
        ).show()
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Bubble counter", NotificationManager.IMPORTANCE_LOW)
        )
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val close = PendingIntent.getService(
            this, 1, Intent(this, BubbleService::class.java).setAction(ACTION_HIDE), flags
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_bubble)
            .setContentTitle("Sharelink Counter aktif")
            .setContentText("Ketuk = +1, tahan = tombol minus/reset, geser ke X = tutup")
            .setContentIntent(open)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_bubble), "Tutup", close
                ).build()
            )
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }
}
