package com.chaman.store

import android.app.Activity
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

object Config {
    // apps.json ka raw link. Repo ka naam alag ho to yahan badlo.
    const val APPS_URL = "https://raw.githubusercontent.com/CHAMANRK/chaman-store/main/apps.json"
}

data class AppItem(
    val name: String,
    val pkg: String,
    val versionName: String,
    val versionCode: Long,
    val description: String,
    val apkUrl: String,
    val iconUrl: String
)

class MainActivity : Activity() {

    private val BG = Color.parseColor("#101010")
    private val CARD = Color.parseColor("#1C1C1C")
    private val TXT = Color.WHITE
    private val SEC = Color.parseColor("#A0A0A0")
    private val BORDER = Color.parseColor("#303030")

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var list: LinearLayout
    private lateinit var subtitle: TextView

    private var apps: List<AppItem> = emptyList()
    private var statusMsg: String? = "Loading…"
    private val downloading = mutableSetOf<String>()
    private val downloads = mutableMapOf<Long, AppItem>()
    private val iconCache = ConcurrentHashMap<String, Bitmap>()

    private val doneReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val id = i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            val app = downloads.remove(id) ?: return
            downloading.remove(app.pkg)
            val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            val cur = dm.query(DownloadManager.Query().setFilterById(id))
            var ok = false
            if (cur.moveToFirst()) {
                val st = cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                ok = st == DownloadManager.STATUS_SUCCESSFUL
            }
            cur.close()
            render()
            if (ok) install(apkFile(app)) else toast("Download fail hua: ${app.name}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(20), dp(24), dp(20), 0)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleCol.addView(tv("Chaman Store", 28f, TXT, true))
        subtitle = tv("", 13f, SEC, false)
        titleCol.addView(subtitle)
        header.addView(titleCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val refresh = tv("Refresh", 13f, TXT, true).apply {
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = round(Color.TRANSPARENT, 20, BORDER)
            setOnClickListener { load() }
        }
        header.addView(refresh)
        root.addView(header)

        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(20), 0, dp(24))
        }
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(list)
        }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        val f = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(doneReceiver, f, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(doneReceiver, f)
        }
        render()
        load()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(doneReceiver)
    }

    // ---------- data ----------
    private fun load() {
        statusMsg = "Loading…"
        if (apps.isEmpty()) render()
        thread {
            try {
                val c = URL(Config.APPS_URL + "?t=" + System.currentTimeMillis()).openConnection() as HttpURLConnection
                c.connectTimeout = 10000
                c.readTimeout = 10000
                val txt = c.inputStream.bufferedReader().use { it.readText() }
                val arr = JSONObject(txt).getJSONArray("apps")
                val out = ArrayList<AppItem>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    out.add(
                        AppItem(
                            o.getString("name"),
                            o.getString("package"),
                            o.optString("versionName", "1.0"),
                            o.optLong("versionCode", 1),
                            o.optString("description", ""),
                            o.getString("apkUrl"),
                            o.optString("iconUrl", "")
                        )
                    )
                }
                ui.post {
                    apps = out
                    statusMsg = if (out.isEmpty()) "Abhi koi app nahi hai." else null
                    render()
                }
            } catch (e: Exception) {
                ui.post {
                    if (apps.isEmpty()) statusMsg = "Apps load nahi hue. Internet check karo, phir Refresh dabao."
                    render()
                }
            }
        }
    }

    // ---------- install logic ----------
    private fun installedCode(pkg: String): Long? = try {
        val p = packageManager.getPackageInfo(pkg, 0)
        if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else @Suppress("DEPRECATION") p.versionCode.toLong()
    } catch (e: Exception) {
        null
    }

    private fun apkFile(a: AppItem) =
        File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "${a.pkg}.apk")

    private fun canInstall(): Boolean {
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            toast("'Install unknown apps' allow karo, phir wapas aake dobara dabao")
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return false
        }
        return true
    }

    private fun startDownload(a: AppItem) {
        if (!canInstall()) return
        val f = apkFile(a)
        if (f.exists()) f.delete()
        val req = DownloadManager.Request(Uri.parse(a.apkUrl))
            .setTitle(a.name)
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, "${a.pkg}.apk")
        val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        val id = dm.enqueue(req)
        downloads[id] = a
        downloading.add(a.pkg)
        render()
    }

    private fun install(f: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            val i = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        } catch (e: Exception) {
            toast("Install nahi khul paya")
        }
    }

    private fun openApp(a: AppItem) {
        val i = packageManager.getLaunchIntentForPackage(a.pkg)
        if (i != null) startActivity(i) else toast("App open nahi ho paya")
    }

    // ---------- UI ----------
    private fun render() {
        list.removeAllViews()
        subtitle.text = if (apps.isEmpty()) "Apne apps, ek jagah" else "${apps.size} apps"
        if (apps.isEmpty()) {
            list.addView(tv(statusMsg ?: "", 14f, SEC, false).apply { setPadding(0, dp(24), 0, 0) })
            return
        }
        for (a in apps) list.addView(card(a))
    }

    private fun card(a: AppItem): View {
        val code = installedCode(a.pkg)
        val isDownloading = downloading.contains(a.pkg)
        val hasUpdate = code != null && code < a.versionCode

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = round(CARD, 18, BORDER)
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = dp(12)
        row.layoutParams = lp

        // icon
        val iconBox = FrameLayout(this).apply {
            background = round(Color.parseColor("#2A2A2A"), 14, null)
            clipToOutline = true
        }
        iconBox.addView(
            tv(a.name.take(1).uppercase(), 20f, TXT, true).apply { gravity = Gravity.CENTER },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        if (a.iconUrl.isNotEmpty()) {
            val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            iconBox.addView(iv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            loadIcon(a.iconUrl, iv)
        }
        row.addView(iconBox, LinearLayout.LayoutParams(dp(52), dp(52)))

        // text
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(tv(a.name, 16f, TXT, true))
        col.addView(tv(if (hasUpdate) "v${a.versionName} • update available" else "v${a.versionName}", 12f, SEC, false))
        if (a.description.isNotEmpty()) {
            col.addView(tv(a.description, 12f, SEC, false).apply {
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
        }
        val tlp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        tlp.leftMargin = dp(12)
        tlp.rightMargin = dp(8)
        row.addView(col, tlp)

        // button
        val label = when {
            isDownloading -> "Downloading…"
            code == null -> "Install"
            hasUpdate -> "Update"
            else -> "Open"
        }
        val btn = tv(label, 13f, Color.parseColor("#111111"), true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(9), dp(16), dp(9))
            when (label) {
                "Open" -> { setTextColor(TXT); background = round(Color.TRANSPARENT, 20, TXT) }
                "Downloading…" -> { setTextColor(SEC); background = round(BORDER, 20, null) }
                else -> background = round(Color.WHITE, 20, null)
            }
            setOnClickListener {
                when (label) {
                    "Open" -> openApp(a)
                    "Downloading…" -> {}
                    else -> startDownload(a)
                }
            }
        }
        row.addView(btn)
        return row
    }

    private fun loadIcon(url: String, iv: ImageView) {
        iconCache[url]?.let { iv.setImageBitmap(it); return }
        thread {
            try {
                val bm = BitmapFactory.decodeStream(URL(url).openStream())
                if (bm != null) {
                    iconCache[url] = bm
                    ui.post { iv.setImageBitmap(bm) }
                }
            } catch (e: Exception) { }
        }
    }

    private fun tv(t: String, sp: Float, color: Int, bold: Boolean) = TextView(this).apply {
        text = t
        textSize = sp
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun round(fill: Int, radiusDp: Int, stroke: Int?) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radiusDp).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()
}
