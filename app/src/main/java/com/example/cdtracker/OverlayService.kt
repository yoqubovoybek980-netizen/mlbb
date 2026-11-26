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
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class OverlayService : Service() {

    companion object { private const val N = 5 }

    private lateinit var wm: WindowManager
    private lateinit var root: LinearLayout
    private lateinit var params: WindowManager.LayoutParams
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("cd", MODE_PRIVATE) }
    private lateinit var heroDb: Map<String, Data.Hero>
    private lateinit var spellDb: Map<String, Int>

    private var cdr = 0
    private val names = Array(N) { "" }
    private val spellNames = Array(N) { "Flicker" }
    private val spellCd = IntArray(N) { 120 }
    private val ultCds = Array(N) { intArrayOf(60, 60, 60) }
    private val ultKnown = BooleanArray(N)
    private val ultApprox = BooleanArray(N)
    private val ultNone = BooleanArray(N)
    private val ultEarly = BooleanArray(N)
    private val lvl = IntArray(N) { 1 }
    private val ends = Array(N) { LongArray(2) }   // [0]=ult, [1]=spell

    private val nameBtns = arrayOfNulls<Button>(N)
    private val lvViews = arrayOfNulls<TextView>(N)
    private val btns = Array(N) { arrayOfNulls<Button>(2) }
    private lateinit var cdrBtn: Button
    private var popup: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        heroDb = Data.heroes(this)
        spellDb = Data.spells(this)
        buildPanel()
        for (i in 0 until N) {
            setHero(
                i,
                prefs.getString("hero$i", "") ?: "",
                prefs.getString("spell$i", "Flicker") ?: "Flicker"
            )
        }
        handler.post(tick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
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
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ---------------- Qahramon / spell tanlash ----------------

    private fun setHero(i: Int, n: String, s: String) {
        names[i] = n
        spellNames[i] = s
        spellCd[i] = spellDb[s] ?: 120

        // 1) o'zing kiritgan (saqlangan) CD  2) bazadagi CD  3) noma'lum
        val saved = prefs.getString("cd_$n", null)?.split(",")?.mapNotNull { it.trim().toIntOrNull() }
        val mine = saved?.takeIf { it.size == 3 }?.toIntArray()
        val h = heroDb.entries.firstOrNull { it.key.equals(n, ignoreCase = true) }?.value
        ultApprox[i] = false
        ultNone[i] = false
        ultEarly[i] = h?.early ?: false
        when {
            mine != null -> { ultCds[i] = mine; ultKnown[i] = true }
            h != null && !h.hasUlt -> { ultCds[i] = IntArray(3) { 60 }; ultKnown[i] = true; ultNone[i] = true }
            h != null -> { ultCds[i] = h.cds.copyOf(); ultKnown[i] = true; ultApprox[i] = h.approx }
            else -> { ultCds[i] = IntArray(3) { 60 }; ultKnown[i] = false }
        }
        lvl[i] = 1
        ends[i][0] = 0L
        ends[i][1] = 0L
        nameBtns[i]?.text = if (n.isEmpty()) "Raqib ${i + 1}" else n
        updateLv(i)
        refresh(i, 1, System.currentTimeMillis())
    }

    private fun pickHero(i: Int, n: String) {
        setHero(i, n, spellNames[i])
        prefs.edit().putString("hero$i", n).apply()
        closePopup()
    }

    private fun pickSpell(i: Int, s: String) {
        spellNames[i] = s
        spellCd[i] = spellDb[s] ?: 120
        ends[i][1] = 0L
        refresh(i, 1, System.currentTimeMillis())
        prefs.edit().putString("spell$i", s).apply()
        closePopup()
    }

    // ---------------- Hisob-kitob ----------------

    /** 0 = ult hali ochilmagan (level < 4); 1 = 4-7; 2 = 8-11; 3 = 12+ */
    private fun ultStage(i: Int): Int {
        if (ultNone[i]) return 0
        return when {
            lvl[i] >= 12 -> 3
            lvl[i] >= 8 -> 2
            lvl[i] >= 4 -> 1
            ultEarly[i] -> 1       // Julian, Suyou: ult 1-levelda ochiq
            else -> 0
        }
    }

    private fun duration(i: Int, kind: Int): Int {
        val base = if (kind == 0) {
            val st = ultStage(i)
            ultCds[i][(if (st == 0) 1 else st) - 1]
        } else spellCd[i]
        return (base * (100 - cdr) / 100.0).toInt()
    }

    private fun toggle(i: Int, kind: Int) {
        if (kind == 0 && ultStage(i) == 0) {
            Toast.makeText(
                this,
                if (ultNone[i]) "Bu qahramonda ult yo'q" else "Ult 4-levelda ochiladi",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        val now = System.currentTimeMillis()
        ends[i][kind] = if (ends[i][kind] == 0L) now + duration(i, kind) * 1000L else 0L
        refresh(i, kind, now)
    }

    private fun changeLevel(i: Int, d: Int) {
        lvl[i] = (lvl[i] + d).coerceIn(1, 15)
        updateLv(i)
    }

    private fun updateLv(i: Int) {
        lvViews[i]?.text = "Lv ${lvl[i]}"
        refresh(i, 0, System.currentTimeMillis())
    }

    private fun refresh(i: Int, kind: Int, now: Long) {
        val b = btns[i][kind] ?: return
        val end = ends[i][kind]
        val tag = if (kind == 0) "U" else spellNames[i].take(3)

        if (kind == 0 && ultStage(i) == 0) {
            b.text = "U —"
            b.setBackgroundColor(Color.rgb(97, 97, 97))
            b.setTextColor(Color.WHITE)
            return
        }
        if (end == 0L) {
            val q = if (kind != 0) "" else if (!ultKnown[i]) "?" else if (ultApprox[i]) "~" else ""
            b.text = "$tag ✓${duration(i, kind)}$q"
            b.setBackgroundColor(Color.rgb(46, 125, 50))
            b.setTextColor(Color.WHITE)
        } else {
            val left = ((end - now) / 1000).toInt()
            if (left <= 0) {
                ends[i][kind] = 0L
                refresh(i, kind, now)
                return
            }
            b.text = "$tag $left"
            b.setBackgroundColor(Color.rgb(198, 40, 40))
            b.setTextColor(Color.WHITE)
        }
    }

    // ---------------- Oynalar (popup) ----------------

    private fun closePopup() {
        popup?.let { runCatching { wm.removeView(it) } }
        popup = null
    }

    private fun showPopup(v: View, w: Int, h: Int, focusable: Boolean = false) {
        closePopup()
        val lp = WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            if (focusable) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = params.x
            y = params.y
            if (focusable) softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        }
        wm.addView(v, lp)
        popup = v
    }

    private fun popupBox(title: String): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(240, 25, 25, 25))
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        r.addView(TextView(this).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(dp(6), 0, dp(6), 0)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        r.addView(smallButton("✕") { closePopup() })
        box.addView(r)
        return box
    }

    private fun grid(items: List<String>, onPick: (String) -> Unit): ScrollView {
        val sv = ScrollView(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (chunk in items.chunked(3)) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = 3f
            }
            for (name in chunk) {
                val b = smallButton(name) { onPick(name) }.apply {
                    isSingleLine = true
                    ellipsize = TextUtils.TruncateAt.END
                }
                row.addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
            col.addView(row)
        }
        sv.addView(col)
        return sv
    }

    private fun norm(t: String) = t.lowercase().filter { it.isLetterOrDigit() }

    private fun showHeroPicker(i: Int) {
        val box = popupBox("${i + 1}-raqib: qahramonni tanla")
        val all = listOf("Boshqa") + heroDb.keys.sorted()
        val holder = FrameLayout(this)

        fun render(q: String) {
            holder.removeAllViews()
            val key = norm(q)
            val items = (if (key.isEmpty()) all else all.filter { norm(it).contains(key) })
                .ifEmpty { listOf("Boshqa") }
            holder.addView(grid(items) { pickHero(i, it) })
        }

        val search = EditText(this).apply {
            hint = "Qidirish (nomini yoz)..."
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            textSize = 13f
            isSingleLine = true
            setBackgroundColor(Color.rgb(55, 55, 55))
            setPadding(dp(8), dp(6), dp(8), dp(6))
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_ACTION_DONE
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { render(s?.toString() ?: "") }
            })
        }

        box.addView(search, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        box.addView(holder, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        render("")
        showPopup(box, dp(330), dp(260), focusable = true)
    }

    private fun showSpellPicker(i: Int) {
        val box = popupBox("${i + 1}-raqib: battle spell")
        box.addView(grid(spellDb.keys.toList()) { pickSpell(i, it) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        showPopup(box, dp(330), dp(190))
    }

    /** Ult CD ni 3 level uchun +/- bilan kiritish. Qahramon nomi bilan telefonga saqlanadi. */
    private fun showCdEditor(i: Int) {
        val vals = ultCds[i].copyOf()
        val label = if (names[i].isEmpty()) "Raqib ${i + 1}" else names[i]
        val box = popupBox("$label: ult CD (soniya)")
        val valueViews = ArrayList<TextView>()

        for (k in 0..2) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(TextView(this).apply {
                text = "Ult ${k + 1}"
                setTextColor(Color.WHITE)
                textSize = 13f
                setPadding(dp(6), 0, dp(6), 0)
            }, LinearLayout.LayoutParams(dp(52), LinearLayout.LayoutParams.WRAP_CONTENT))
            val v = TextView(this).apply {
                text = "${vals[k]}"
                setTextColor(Color.WHITE)
                textSize = 14f
                gravity = Gravity.CENTER
            }
            valueViews.add(v)
            fun step(d: Int) {
                vals[k] = (vals[k] + d).coerceIn(1, 300)
                v.text = "${vals[k]}"
            }
            row.addView(smallButton("−5") { step(-5) })
            row.addView(smallButton("−1") { step(-1) })
            row.addView(v, LinearLayout.LayoutParams(dp(44), LinearLayout.LayoutParams.WRAP_CONTENT))
            row.addView(smallButton("+1") { step(1) })
            row.addView(smallButton("+5") { step(5) })
            box.addView(row)
        }

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(smallButton("Hammasi = Ult 1") {
            for (k in 1..2) vals[k] = vals[0]
            for (k in 0..2) valueViews[k].text = "${vals[k]}"
        })
        actions.addView(smallButton("Ult 2 = o'rta") {
            vals[1] = (vals[0] + vals[2]) / 2
            valueViews[1].text = "${vals[1]}"
        })
        actions.addView(smallButton("Saqla") {
            ultCds[i] = vals.copyOf()
            ultKnown[i] = true
            ultApprox[i] = false
            ultNone[i] = false
            val n = names[i]
            if (n.isNotEmpty() && n != "Boshqa") {
                prefs.edit().putString("cd_$n", vals.joinToString(",")).apply()
            }
            refresh(i, 0, System.currentTimeMillis())
            closePopup()
        })
        box.addView(actions)
        showPopup(box, dp(330), dp(200))
    }

    // ---------------- Panel ----------------

    private fun smallButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 12f
        isAllCaps = false
        minWidth = 0; minimumWidth = 0
        minHeight = 0; minimumHeight = 0
        setPadding(dp(8), dp(4), dp(8), dp(4))
        setOnClickListener { onClick() }
    }

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
            val now = System.currentTimeMillis()
            for (i in 0 until N) { refresh(i, 0, now); refresh(i, 1, now) }
        }
        val close = smallButton("✕") { stopSelf() }
        header.addView(drag)
        header.addView(cdrBtn)
        header.addView(close)
        root.addView(header)

        for (i in 0 until N) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val nameB = smallButton("Raqib ${i + 1}") { showHeroPicker(i) }.apply {
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                textSize = 11f
                setOnLongClickListener { showCdEditor(i); true }
            }
            val minus = smallButton("−") { changeLevel(i, -1) }
            val lv = TextView(this).apply {
                text = "Lv 1"
                setTextColor(Color.WHITE)
                textSize = 12f
                gravity = Gravity.CENTER
            }
            val plus = smallButton("+") { changeLevel(i, +1) }
            val u = smallButton("U") { toggle(i, 0) }
            val s = smallButton("S") { toggle(i, 1) }.apply {
                setOnLongClickListener { showSpellPicker(i); true }
            }

            nameBtns[i] = nameB
            lvViews[i] = lv
            btns[i][0] = u
            btns[i][1] = s

            row.addView(nameB, LinearLayout.LayoutParams(dp(78), LinearLayout.LayoutParams.WRAP_CONTENT))
            row.addView(minus)
            row.addView(lv, LinearLayout.LayoutParams(dp(40), LinearLayout.LayoutParams.WRAP_CONTENT))
            row.addView(plus)
            row.addView(u)
            row.addView(s)
            root.addView(row)
        }

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(8)
            y = dp(60)
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

    private val tick = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            for (i in 0 until N) for (k in 0..1) {
                if (ends[i][k] != 0L) refresh(i, k, now)
            }
            handler.postDelayed(this, 500)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        closePopup()
        if (::root.isInitialized) runCatching { wm.removeView(root) }
        super.onDestroy()
    }
}
