package com.alok.kharcha

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.ByteArrayOutputStream
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A report period: [start, end) in millis, with a human label. */
data class Period(val label: String, val start: Long, val end: Long)

object Report {
    private const val DAY = 24L * 60 * 60 * 1000
    private val inLocale = Locale("en", "IN")
    private val money = NumberFormat.getIntegerInstance(inLocale)
    private fun inr(v: Double) = "Rs " + money.format(Math.round(v))
    private val dFmt = SimpleDateFormat("dd MMM yyyy", inLocale)
    private val tFmt = SimpleDateFormat("HH:mm", inLocale)

    private fun startOfDay(t: Long): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = t
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    fun periods(): List<Period> {
        val end = startOfDay(System.currentTimeMillis()) + DAY
        val c = Calendar.getInstance()
        c.timeInMillis = end - DAY
        c.set(Calendar.DAY_OF_MONTH, 1)
        val monthStart = startOfDay(c.timeInMillis)
        val monthName = SimpleDateFormat("MMMM yyyy", inLocale).format(Date(monthStart))
        c.add(Calendar.MONTH, -1)
        val prevStart = startOfDay(c.timeInMillis)
        val prevName = SimpleDateFormat("MMMM yyyy", inLocale).format(Date(prevStart))
        return listOf(
            Period("Last 7 days (weekly)", end - 7 * DAY, end),
            Period("Last 15 days", end - 15 * DAY, end),
            Period("Last 30 days", end - 30 * DAY, end),
            Period("This month ($monthName)", monthStart, end),
            Period("Last month ($prevName)", prevStart, monthStart)
        )
    }

    private class Data(p: Period) {
        val tx = Store.snapTxns().filter { it.ts >= p.start && it.ts < p.end }.sortedBy { it.ts }
        val sv = Store.savings.filter { it.ts >= p.start && it.ts < p.end }.sortedBy { it.ts }
        val total = tx.sumOf { it.amt }
        val need = tx.filter { it.kind == "need" }.sumOf { it.amt }
        val want = tx.filter { it.kind == "want" }.sumOf { it.amt }
        val waste = tx.filter { it.kind == "waste" }.sumOf { it.amt }
        val saved = sv.sumOf { it.amt }
        val days = Math.max(1, ((Math.min(p.end, startOfDay(System.currentTimeMillis()) + DAY) - p.start) / DAY).toInt())
        val score: Int? = if (saved + waste > 0) Math.round(saved / (saved + waste) * 100).toInt() else null
        val byCat = Cats.list.map { c ->
            val e = tx.filter { it.cat == c.id }
            Triple(c, e.sumOf { it.amt }, e.filter { it.kind == "waste" }.sumOf { it.amt })
        }.filter { it.second > 0 }.sortedByDescending { it.second }
        val byDay: List<Pair<Long, Double>> = (0 until days).map { i ->
            val d0 = p.start + i * DAY
            d0 to tx.filter { it.ts >= d0 && it.ts < d0 + DAY }.sumOf { it.amt }
        }
    }

    private fun source(src: String): String {
        val parts = src.split("+").distinct().map {
            when (it) { "sms" -> "SMS"; "email", "mail" -> "Bank mail"; "receipt" -> "Receipt"; else -> "Manual" }
        }
        return parts.joinToString(" + ")
    }

    // ---------------- Excel (.xlsx) ----------------

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private class Sheet(val name: String) {
        val rows = mutableListOf<List<Any?>>()
        fun row(vararg v: Any?) { rows.add(v.toList()) }
    }

    private fun colName(i: Int): String {
        var n = i + 1
        val sb = StringBuilder()
        while (n > 0) { val r = (n - 1) % 26; sb.insert(0, ('A' + r)); n = (n - 1) / 26 }
        return sb.toString()
    }

    private fun sheetXml(sh: Sheet): String {
        val sb = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        sb.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
        sb.append("<cols><col min=\"1\" max=\"1\" width=\"24\" customWidth=\"1\"/><col min=\"2\" max=\"8\" width=\"18\" customWidth=\"1\"/></cols><sheetData>")
        sh.rows.forEachIndexed { r, cells ->
            sb.append("<row r=\"${r + 1}\">")
            cells.forEachIndexed { c, v ->
                val ref = colName(c) + (r + 1)
                when (v) {
                    null -> {}
                    is Number -> sb.append("<c r=\"$ref\"><v>${v.toDouble()}</v></c>")
                    else -> {
                        val bold = r == 0 || (v is String && v.startsWith("#"))
                        val text = if (v is String && v.startsWith("#")) v.substring(1) else v.toString()
                        sb.append("<c r=\"$ref\" t=\"inlineStr\"${if (bold) " s=\"1\"" else ""}><is><t xml:space=\"preserve\">${esc(text)}</t></is></c>")
                    }
                }
            }
            sb.append("</row>")
        }
        sb.append("</sheetData></worksheet>")
        return sb.toString()
    }

    fun xlsx(p: Period): ByteArray {
        val d = Data(p)
        val summary = Sheet("Summary")
        summary.row("Expense report", p.label)
        summary.row("Period", dFmt.format(Date(p.start)) + " to " + dFmt.format(Date(p.end - 1)))
        summary.row("Generated", dFmt.format(Date()) + " " + tFmt.format(Date()))
        summary.row()
        summary.row("#Total spent", d.total)
        summary.row("Daily average", Math.round(d.total / d.days).toDouble())
        summary.row("Essential", d.need)
        summary.row("Optional", d.want)
        summary.row("Avoidable", d.waste)
        summary.row("Saved", d.saved)
        summary.row("Saving score %", d.score ?: "-")
        summary.row("Number of expenses", d.tx.size)
        summary.row()
        summary.row("#Category", "#Spent", "#Avoidable")
        for ((c, t, w) in d.byCat) summary.row(c.name, t, w)

        val entries = Sheet("Expenses")
        entries.row("Date", "Time", "Paid to / for", "Category", "Type", "Amount (Rs)", "Source", "Reference")
        for (t in d.tx) entries.row(
            dFmt.format(Date(t.ts)), tFmt.format(Date(t.ts)), t.merchant, Cats.of(t.cat).name,
            Kinds.label(t.kind), t.amt, source(t.src), t.ref.ifEmpty { null }
        )
        entries.row()
        entries.row("#Total", null, null, null, null, d.total)

        val daily = Sheet("Daily")
        daily.row("Date", "Spent (Rs)")
        for ((day, amt) in d.byDay) daily.row(dFmt.format(Date(day)), amt)

        val saves = Sheet("Savings")
        saves.row("Date", "How", "Amount (Rs)")
        for (s in d.sv) saves.row(dFmt.format(Date(s.ts)), s.note, s.amt)

        val sheets = listOf(summary, entries, daily, saves)
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            fun put(name: String, body: String) {
                z.putNextEntry(ZipEntry(name)); z.write(body.toByteArray(Charsets.UTF_8)); z.closeEntry()
            }
            val ct = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
            ct.append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/>")
            ct.append("<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>")
            ct.append("<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>")
            sheets.indices.forEach { ct.append("<Override PartName=\"/xl/worksheets/sheet${it + 1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>") }
            ct.append("</Types>")
            put("[Content_Types].xml", ct.toString())
            put("_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>")
            val wb = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>")
            sheets.forEachIndexed { i, s -> wb.append("<sheet name=\"${esc(s.name)}\" sheetId=\"${i + 1}\" r:id=\"rId${i + 1}\"/>") }
            wb.append("</sheets></workbook>")
            put("xl/workbook.xml", wb.toString())
            val rels = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
            sheets.indices.forEach { rels.append("<Relationship Id=\"rId${it + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet${it + 1}.xml\"/>") }
            rels.append("<Relationship Id=\"rId${sheets.size + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/></Relationships>")
            put("xl/_rels/workbook.xml.rels", rels.toString())
            put("xl/styles.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font><font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts><fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill></fills><borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders><cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs><cellXfs count=\"2\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/><xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/></cellXfs></styleSheet>")
            sheets.forEachIndexed { i, s -> put("xl/worksheets/sheet${i + 1}.xml", sheetXml(s)) }
        }
        return bos.toByteArray()
    }

    // ---------------- PDF ----------------

    fun pdf(p: Period): ByteArray {
        val d = Data(p)
        val doc = PdfDocument()
        val W = 595; val H = 842; val M = 40f
        var pageNo = 0
        var page: PdfDocument.Page? = null
        var y = 0f
        val text = Paint().apply { textSize = 10f; color = Color.rgb(29, 32, 36); isAntiAlias = true }
        val bold = Paint(text).apply { typeface = Typeface.DEFAULT_BOLD }
        val big = Paint(bold).apply { textSize = 18f }
        val mute = Paint(text).apply { color = Color.rgb(107, 112, 120); textSize = 9f }
        val line = Paint().apply { color = Color.rgb(220, 220, 215); strokeWidth = 0.7f }
        val red = Paint(bold).apply { color = Color.rgb(216, 71, 47) }
        val green = Paint(bold).apply { color = Color.rgb(31, 157, 99) }

        fun newPage() {
            page?.let { doc.finishPage(it) }
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
            y = M
            page!!.canvas.drawText("Daily Expenses  ·  ${p.label}  ·  page $pageNo", M, H - 20f, mute)
        }
        fun need(h: Float) { if (y + h > H - 40f) newPage() }
        fun right(s: String, x: Float, paint: Paint) = page!!.canvas.drawText(s, x - paint.measureText(s), y, paint)
        fun clip(s: String, max: Float, paint: Paint): String {
            var t = s
            while (t.isNotEmpty() && paint.measureText(t) > max) t = t.dropLast(1)
            return if (t.length < s.length) t.dropLast(1) + "…" else t
        }

        newPage()
        y += 14f
        page!!.canvas.drawText("Expense report", M, y, big); y += 18f
        page!!.canvas.drawText(p.label + "  (" + dFmt.format(Date(p.start)) + " – " + dFmt.format(Date(p.end - 1)) + ")", M, y, text); y += 22f

        val stats = listOf(
            "Total spent" to inr(d.total), "Daily average" to inr(d.total / d.days),
            "Essential" to inr(d.need), "Optional" to inr(d.want),
            "Avoidable" to inr(d.waste), "Saved" to inr(d.saved),
            "Saving score" to (d.score?.let { "$it%" } ?: "–"), "Expenses" to d.tx.size.toString()
        )
        for (i in stats.indices step 2) {
            page!!.canvas.drawText(stats[i].first, M, y, mute)
            page!!.canvas.drawText(stats[i + 1].first, M + 260f, y, mute)
            y += 13f
            page!!.canvas.drawText(stats[i].second, M, y, if (stats[i].first == "Avoidable") red else bold)
            page!!.canvas.drawText(stats[i + 1].second, M + 260f, y, if (stats[i + 1].first == "Saved") green else bold)
            y += 18f
        }

        if (d.byCat.isNotEmpty()) {
            need(40f); y += 6f
            page!!.canvas.drawText("By category", M, y, bold); y += 6f
            page!!.canvas.drawLine(M, y, W - M, y, line); y += 14f
            val maxC = d.byCat.first().second
            for ((c, t, w) in d.byCat) {
                need(18f)
                page!!.canvas.drawText(c.name, M, y, text)
                val bx = M + 170f; val bw = 220f
                val tw = (t / maxC * bw).toFloat(); val ww = (w / maxC * bw).toFloat()
                page!!.canvas.drawRect(bx, y - 8f, bx + tw - ww, y, Paint().apply { color = Color.rgb(47, 111, 219) })
                page!!.canvas.drawRect(bx + tw - ww, y - 8f, bx + tw, y, Paint().apply { color = Color.rgb(216, 71, 47) })
                right(inr(t), W - M, bold)
                y += 16f
            }
            page!!.canvas.drawText("Blue = essential + optional, red = avoidable", M, y, mute); y += 16f
        }

        need(50f); y += 6f
        page!!.canvas.drawText("All expenses", M, y, bold); y += 6f
        page!!.canvas.drawLine(M, y, W - M, y, line); y += 13f
        val cols = floatArrayOf(M, M + 72f, M + 108f, M + 268f, M + 368f)
        fun header() {
            page!!.canvas.drawText("Date", cols[0], y, mute); page!!.canvas.drawText("Time", cols[1], y, mute)
            page!!.canvas.drawText("Paid to / for", cols[2], y, mute); page!!.canvas.drawText("Category", cols[3], y, mute)
            page!!.canvas.drawText("Type", cols[4], y, mute); right("Amount", W - M, mute); y += 14f
        }
        header()
        if (d.tx.isEmpty()) { page!!.canvas.drawText("No expenses in this period.", M, y, text); y += 14f }
        for (t in d.tx) {
            if (y + 14f > H - 40f) { newPage(); header() }
            page!!.canvas.drawText(SimpleDateFormat("dd MMM", inLocale).format(Date(t.ts)), cols[0], y, text)
            page!!.canvas.drawText(tFmt.format(Date(t.ts)), cols[1], y, text)
            page!!.canvas.drawText(clip(t.merchant, 155f, text), cols[2], y, text)
            page!!.canvas.drawText(clip(Cats.of(t.cat).name, 95f, text), cols[3], y, text)
            page!!.canvas.drawText(Kinds.label(t.kind), cols[4], y, if (t.kind == "waste") red else text)
            right(inr(t.amt), W - M, bold)
            y += 14f
        }
        need(20f)
        page!!.canvas.drawLine(M, y - 8f, W - M, y - 8f, line); y += 4f
        page!!.canvas.drawText("Total", M, y, bold); right(inr(d.total), W - M, bold); y += 20f

        if (d.sv.isNotEmpty()) {
            need(40f)
            page!!.canvas.drawText("Savings", M, y, bold); y += 6f
            page!!.canvas.drawLine(M, y, W - M, y, line); y += 13f
            for (s in d.sv) {
                need(14f)
                page!!.canvas.drawText(SimpleDateFormat("dd MMM", inLocale).format(Date(s.ts)), cols[0], y, text)
                page!!.canvas.drawText(clip(s.note, 330f, text), cols[2], y, text)
                right(inr(s.amt), W - M, green)
                y += 14f
            }
        }

        page?.let { doc.finishPage(it) }
        val bos = ByteArrayOutputStream()
        doc.writeTo(bos)
        doc.close()
        return bos.toByteArray()
    }
}
