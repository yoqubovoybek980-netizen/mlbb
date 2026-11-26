package com.example.cdtracker

import android.content.Context

object Data {

    /**
     * cds: 1/2/3-daraja ult CD (soniya).
     * approx: manbada faqat bitta qiymat bor (1-daraja); keyingi darajalarda CD kamayishi mumkin.
     * hasUlt: false bo'lsa qahramonda ult yo'q.
     * early: ult 1-levelda ochiladi (Julian, Suyou).
     */
    class Hero(val cds: IntArray, val approx: Boolean, val hasUlt: Boolean, val early: Boolean = false)

    private val EARLY = setOf("Julian", "Suyou")

    private fun readLines(ctx: Context, file: String): List<String> =
        ctx.assets.open(file).bufferedReader().use { it.readLines() }

    private fun splitCsv(line: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var q = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' && q && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                ch == '"' -> q = !q
                ch == ',' && !q -> { out.add(sb.toString()); sb.clear() }
                else -> sb.append(ch)
            }
            i++
        }
        out.add(sb.toString())
        return out
    }

    private fun secs(s: String): Int? = s.trim().toDoubleOrNull()?.let { Math.round(it).toInt() }

    /** Qahramon -> ma'lumot. assets/heroes.csv, keyin assets/heroes_patch.csv ustidan yoziladi. */
    fun heroes(ctx: Context): LinkedHashMap<String, Hero> {
        val m = LinkedHashMap<String, Hero>()

        val main = readLines(ctx, "heroes.csv")
        for ((idx, raw) in main.withIndex()) {
            if (idx == 0 || raw.isBlank()) continue          // sarlavha
            val c = splitCsv(raw)
            if (c.size < 7) continue
            val name = c[0].trim().removePrefix("\uFEFF")
            if (name.isEmpty()) continue
            val a = secs(c[2])
            val b = secs(c[3])
            val d = secs(c[4])
            val holat = c[6].trim()
            val early = name in EARLY
            when {
                a == null -> {
                    if (holat.startsWith("Ultimate yo")) m[name] = Hero(IntArray(3), false, false, early)
                    // aks holda CD noma'lum: bazaga qo'shilmaydi (panelda "?" chiqadi)
                }
                b != null && d != null -> m[name] = Hero(intArrayOf(a, b, d), false, true, early)
                else -> m[name] = Hero(intArrayOf(a, a, a), true, true, early)
            }
        }

        for (raw in readLines(ctx, "heroes_patch.csv")) {
            val l = raw.trim()
            if (l.isEmpty() || l.startsWith("#")) continue
            val p = l.split(",")
            val name = p[0].trim()
            val v = p.drop(1).mapNotNull { it.trim().toIntOrNull() }
            val cds = when (v.size) {
                1 -> intArrayOf(v[0], v[0], v[0])
                3 -> intArrayOf(v[0], v[1], v[2])
                else -> continue
            }
            m[name] = Hero(cds, false, true, name in EARLY)
        }
        return m
    }

    /** Battle spell -> CD (assets/spells.csv) */
    fun spells(ctx: Context): LinkedHashMap<String, Int> {
        val m = LinkedHashMap<String, Int>()
        for (l in readLines(ctx, "spells.csv").map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }) {
            val p = l.split(",")
            val cd = p.getOrNull(1)?.trim()?.toIntOrNull() ?: continue
            m[p[0].trim()] = cd
        }
        return m
    }
}
