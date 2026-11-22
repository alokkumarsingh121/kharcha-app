package com.alok.kharcha

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

class MainActivity : Activity() {

    private lateinit var root: LinearLayout
    private var day: Long = startOfDay(System.currentTimeMillis())
    private var mailStatus = ""
    private var syncing = false
    private var followToday = true          // keep showing today, also after midnight
    private var shownVersion = -1
    private var ticks = 0
    private var fullScanDone = false
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())

    // Re-checks the SMS inbox every few seconds while the app is open, so a payment shows up
    // as soon as the bank SMS arrives (not only the next time the app is opened).
    private val tick = object : Runnable {
        override fun run() {
            ticks++
            scanInbox(quick = true)
            val today = startOfDay(System.currentTimeMillis())
            if (followToday && day != today) { day = today; shownVersion = -1 }
            if (Store.version != shownVersion) render()
            ui.postDelayed(this, if (ticks < 24) 5_000L else 15_000L)
        }
    }

    private val DAY_MS = 24L * 60 * 60 * 1000
    private val ink = 0xFF1D2024.toInt()
    private val mute = 0xFF6B7078.toInt()
    private val green = 0xFF1F9D63.toInt()
    private val red = 0xFFD8472F.toInt()
    private val blue = 0xFF2F6FDB.toInt()
    private val amber = 0xFFE0A21A.toInt()
    private val money = NumberFormat.getIntegerInstance(Locale("en", "IN"))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(applicationContext)
        val sv = ScrollView(this)
        sv.setBackgroundColor(0xFFF6F5F1.toInt())
        root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(14), dp(18), dp(14), dp(48))
        sv.addView(root)
        setContentView(sv)
        if (!hasSmsPermission()) {
            requestPermissions(arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        if (followToday) day = startOfDay(System.currentTimeMillis())
        scanInbox(quick = fullScanDone)
        fullScanDone = true
        syncMail(false)
        render()
        ticks = 0
        ui.removeCallbacks(tick)
        ui.postDelayed(tick, 5_000L)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(tick)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        scanInbox()
        render()
    }

    // ---------- helpers ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun inr(v: Double) = "₹" + money.format(Math.round(v))
    private fun startOfDay(t: Long): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = t
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }
    private fun hasSmsPermission() =
        checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    private fun tv(text: String, size: Float = 14f, bold: Boolean = false, color: Int = ink): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = size
        t.setTextColor(color)
        if (bold) t.setTypeface(t.typeface, android.graphics.Typeface.BOLD)
        return t
    }

    private fun bg(color: Int, radius: Int = 12, stroke: Int = 0): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(radius).toFloat()
        if (stroke != 0) g.setStroke(dp(1), stroke)
        return g
    }

    private fun card(): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.setPadding(dp(14), dp(12), dp(14), dp(12))
        c.background = bg(Color.WHITE, 14, 0xFFE6E4DD.toInt())
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.bottomMargin = dp(12)
        c.layoutParams = lp
        return c
    }

    private fun pill(text: String, color: Int, onClick: () -> Unit): TextView {
        val t = tv(text, 12.5f, true, color)
        t.setPadding(dp(10), dp(5), dp(10), dp(5))
        t.background = bg(Color.argb(30, Color.red(color), Color.green(color), Color.blue(color)), 99)
        t.setOnClickListener { onClick() }
        return t
    }

    private fun button(text: String, color: Int, onClick: () -> Unit): TextView {
        val t = tv(text, 14f, true, Color.WHITE)
        t.gravity = Gravity.CENTER
        t.setPadding(dp(8), dp(12), dp(8), dp(12))
        t.background = bg(color, 10)
        t.setOnClickListener { onClick() }
        return t
    }

    private fun gap(h: Int): View {
        val v = View(this)
        v.layoutParams = LinearLayout.LayoutParams(-1, dp(h))
        return v
    }

    private fun stackedBar(parts: List<Pair<Double, Int>>): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.layoutParams = LinearLayout.LayoutParams(-1, dp(10))
        row.background = bg(0xFFE6E4DD.toInt(), 99)
        val total = parts.sumOf { it.first }
        if (total <= 0) return row
        for ((v, c) in parts) {
            if (v <= 0) continue
            val seg = View(this)
            seg.setBackgroundColor(c)
            seg.layoutParams = LinearLayout.LayoutParams(0, -1, v.toFloat())
            row.addView(seg)
        }
        return row
    }

    // ---------- SMS inbox scan ----------
    /** Reads bank SMS from the inbox: 90 days on first open, the last 2 days on later checks. */
    private fun scanInbox(quick: Boolean = false) {
        if (!hasSmsPermission()) return
        try {
            val since = System.currentTimeMillis() - (if (quick) 2L else 90L) * DAY_MS
            val cur = contentResolver.query(
                Uri.parse("content://sms/inbox"),
                arrayOf("address", "body", "date"),
                "date>?", arrayOf(since.toString()), "date DESC"
            ) ?: return
            cur.use {
                while (it.moveToNext()) {
                    val addr = it.getString(0) ?: "SMS"
                    val body = it.getString(1) ?: continue
                    val date = it.getLong(2)
                    val t = Parser.parseSms(addr, body, date).txn
                    if (t != null) Store.add(t)
                }
            }
        } catch (e: Exception) {
            // ignore: permission revoked or provider unavailable
        }
    }

    // ---------- UI ----------
    private fun render() {
        shownVersion = Store.version
        root.removeAllViews()
        val dayEnd = day + DAY_MS
        val today = startOfDay(System.currentTimeMillis())

        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.BOTTOM
        head.addView(tv("💸 Daily Expenses", 22f, true), LinearLayout.LayoutParams(0, -2, 1f))
        val ver = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "" } catch (e: Exception) { "" }
        head.addView(tv("v$ver", 11f, false, mute))
        root.addView(head)
        root.addView(tv(
            if (hasSmsPermission()) "Bank SMS are read automatically and stay on this phone."
            else "SMS permission not granted. Manual entries work; allow SMS to read bank alerts.",
            12f, false, mute
        ))
        root.addView(gap(8))

        // Gmail connection row
        val mailRow = LinearLayout(this)
        mailRow.orientation = LinearLayout.HORIZONTAL
        mailRow.gravity = Gravity.CENTER_VERTICAL
        val connected = Store.mailEmail.isNotBlank()
        val statusLine = when {
            !connected -> "Tap to connect Gmail so bank alert mails are included too"
            mailStatus.isNotEmpty() -> mailStatus
            Store.lastMailSync > 0 -> "Last sync: " + SimpleDateFormat("d MMM, h:mm a", Locale("en", "IN")).format(Date(Store.lastMailSync))
            else -> ""
        }
        val mt = tv(
            (if (connected) "✉️ Gmail: ${Store.mailEmail}" else "✉️ Connect Gmail (optional)") + "\n" + statusLine,
            12f, true, if (connected) green else blue
        )
        mt.setOnClickListener { mailDialog() }
        mailRow.addView(mt, LinearLayout.LayoutParams(0, -2, 1f))
        if (connected) {
            mailRow.addView(pill("🔄 Sync", blue) { syncMail(true); render() })
        }
        root.addView(mailRow)
        val missed = tv("🔍 SMS check (which SMS were read, which were missed)", 12f, true, blue)
        missed.setPadding(0, dp(8), 0, 0)
        missed.setOnClickListener { missedDialog() }
        root.addView(missed)
        root.addView(gap(10))

        // day navigation
        val nav = LinearLayout(this)
        nav.orientation = LinearLayout.HORIZONTAL
        nav.gravity = Gravity.CENTER_VERTICAL
        val prev = button("‹", 0xFF444A52.toInt()) { day -= DAY_MS; followToday = false; render() }
        val next = button("›", 0xFF444A52.toInt()) {
            if (day < today) { day += DAY_MS; followToday = day >= today; render() }
        }
        val label = when (day) {
            today -> "Today"
            today - DAY_MS -> "Yesterday"
            else -> SimpleDateFormat("EEE, d MMM", Locale("en", "IN")).format(Date(day))
        }
        val lt = tv(label, 17f, true)
        lt.gravity = Gravity.CENTER
        nav.addView(prev, LinearLayout.LayoutParams(dp(52), -2))
        nav.addView(lt, LinearLayout.LayoutParams(0, -2, 1f))
        nav.addView(next, LinearLayout.LayoutParams(dp(52), -2))
        root.addView(nav)
        val range = tv(SimpleDateFormat("d MMM yyyy", Locale("en", "IN")).format(Date(day)) + " · 12:00 AM to 11:59 PM", 11f, false, mute)
        range.gravity = Gravity.CENTER
        root.addView(range, LinearLayout.LayoutParams(-1, -2))
        root.addView(gap(10))

        val dayTx = Store.snapTxns().filter { it.ts in day until dayEnd }.sortedByDescending { it.ts }
        val daySv = Store.savings.filter { it.ts in day until dayEnd }.sortedByDescending { it.ts }
        val need = dayTx.filter { it.kind == "need" }.sumOf { it.amt }
        val want = dayTx.filter { it.kind == "want" }.sumOf { it.amt }
        val waste = dayTx.filter { it.kind == "waste" }.sumOf { it.amt }
        val total = need + want + waste
        val saved = daySv.sumOf { it.amt }
        val notCounted = dayTx.filter { it.kind == "skip" }.sumOf { it.amt }

        // summary card
        val sum = card()
        sum.addView(tv("Spent", 12f, false, mute))
        sum.addView(tv(inr(total), 28f, true))
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val w = tv("Avoidable\n" + inr(waste), 13f, true, red)
        val s = tv("Saved\n" + inr(saved), 13f, true, green)
        row.addView(w, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(s, LinearLayout.LayoutParams(0, -2, 1f))
        sum.addView(row)
        if (notCounted > 0) sum.addView(tv("Not counted (card bill / own transfer): ${inr(notCounted)}", 11f, false, mute))
        sum.addView(gap(8))
        val rest = Store.budget - total
        sum.addView(stackedBar(listOf(need to blue, want to amber, waste to red, maxOf(rest, 0.0) to 0xFFE6E4DD.toInt())))
        sum.addView(tv(
            if (Store.budget > 0) {
                if (rest >= 0) "${inr(rest)} left of ${inr(Store.budget.toDouble())} budget"
                else "${inr(-rest)} over budget ⚠️"
            } else "No budget set",
            12f, false, mute
        ))
        val bud = tv("Change budget", 12f, true, blue)
        bud.setPadding(0, dp(6), 0, 0)
        bud.setOnClickListener { budgetDialog() }
        sum.addView(bud)
        root.addView(sum)

        // action buttons
        val btns = LinearLayout(this)
        btns.orientation = LinearLayout.HORIZONTAL
        val b1 = button("+ Expense (cash)", ink) { addDialog() }
        val b2 = button("🌱 Saving", green) { savingDialog() }
        val lp1 = LinearLayout.LayoutParams(0, -2, 1f)
        lp1.rightMargin = dp(6)
        val lp2 = LinearLayout.LayoutParams(0, -2, 1f)
        lp2.leftMargin = dp(6)
        btns.addView(b1, lp1)
        btns.addView(b2, lp2)
        root.addView(btns)
        root.addView(gap(12))

        // entries
        val list = card()
        val pending = dayTx.count { !it.confirmed && it.kind != "skip" }
        list.addView(tv("Entries for this day", 15f, true))
        if (pending > 0) {
            list.addView(tv("⚠ Check tag/category of $pending expense(s) (tap a tag to change it)", 12f, false, amber))
        }
        if (dayTx.isEmpty() && daySv.isEmpty()) {
            val e = tv("No entries.", 14f, false, mute)
            e.setPadding(0, dp(10), 0, dp(4))
            list.addView(e)
        }
        for (t in dayTx) list.addView(txnRow(t))
        for (sv in daySv) list.addView(savingRow(sv))
        root.addView(list)

        root.addView(reportCard())
    }

    private fun txnRow(t: Txn): View {
        val c = Cats.of(t.cat)
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(0, dp(10), 0, dp(6))

        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        val name = tv(c.emoji + " " + t.displayName(), 15f, true)
        name.maxLines = 1
        top.addView(name, LinearLayout.LayoutParams(0, -2, 1f))
        val amtView = tv(inr(t.amt), 15f, true, if (t.kind == "skip") mute else ink)
        if (t.kind == "skip") amtView.paintFlags = amtView.paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
        top.addView(amtView)
        box.addView(top)
        val time = SimpleDateFormat("h:mm a", Locale("en", "IN")).format(Date(t.ts))
        val info = listOf(time, t.bank.ifBlank { "" }, srcLabel(t.src)).filter { it.isNotBlank() }.joinToString("  ·  ")
        box.addView(tv(info + if (t.kind == "skip") "  ·  not counted" else "", 11f, false, mute))

        val bottom = LinearLayout(this)
        bottom.orientation = LinearLayout.HORIZONTAL
        bottom.gravity = Gravity.CENTER_VERTICAL
        bottom.setPadding(0, dp(6), 0, 0)
        val kindPill = pill(Kinds.label(t.kind), Kinds.color(t.kind)) {
            t.kind = Kinds.next(t.kind)
            t.confirmed = true
            Store.save()
            render()
        }
        val catPill = pill(c.name, mute) { catDialog(t) }
        val del = tv("✕", 16f, false, mute)
        del.setPadding(dp(14), dp(4), dp(4), dp(4))
        del.setOnClickListener {
            AlertDialog.Builder(this).setMessage("Delete this entry?")
                .setPositiveButton("Yes") { _, _ ->
                    Store.removeTxn(t)
                    render()
                }.setNegativeButton("No", null).show()
        }
        val lp = LinearLayout.LayoutParams(-2, -2)
        lp.rightMargin = dp(8)
        bottom.addView(kindPill, lp)
        bottom.addView(catPill)
        val fill = View(this)
        bottom.addView(fill, LinearLayout.LayoutParams(0, 1, 1f))
        bottom.addView(del)
        if (!t.confirmed) {
            val hint = tv("  ⚠ please confirm", 11f, false, amber)
            bottom.addView(hint, 2)
        }
        box.addView(bottom)
        val line = View(this)
        line.setBackgroundColor(0xFFE6E4DD.toInt())
        box.addView(line, LinearLayout.LayoutParams(-1, 1))
        (line.layoutParams as LinearLayout.LayoutParams).topMargin = dp(8)
        return box
    }

    private fun savingRow(sv: Saving): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(10), 0, dp(6))
        row.addView(tv("🌱 " + sv.note, 15f, true, green), LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(tv("+" + inr(sv.amt), 15f, true, green))
        val del = tv("✕", 16f, false, mute)
        del.setPadding(dp(14), dp(4), dp(4), dp(4))
        del.setOnClickListener {
            Store.savings.remove(sv)
            Store.save()
            render()
        }
        row.addView(del)
        return row
    }

    private fun reportCard(): View {
        val c = card()
        val end = startOfDay(System.currentTimeMillis()) + DAY_MS
        val start = end - 7 * DAY_MS
        val tx = Store.snapTxns().filter { it.ts in start until end && it.kind != "skip" }
        val sv = Store.savings.filter { it.ts in start until end }
        val total = tx.sumOf { it.amt }
        val waste = tx.filter { it.kind == "waste" }.sumOf { it.amt }
        val want = tx.filter { it.kind == "want" }.sumOf { it.amt }
        val saved = sv.sumOf { it.amt }

        c.addView(tv("📊 Last 7 days", 15f, true))
        c.addView(gap(6))
        c.addView(tv("Total spent: ${inr(total)}   (daily avg ${inr(total / 7)})", 14f))
        c.addView(tv("Optional: ${inr(want)}   ·   Avoidable: ${inr(waste)}", 14f, false, red))
        c.addView(tv("Saved: ${inr(saved)}", 14f, false, green))
        val denom = saved + waste
        if (denom > 0) {
            val score = Math.round(saved / denom * 100).toInt()
            val verdict = when {
                score >= 60 -> "Great, savings are ahead 👏"
                score >= 30 -> "Okay, room to save more"
                else -> "Careful, too much avoidable spending"
            }
            c.addView(gap(6))
            c.addView(tv("Saving score: $score%  –  $verdict", 14f, true))
        }

        c.addView(gap(10))
        val dayFmt = SimpleDateFormat("EEE d", Locale("en", "IN"))
        val maxDay = (0 until 7).maxOf { i ->
            tx.filter { it.ts in (start + i * DAY_MS) until (start + (i + 1) * DAY_MS) }.sumOf { it.amt }
        }.coerceAtLeast(1.0)
        for (i in 0 until 7) {
            val d0 = start + i * DAY_MS
            val dt = tx.filter { it.ts in d0 until (d0 + DAY_MS) }
            val ok = dt.filter { it.kind != "waste" }.sumOf { it.amt }
            val ws = dt.filter { it.kind == "waste" }.sumOf { it.amt }
            val r = LinearLayout(this)
            r.orientation = LinearLayout.HORIZONTAL
            r.gravity = Gravity.CENTER_VERTICAL
            r.setPadding(0, dp(3), 0, dp(3))
            r.addView(tv(dayFmt.format(Date(d0)), 12f, false, mute), LinearLayout.LayoutParams(dp(52), -2))
            val barHolder = LinearLayout(this)
            barHolder.orientation = LinearLayout.HORIZONTAL
            val okW = (ok / maxDay).toFloat()
            val wsW = (ws / maxDay).toFloat()
            if (okW > 0) {
                val v = View(this); v.setBackgroundColor(blue)
                barHolder.addView(v, LinearLayout.LayoutParams(0, dp(10), okW))
            }
            if (wsW > 0) {
                val v = View(this); v.setBackgroundColor(red)
                barHolder.addView(v, LinearLayout.LayoutParams(0, dp(10), wsW))
            }
            val spacer = View(this)
            barHolder.addView(spacer, LinearLayout.LayoutParams(0, dp(10), maxOf(1f - okW - wsW, 0f)))
            r.addView(barHolder, LinearLayout.LayoutParams(0, -2, 1f))
            r.addView(tv(inr(ok + ws), 12f, true), LinearLayout.LayoutParams(dp(64), -2))
            (r.getChildAt(2) as TextView).gravity = Gravity.END
            c.addView(r)
        }
        c.addView(tv("■ blue = essential+optional   ■ red = avoidable", 11f, false, mute))

        // tips: top categories by avoidable, then optional
        c.addView(gap(10))
        c.addView(tv("💡 Where you can save", 15f, true))
        val byCat = Cats.list.map { cat ->
            Triple(cat, tx.filter { it.cat == cat.id && it.kind == "waste" }.sumOf { it.amt },
                tx.filter { it.cat == cat.id && it.kind == "want" }.sumOf { it.amt })
        }
        val tips = byCat.filter { it.second > 0 }.sortedByDescending { it.second }.take(3)
        if (tips.isEmpty()) {
            val opt = byCat.filter { it.third > 0 }.maxByOrNull { it.third }
            if (opt != null) {
                c.addView(tv("${opt.first.emoji} ${opt.first.name}: ${inr(opt.third)} went on optional spending. ${opt.first.tip}", 13f))
            } else {
                c.addView(tv("No avoidable spending yet 🎉", 13f, false, green))
            }
        }
        for ((cat, w, _) in tips) {
            val t = tv("${cat.emoji} ${cat.name}: ${inr(w)} was avoidable. ${cat.tip}", 13f)
            t.setPadding(0, dp(6), 0, 0)
            c.addView(t)
        }
        c.addView(gap(12))
        val exp = button("⬇️ Download report (Excel / PDF)", ink) { exportDialog() }
        c.addView(exp)
        return c
    }

    private fun srcLabel(src: String): String {
        val parts = src.split("+").filter { it.isNotEmpty() }
        if (parts.contains("manual")) return "✍️ Haath se"
        val names = parts.distinct().map {
            when (it) {
                "sms" -> "📩 Bank SMS"
                "email", "mail" -> "✉️ Bank mail"
                "receipt" -> "🧾 Receipt mail"
                else -> it
            }
        }
        return if (names.size > 1) names.joinToString(" + ") + " (merged, duplicate removed)" else names.firstOrNull() ?: ""
    }

    // ---------- Gmail sync ----------
    private fun syncMail(force: Boolean) {
        val email = Store.mailEmail
        if (email.isBlank() || Store.mailPassEnc.isBlank() || syncing) return
        val now = System.currentTimeMillis()
        if (!force && now - Store.lastMailSync < 5 * 60 * 1000L) return
        val first = Store.lastMailSync == 0L
        val pass = try {
            Secret.dec(Store.mailPassEnc)
        } catch (e: Exception) {
            mailStatus = "Could not read password, reconnect Gmail"
            return
        }
        syncing = true
        mailStatus = "Syncing…"
        Thread {
            val msg = try {
                val n = MailSync.sync(email, pass, if (first) 30 else 7)
                Store.lastMailSync = System.currentTimeMillis()
                Store.save()
                "Synced ($n new)"
            } catch (e: javax.mail.AuthenticationFailedException) {
                "Login failed: wrong email or App Password"
            } catch (e: Exception) {
                "Sync failed: " + (e.message ?: "check internet")
            }
            runOnUiThread {
                syncing = false
                mailStatus = msg
                render()
            }
        }.start()
    }

    private fun mailDialog() {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(20), dp(10), dp(20), 0)
        box.addView(tv(
            "Use a Gmail App Password, not your normal password:\n" +
                "1) Open myaccount.google.com/apppasswords (2-Step Verification must be on)\n" +
                "2) Name it 'Kharcha' and create the 16-letter password\n" +
                "3) Paste it below. It is stored encrypted on this phone only.\n" +
                "The app only reads bank and receipt mails; it never sends or changes anything.",
            12f, false, mute
        ))
        val em = EditText(this)
        em.hint = "Gmail address"
        em.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        em.setText(Store.mailEmail)
        val pw = EditText(this)
        pw.hint = "App Password (16 letters)"
        pw.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        box.addView(em)
        box.addView(pw)
        val b = AlertDialog.Builder(this).setTitle("Connect Gmail").setView(box)
            .setPositiveButton("Connect") { _, _ ->
                val e = em.text.toString().trim()
                val p = pw.text.toString().replace(" ", "").trim()
                if (e.isEmpty() || p.isEmpty()) {
                    Toast.makeText(this, "Enter both email and App Password", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Checking…", Toast.LENGTH_SHORT).show()
                    Thread {
                        val err = try {
                            MailSync.test(e, p)
                            null
                        } catch (ex: javax.mail.AuthenticationFailedException) {
                            "Login failed: wrong email or App Password"
                        } catch (ex: Exception) {
                            "Could not connect: " + (ex.message ?: "check internet")
                        }
                        runOnUiThread {
                            if (err == null) {
                                Store.mailEmail = e
                                Store.mailPassEnc = Secret.enc(p)
                                Store.lastMailSync = 0L
                                Store.save()
                                mailStatus = ""
                                syncMail(true)
                                render()
                            } else {
                                Toast.makeText(this, err, Toast.LENGTH_LONG).show()
                            }
                        }
                    }.start()
                }
            }.setNegativeButton("Cancel", null)
        if (Store.mailEmail.isNotBlank()) {
            b.setNeutralButton("Remove Gmail") { _, _ ->
                Store.mailEmail = ""
                Store.mailPassEnc = ""
                Store.lastMailSync = 0L
                Store.save()
                mailStatus = ""
                render()
            }
        }
        b.show()
    }

    private fun missedDialog() {
        if (!hasSmsPermission()) {
            Toast.makeText(this, "Allow SMS permission first", Toast.LENGTH_LONG).show()
            return
        }
        var total = 0
        var read = 0
        val missed = mutableListOf<String>()
        try {
            val cur = contentResolver.query(
                Uri.parse("content://sms/inbox"),
                arrayOf("address", "body", "date"),
                "date>?", arrayOf((System.currentTimeMillis() - 90L * DAY_MS).toString()), "date DESC"
            )
            cur?.use {
                while (it.moveToNext()) {
                    total++
                    val addr = it.getString(0) ?: ""
                    val body = it.getString(1) ?: continue
                    val p = Parser.parseSms(addr, body, it.getLong(2))
                    if (p.txn != null) { read++; continue }
                    if (missed.size >= 15 || !Parser.mentionsMoney(body)) continue
                    // Only list messages that could be a payment we missed; obvious non-payments are left out.
                    if (p.reason.startsWith("OTP") || p.reason.startsWith("promotional") || p.reason.startsWith("money received") ||
                        p.reason.startsWith("not completed") || p.reason.startsWith("reminder")) continue
                    val date = SimpleDateFormat("d MMM h:mm a", Locale("en", "IN")).format(Date(it.getLong(2)))
                    missed += "• $addr ($date) [${p.reason}]: " + body.replace('\n', ' ').take(200)
                }
            }
        } catch (e: Exception) {
            missed += "Could not read SMS: " + e.message
        }
        val head = "Last 90 days: $total SMS, $read read as payments.\n" +
            (if (total == 0) "The app cannot see the inbox. Check SMS permission (and 'Allow restricted settings') in Settings.\n" else "")
        val body = if (missed.isEmpty()) "No missed SMS that look like payments." else
            "These mention money but were not counted. If one is a real payment, tap Copy and send it to Claude:\n\n" + missed.joinToString("\n\n")
        val text = head + "\n" + body
        val dlg = AlertDialog.Builder(this)
            .setTitle("SMS check")
            .setMessage(text)
            .setPositiveButton("OK", null)
        if (missed.isNotEmpty()) dlg.setNeutralButton("Copy") { _, _ ->
            try {
                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("SMS check", text))
                Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) { }
        }
        dlg.show()
    }

    // ---------- export ----------
    private var pendingExport: ByteArray? = null

    private fun exportDialog() {
        val ps = Report.periods()
        AlertDialog.Builder(this).setTitle("Report period")
            .setItems(ps.map { it.label }.toTypedArray()) { _, i ->
                val p = ps[i]
                AlertDialog.Builder(this).setTitle("Format")
                    .setItems(arrayOf("Excel (.xlsx)", "PDF")) { _, f -> export(p, f == 0) }
                    .show()
            }.show()
    }

    private fun export(p: Period, excel: Boolean) {
        val bytes = try {
            if (excel) Report.xlsx(p) else Report.pdf(p)
        } catch (e: Exception) {
            Toast.makeText(this, "Could not create report: " + e.message, Toast.LENGTH_LONG).show()
            return
        }
        pendingExport = bytes
        val stamp = SimpleDateFormat("yyyy-MM-dd", Locale("en", "IN")).format(Date())
        val slug = p.label.substringBefore(" (").lowercase().replace(" ", "-")
        val i = android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT)
        i.addCategory(android.content.Intent.CATEGORY_OPENABLE)
        i.type = if (excel) "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" else "application/pdf"
        i.putExtra(android.content.Intent.EXTRA_TITLE, "expenses-$slug-$stamp." + if (excel) "xlsx" else "pdf")
        try {
            startActivityForResult(i, 42)
        } catch (e: Exception) {
            Toast.makeText(this, "No file picker found on this phone", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 42) return
        val bytes = pendingExport
        pendingExport = null
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null || bytes == null) return
        try {
            contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            Toast.makeText(this, "Report saved ✔", Toast.LENGTH_LONG).show()
            val v = android.content.Intent(android.content.Intent.ACTION_VIEW)
            v.setDataAndType(uri, contentResolver.getType(uri))
            v.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            try { startActivity(v) } catch (e: Exception) { }
        } catch (e: Exception) {
            Toast.makeText(this, "Could not save: " + e.message, Toast.LENGTH_LONG).show()
        }
    }

    // ---------- dialogs ----------
    private fun catDialog(t: Txn) {
        AlertDialog.Builder(this).setTitle("Choose category")
            .setItems(Cats.list.map { it.emoji + " " + it.name }.toTypedArray()) { _, i ->
                t.cat = Cats.list[i].id
                t.kind = Cats.list[i].kind
                t.confirmed = true
                Store.save()
                render()
            }.show()
    }

    private fun budgetDialog() {
        val e = EditText(this)
        e.inputType = InputType.TYPE_CLASS_NUMBER
        e.setText(Store.budget.toString())
        AlertDialog.Builder(this).setTitle("Daily budget (₹)").setView(e)
            .setPositiveButton("Save") { _, _ ->
                Store.budget = e.text.toString().toIntOrNull() ?: Store.budget
                Store.save()
                render()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun addDialog() {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(20), dp(10), dp(20), 0)
        val amt = EditText(this)
        amt.hint = "Amount (₹)"
        amt.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        val note = EditText(this)
        note.hint = "What for? (e.g. tea)"
        val sp = Spinner(this)
        sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            Cats.list.map { it.emoji + " " + it.name })
        val rg = RadioGroup(this)
        val ids = HashMap<Int, String>()
        for (k in Kinds.order) {
            val rb = RadioButton(this)
            rb.text = Kinds.label(k)
            rb.id = View.generateViewId()
            ids[rb.id] = k
            rg.addView(rb)
        }
        rg.check(rg.getChildAt(0).id)
        sp.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val k = Cats.list[pos].kind
                rg.check(rg.getChildAt(Kinds.order.indexOf(k)).id)
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
        box.addView(amt)
        box.addView(note)
        box.addView(sp)
        box.addView(rg)
        AlertDialog.Builder(this).setTitle("Add expense").setView(box)
            .setPositiveButton("Save") { _, _ ->
                val a = amt.text.toString().toDoubleOrNull()
                if (a == null || a <= 0) {
                    Toast.makeText(this, "Enter a valid amount", Toast.LENGTH_SHORT).show()
                } else {
                    val cat = Cats.list[sp.selectedItemPosition]
                    val ts = if (day == startOfDay(System.currentTimeMillis())) System.currentTimeMillis() else day + 12 * 60 * 60 * 1000L
                    Store.addTxnIfNew(
                        Txn("m:" + UUID.randomUUID(), ts, a,
                            note.text.toString().trim().ifEmpty { cat.name },
                            cat.id, ids[rg.checkedRadioButtonId] ?: cat.kind, "manual", true)
                    )
                    render()
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun savingDialog() {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(20), dp(10), dp(20), 0)
        val amt = EditText(this)
        amt.hint = "Amount saved (₹)"
        amt.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        val note = EditText(this)
        note.hint = "How? (e.g. bus instead of Rapido)"
        box.addView(amt)
        box.addView(note)
        AlertDialog.Builder(this).setTitle("What did you save?").setView(box)
            .setPositiveButton("Save") { _, _ ->
                val a = amt.text.toString().toDoubleOrNull()
                val n = note.text.toString().trim()
                if (a == null || a <= 0 || n.isEmpty()) {
                    Toast.makeText(this, "Enter amount and how you saved", Toast.LENGTH_SHORT).show()
                } else {
                    val ts = if (day == startOfDay(System.currentTimeMillis())) System.currentTimeMillis() else day + 12 * 60 * 60 * 1000L
                    Store.addSaving(Saving("s:" + UUID.randomUUID(), ts, a, n))
                    render()
                }
            }.setNegativeButton("Cancel", null).show()
    }
}
