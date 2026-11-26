package com.example.cdtracker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class OverlayService : Service() {

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val N = 5               // raqiblar soni
        private const val ICONS = N * 2       // har raqib: F (spell) va U (ult)
        private const val DIM_RATIO = 0.65    // yorqinlik baza'ning shu qismidan pastga tushsa = CDda
        private const val SAMPLE_MS = 400L
    }

    private lateinit var wm: WindowManager
    private lateinit var root: LinearLayout
    private lateinit var params: WindowManager.LayoutParams
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("cd", MODE_PRIVATE) }

    // ---- Taymer sozlamalari (soniya) ----
    private val flickerBase = 120
    private val ultBases = intArrayOf(40, 60, 80, 100, 120)
    private var cdr = 0
    private val ultIdx = IntArray(N) { 1 }
    private val ends = Array(N) { LongArray(2) }
    private val btns = Array(N) { arrayOfNulls<Button>(2) }
    private lateinit var cdrBtn: Button
    private lateinit var kalibBtn: Button
    private lateinit var autoBtn: Button
    private lateinit var dbg: TextView

    // ---- Ekran yozish ----
    private var projection: MediaProjection? = null
    private var vd: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var capW = 0
    private var capH = 0
    private val bgThread = HandlerThread("cd-capture").also { it.start() }
    private val bg = Handler(bgThread.looper)
    @Volatile private var pts: IntArray? = null
    private var lastSample = 0L

    // ---- Aniqlash holati ----
    private var calibrating = false
    private var auto = false
    private var lastLuma: DoubleArray? = null
    private var baseline: DoubleArray? = null
    private val dimStreak = IntArray(ICONS)
    private val brightStreak = IntArray(ICONS)
    private val armed = BooleanArray(ICONS) { true }

    // ---- Kalibrovka belgilari (10 ta kichik oyna) ----
    private val markers = ArrayList<TextView>()
    private val markerParams = ArrayList<WindowManager.LayoutParams>()
    private var markersShown = false
    private val markerSize by lazy { dp(22) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        buildPanel()
        buildMarkers()
        loadSavedPoints()
        handler.post(tick)
    }

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (projection == null) {
            val code = intent?.getIntExtra(EXTRA_CODE, Int.MIN_VALUE) ?: Int.MIN_VALUE
            val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
            } else {
                intent?.getParcelableExtra(EXTRA_DATA)
            }
            if (code != -1 || data == null) { // -1 = RESULT_OK
                stopSelf()
                return START_NOT_STICKY
            }
            startCapture(code, data)
        }
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val chId = "overlay"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(chId, "Overlay", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, chId)
            .setContentTitle("CD taymer ishlayapti")
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, n)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // =====================================================================
    //  EKRAN YOZISH (MediaProjection)
    // =====================================================================

    private fun screenSize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            Pair(b.width(), b.height())
        } else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(m)
            Pair(m.widthPixels, m.heightPixels)
        }
    }

    private fun makeReader(w: Int, h: Int): ImageReader {
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        r.setOnImageAvailableListener({ rd -> onFrame(rd) }, bg)
        return r
    }

    private fun startCapture(code: Int, data: Intent) {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val p = mpm.getMediaProjection(code, data)
        projection = p
        // Android 14+: createVirtualDisplay'dan oldin callback shart
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                handler.post { stopSelf() }
            }
        }, handler)
        val (w, h) = screenSize()
        capW = w; capH = h
        val r = makeReader(w, h)
        reader = r
        vd = p.createVirtualDisplay(
            "cd-capture", w, h, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null
        )
    }

    /** Ekran aylansa (portret <-> landshaft) capture o'lchamini yangilaydi. */
    private fun ensureSize() {
        if (projection == null || vd == null) return
        val (w, h) = screenSize()
        if (w == capW && h == capH) return
        val old = reader
        val r = makeReader(w, h)
        reader = r
        vd?.surface = r.surface
        vd?.resize(w, h, resources.displayMetrics.densityDpi)
        capW = w; capH = h
        old?.close()
    }

    /** Fon oqimida: 10 ta nuqta atrofidagi o'rtacha yorqinlikni o'lchaydi. */
    private fun onFrame(rd: ImageReader) {
        val img: Image? = try { rd.acquireLatestImage() } catch (e: Exception) { null }
        if (img == null) return
        try {
            val now = SystemClock.uptimeMillis()
            val p = pts
            if (p == null || now - lastSample < SAMPLE_MS) return
            lastSample = now

            val plane = img.planes[0]
            val buf = plane.buffer
            val rs = plane.rowStride
            val ps = plane.pixelStride
            val w = img.width
            val h = img.height
            val step = dp(2).coerceAtLeast(1)
            val out = DoubleArray(ICONS)
            for (idx in 0 until ICONS) {
                val cx = p[idx * 2]
                val cy = p[idx * 2 + 1]
                var sum = 0.0
                var cnt = 0
                for (dy in -3..3) for (dx in -3..3) {
                    val x = (cx + dx * step).coerceIn(0, w - 1)
                    val y = (cy + dy * step).coerceIn(0, h - 1)
                    val o = y * rs + x * ps
                    val r = buf.get(o).toInt() and 0xff
                    val g = buf.get(o + 1).toInt() and 0xff
                    val b = buf.get(o + 2).toInt() and 0xff
                    sum += 0.299 * r + 0.587 * g + 0.114 * b
                    cnt++
                }
                out[idx] = sum / cnt
            }
            handler.post { onLuma(out) }
        } finally {
            img.close()
        }
    }

    // =====================================================================
    //  ANIQLASH
    // =====================================================================

    private fun onLuma(l: DoubleArray) {
        lastLuma = l
        val base = baseline ?: return
        if (!auto || calibrating) return

        val ratio = DoubleArray(ICONS) { if (base[it] > 8.0) l[it] / base[it] else 1.0 }
        val dimNow = BooleanArray(ICONS) { ratio[it] < DIM_RATIO }
        updateDebug(ratio)

        // Deyarli hamma nuqta birdan qorayib ketsa: scoreboard yopilgan yoki sahna o'zgargan
        if (dimNow.count { it } >= 7) {
            dimStreak.fill(0)
            return
        }

        val now = System.currentTimeMillis()
        for (idx in 0 until ICONS) {
            if (dimNow[idx]) {
                dimStreak[idx]++
                brightStreak[idx] = 0
            } else {
                brightStreak[idx]++
                dimStreak[idx] = 0
                if (brightStreak[idx] >= 2) armed[idx] = true
            }
            if (armed[idx] && dimStreak[idx] >= 2) {
                armed[idx] = false
                val i = idx / 2
                val k = idx % 2
                if (ends[i][k] == 0L) {
                    ends[i][k] = now + duration(i, k) * 1000L
                    refresh(i, k, now)
                }
            }
        }
    }

    private fun updateDebug(ratio: DoubleArray) {
        val sb = StringBuilder()
        for (i in 0 until N) {
            sb.append("${i + 1}: F${(ratio[i * 2] * 100).toInt()} U${(ratio[i * 2 + 1] * 100).toInt()}")
            if (i < N - 1) sb.append("\n")
        }
        dbg.text = sb.toString()
    }

    // =====================================================================
    //  KALIBROVKA
    // =====================================================================

    private fun buildMarkers() {
        for (idx in 0 until ICONS) {
            val tv = TextView(this).apply {
                text = "${idx / 2 + 1}${if (idx % 2 == 0) "F" else "U"}"
                textSize = 8f
                setTextColor(Color.BLACK)
                gravity = Gravity.CENTER
                setBackgroundColor(
                    if (idx % 2 == 0) Color.argb(150, 255, 235, 59) else Color.argb(150, 0, 229, 255)
                )
            }
            val lp = WindowManager.LayoutParams(
                markerSize, markerSize,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = prefs.getInt("x$idx", dp(400 + (idx % 2) * 40))
                y = prefs.getInt("y$idx", dp(60 + (idx / 2) * 32))
                if (Build.VERSION.SDK_INT >= 28) {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            var sx = 0; var sy = 0; var tx = 0f; var ty = 0f
            tv.setOnTouchListener { _: View, e: MotionEvent ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { sx = lp.x; sy = lp.y; tx = e.rawX; ty = e.rawY }
                    MotionEvent.ACTION_MOVE -> {
                        lp.x = sx + (e.rawX - tx).toInt()
                        lp.y = sy + (e.rawY - ty).toInt()
                        wm.updateViewLayout(tv, lp)
                    }
                }
                true
            }
            markers.add(tv)
            markerParams.add(lp)
        }
    }

    private fun showMarkers() {
        if (markersShown) return
        for (i in 0 until ICONS) runCatching { wm.addView(markers[i], markerParams[i]) }
        markersShown = true
    }

    private fun hideMarkers() {
        if (!markersShown) return
        for (i in 0 until ICONS) runCatching { wm.removeView(markers[i]) }
        markersShown = false
    }

    private fun loadSavedPoints() {
        if (prefs.contains("x0")) pts = computePoints()
    }

    private fun computePoints(): IntArray {
        val half = markerSize / 2
        val p = IntArray(ICONS * 2)
        for (i in 0 until ICONS) {
            p[i * 2] = markerParams[i].x + half
            p[i * 2 + 1] = markerParams[i].y + half
        }
        return p
    }

    private fun toggleCalibration() {
        if (!calibrating) {
            calibrating = true
            auto = false
            autoBtn.text = "Auto: OFF"
            dbg.text = ""
            baseline = null
            kalibBtn.text = "Saqla"
            showMarkers()
            Toast.makeText(
                this,
                "Scoreboard'ni och, sariq/ko'k kataklarni raqib ikonkalari ustiga qo'y (F=spell, U=ult). Hammasi tayyor (CDda emas) bo'lsin.",
                Toast.LENGTH_LONG
            ).show()
        } else {
            calibrating = false
            kalibBtn.text = "Kalib"
            val p = computePoints()
            val ed = prefs.edit()
            for (i in 0 until ICONS) {
                ed.putInt("x$i", markerParams[i].x)
                ed.putInt("y$i", markerParams[i].y)
            }
            ed.apply()
            pts = p
            hideMarkers()
            Toast.makeText(this, "Baza olinmoqda...", Toast.LENGTH_SHORT).show()
            lastLuma = null
            // belgilar yo'qolgandan keyingi kadrlardan baza olamiz
            handler.postDelayed({
                val l = lastLuma
                if (l != null) {
                    baseline = l.copyOf()
                    Toast.makeText(this, "Baza olindi. Endi Auto'ni yoq.", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Kadr kelmadi. Qaytadan Kalib qil.", Toast.LENGTH_LONG).show()
                }
            }, 1200)
        }
    }

    private fun toggleAuto() {
        if (baseline == null) {
            Toast.makeText(this, "Avval Kalib qilib, Saqla bos", Toast.LENGTH_SHORT).show()
            return
        }
        auto = !auto
        autoBtn.text = if (auto) "Auto: ON" else "Auto: OFF"
        dimStreak.fill(0)
        brightStreak.fill(0)
        armed.fill(true)
        if (!auto) dbg.text = ""
    }

    // =====================================================================
    //  PANEL
    // =====================================================================

    private fun buildPanel() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(170, 0, 0, 0))
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }

        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val drag = TextView(this).apply {
            text = "☰ CD"
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(dp(8), dp(6), dp(12), dp(6))
        }
        cdrBtn = smallButton("CDR 0%") {
            cdr = if (cdr >= 40) 0 else cdr + 10
            cdrBtn.text = "CDR $cdr%"
        }
        val close = smallButton("✕") { stopSelf() }
        header.addView(drag)
        header.addView(cdrBtn)
        header.addView(close)
        root.addView(header)

        val header2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        kalibBtn = smallButton("Kalib") { toggleCalibration() }
        autoBtn = smallButton("Auto: OFF") { toggleAuto() }
        header2.addView(kalibBtn)
        header2.addView(autoBtn)
        root.addView(header2)

        for (i in 0 until N) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val label = TextView(this).apply {
                text = "${i + 1}"
                setTextColor(Color.WHITE)
                textSize = 14f
                setPadding(dp(6), 0, dp(6), 0)
                gravity = Gravity.CENTER_VERTICAL
            }
            val f = smallButton("F") { toggle(i, 0) }
            val u = smallButton("U") { toggle(i, 1) }
            u.setOnLongClickListener {
                ultIdx[i] = (ultIdx[i] + 1) % ultBases.size
                if (ends[i][1] == 0L) refresh(i, 1, System.currentTimeMillis())
                true
            }
            btns[i][0] = f
            btns[i][1] = u
            row.addView(label)
            row.addView(f)
            row.addView(u)
            root.addView(row)
            refresh(i, 0, 0L)
            refresh(i, 1, 0L)
        }

        dbg = TextView(this).apply {
            setTextColor(Color.LTGRAY)
            textSize = 10f
            setPadding(dp(6), dp(2), dp(6), 0)
        }
        root.addView(dbg)

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(8)
            y = dp(120)
        }

        var startX = 0; var startY = 0; var touchX = 0f; var touchY = 0f
        drag.setOnTouchListener { _: View, e: MotionEvent ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y
                    touchX = e.rawX; touchY = e.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (e.rawX - touchX).toInt()
                    params.y = startY + (e.rawY - touchY).toInt()
                    wm.updateViewLayout(root, params)
                }
            }
            true
        }

        wm.addView(root, params)
    }

    private fun smallButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 12f
        isAllCaps = false
        minWidth = 0; minimumWidth = 0
        minHeight = 0; minimumHeight = 0
        setPadding(dp(8), dp(4), dp(8), dp(4))
        setOnClickListener { onClick() }
    }

    private fun duration(i: Int, kind: Int): Int {
        val base = if (kind == 0) flickerBase else ultBases[ultIdx[i]]
        return (base * (100 - cdr) / 100.0).toInt()
    }

    private fun toggle(i: Int, kind: Int) {
        val now = System.currentTimeMillis()
        ends[i][kind] = if (ends[i][kind] == 0L) now + duration(i, kind) * 1000L else 0L
        refresh(i, kind, now)
    }

    private fun refresh(i: Int, kind: Int, now: Long) {
        val b = btns[i][kind] ?: return
        val name = if (kind == 0) "F" else "U"
        val end = ends[i][kind]
        if (end == 0L) {
            val extra = if (kind == 1) " (${duration(i, 1)})" else ""
            b.text = "$name ✓$extra"
            b.setBackgroundColor(Color.rgb(46, 125, 50))
            b.setTextColor(Color.WHITE)
        } else {
            val left = ((end - now) / 1000).toInt()
            if (left <= 0) {
                ends[i][kind] = 0L
                refresh(i, kind, now)
                return
            }
            b.text = "$name $left"
            b.setBackgroundColor(Color.rgb(198, 40, 40))
            b.setTextColor(Color.WHITE)
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            for (i in 0 until N) for (k in 0..1) {
                if (ends[i][k] != 0L) refresh(i, k, now)
            }
            runCatching { ensureSize() }
            handler.postDelayed(this, 500)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        hideMarkers()
        if (::root.isInitialized) runCatching { wm.removeView(root) }
        runCatching { vd?.release() }
        runCatching { reader?.close() }
        runCatching { projection?.stop() }
        bgThread.quitSafely()
        super.onDestroy()
    }
}
