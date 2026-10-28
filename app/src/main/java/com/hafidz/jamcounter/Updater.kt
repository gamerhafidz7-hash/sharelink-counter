package com.hafidz.jamcounter

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Pembaruan dari dalam aplikasi: cek rilis terbaru di GitHub Releases (repo publik), unduh APK,
 * lalu pasang lewat PackageInstaller. Nomor versi = angka pada tag rilis (mis. "v12" = 12)
 * dibandingkan dengan versionCode aplikasi. Workflow GitHub mengisi keduanya dengan nomor run.
 */
object Updater {
    class Release(val code: Long, val tag: String, val url: String, val notes: String)

    private const val ACTION_RESULT = "com.hafidz.jamcounter.INSTALL_RESULT"

    @Suppress("DEPRECATION")
    fun currentCode(ctx: Context): Long {
        val i = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) i.longVersionCode else i.versionCode.toLong()
    }

    fun currentName(ctx: Context): String =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"

    /** Cek rilis terbaru. [onDone] di UI thread: (rilis lebih baru atau null, pesan galat atau null). */
    fun check(act: Activity, repo: String, onDone: (Release?, String?) -> Unit) {
        Thread {
            var rel: Release? = null
            var err: String? = null
            try {
                val conn = URL("https://api.github.com/repos/$repo/releases/latest")
                    .openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.setRequestProperty("User-Agent", "FloatingCounter")
                if (conn.responseCode != 200) {
                    err = "Repo atau rilis tidak ditemukan (HTTP ${conn.responseCode})"
                } else {
                    val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    val tag = json.optString("tag_name")
                    val code = tag.filter { it.isDigit() }.toLongOrNull() ?: 0L
                    var url = ""
                    val assets = json.optJSONArray("assets")
                    if (assets != null) {
                        for (i in 0 until assets.length()) {
                            val a = assets.getJSONObject(i)
                            if (a.optString("name").endsWith(".apk")) {
                                url = a.optString("browser_download_url")
                                break
                            }
                        }
                    }
                    if (url.isEmpty()) {
                        err = "Rilis tidak berisi file APK"
                    } else if (code > currentCode(act)) {
                        rel = Release(code, tag, url, json.optString("body"))
                    }
                }
            } catch (e: Exception) {
                err = "Gagal cek pembaruan: ${e.message ?: e.javaClass.simpleName}"
            }
            act.runOnUiThread { onDone(rel, err) }
        }.start()
    }

    /** Cek otomatis (maks. sekali per 6 jam) saat aplikasi dibuka; diam kalau tidak ada yang baru. */
    fun autoCheck(act: Activity) {
        val repo = Cfg.repo(act)
        if (repo.isBlank() || !Cfg.autoUpdate(act)) return
        val p = Cfg.prefs(act)
        val now = System.currentTimeMillis()
        if (now - p.getLong("last_check", 0L) < 6L * 3600L * 1000L) return
        p.edit().putLong("last_check", now).apply()
        check(act, repo) { rel, _ -> if (rel != null && !act.isFinishing) promptInstall(act, rel) }
    }

    fun promptInstall(act: Activity, rel: Release) {
        val notes = rel.notes.trim().take(300)
        AlertDialog.Builder(act)
            .setTitle("Versi baru ${rel.tag}")
            .setMessage(
                "Terpasang: ${currentName(act)} (kode ${currentCode(act)})\n" +
                    "Tersedia: kode ${rel.code}" + if (notes.isNotEmpty()) "\n\n$notes" else ""
            )
            .setPositiveButton("Perbarui") { _, _ -> startInstall(act, rel) }
            .setNegativeButton("Nanti", null)
            .show()
    }

    private fun startInstall(act: Activity, rel: Release) {
        if (!act.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(
                act, "Izinkan \"Pasang aplikasi tidak dikenal\" untuk aplikasi ini, lalu ulangi", Toast.LENGTH_LONG
            ).show()
            act.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${act.packageName}"))
            )
            return
        }
        val bar = ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal)
        bar.max = 100
        val label = TextView(act)
        label.text = "Mengunduh…"
        val box = LinearLayout(act)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(act.dp(20), act.dp(12), act.dp(20), act.dp(8))
        box.addView(label)
        box.addView(
            bar,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        val dlg = AlertDialog.Builder(act).setTitle("Memperbarui").setView(box).setCancelable(false).create()
        dlg.show()

        Thread {
            try {
                val installer = act.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                params.setAppPackageName(act.packageName)
                val id = installer.createSession(params)
                val session = installer.openSession(id)
                try {
                    val conn = URL(rel.url).openConnection() as HttpURLConnection
                    conn.connectTimeout = 15000
                    conn.readTimeout = 30000
                    val total = conn.contentLengthLong
                    session.openWrite("update.apk", 0, -1).use { out ->
                        conn.inputStream.use { input ->
                            val buf = ByteArray(32768)
                            var done = 0L
                            var n = 0
                            var last = -1
                            while (input.read(buf).also { n = it } >= 0) {
                                out.write(buf, 0, n)
                                done += n
                                if (total > 0) {
                                    val pct = (done * 100 / total).toInt()
                                    if (pct != last) {
                                        last = pct
                                        act.runOnUiThread {
                                            bar.progress = pct
                                            label.text = "Mengunduh… $pct%"
                                        }
                                    }
                                }
                            }
                        }
                        session.fsync(out)
                    }
                } catch (e: Exception) {
                    session.abandon()
                    throw e
                }
                act.runOnUiThread { label.text = "Menyiapkan pemasangan…" }
                val intent = Intent(act, InstallResultReceiver::class.java)
                    .setAction(ACTION_RESULT)
                    .setPackage(act.packageName)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                session.commit(PendingIntent.getBroadcast(act, id, intent, flags).intentSender)
                session.close()
                act.runOnUiThread { dlg.dismiss() }
            } catch (e: Exception) {
                act.runOnUiThread {
                    dlg.dismiss()
                    Toast.makeText(
                        act, "Gagal memperbarui: ${e.message ?: e.javaClass.simpleName}", Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }
}

/** Menerima hasil PackageInstaller: minta konfirmasi sistem, atau tampilkan galat. */
class InstallResultReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(confirm)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {}
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "tidak diketahui"
                Toast.makeText(context, "Pemasangan gagal: $msg", Toast.LENGTH_LONG).show()
            }
        }
    }
}
