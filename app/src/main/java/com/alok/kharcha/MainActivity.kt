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
        scanInbox()
        syncMail(false)
        render()
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
    private fun scanInbox() {
        if (!hasSmsPermission()) return
        try {
            val since = System.currentTimeMillis() - 30L * DAY_MS
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
                    val t = SmsParser.parse(addr, body, date)
                    if (t != null) Store.addTxnIfNew(t)
                }
            }
        } catch (e: Exception) {
            // ignore: permission revoked or provider unavailable
        }
    }

    // ---------- UI ----------
    private fun render() {
        root.removeAllViews()
        val dayEnd = day + DAY_MS
        val today = startOfDay(System.currentTimeMillis())

        root.addView(tv("💸 Daily Kharcha", 22f, true))
        root.addView(tv(
            if (hasSmsPermission()) "Bank SMS apne aap padhe jaate hain (phone ke andar hi rehte hain)."
            else "SMS permission nahi mili. Cash/manual entry kaam karegi, bank SMS ke liye permission do.",
            12f, false, mute
        ))
        root.addView(gap(8))

        // Gmail connection row
        val mailRow = LinearLayout(this)
        mailRow.orientation = LinearLayout.HORIZONTAL
        mailRow.gravity = Gravity.CENTER_VERTICAL
        val connected = Store.mailEmail.isNotBlank()
        val statusLine = when {
            !connected -> "Tap karke Gmail jodo, taaki bank/receipt mails bhi judein"
            mailStatus.isNotEmpty() -> mailStatus
            Store.lastMailSync > 0 -> "Last sync: " + SimpleDateFormat("d MMM, h:mm a", Locale("en", "IN")).format(Date(Store.lastMailSync))
            else -> ""
        }
        val mt = tv(
            (if (connected) "✉️ Gmail: ${Store.mailEmail}" else "✉️ Gmail jodo (optional)") + "\n" + statusLine,
            12f, true, if (connected) green else blue
        )
        mt.setOnClickListener { mailDialog() }
        mailRow.addView(mt, LinearLayout.LayoutParams(0, -2, 1f))
        if (connected) {
            mailRow.addView(pill("🔄 Sync", blue) { syncMail(true); render() })
        }
        root.addView(mailRow)
        root.addView(gap(10))

        // day navigation
        val nav = LinearLayout(this)
        nav.orientation = LinearLayout.HORIZONTAL
        nav.gravity = Gravity.CENTER_VERTICAL
        val prev = button("‹", 0xFF444A52.toInt()) { day -= DAY_MS; render() }
        val next = button("›", 0xFF444A52.toInt()) { if (day < today) { day += DAY_MS; render() } }
        val label = when (day) {
            today -> "Aaj"
            today - DAY_MS -> "Kal"
            else -> SimpleDateFormat("EEE, d MMM", Locale("en", "IN")).format(Date(day))
        }
        val lt = tv(label, 17f, true)
        lt.gravity = Gravity.CENTER
        nav.addView(prev, LinearLayout.LayoutParams(dp(52), -2))
        nav.addView(lt, LinearLayout.LayoutParams(0, -2, 1f))
        nav.addView(next, LinearLayout.LayoutParams(dp(52), -2))
        root.addView(nav)
        root.addView(gap(10))

        val dayTx = Store.snapTxns().filter { it.ts in day until dayEnd }.sortedByDescending { it.ts }
        val daySv = Store.savings.filter { it.ts in day until dayEnd }.sortedByDescending { it.ts }
        val need = dayTx.filter { it.kind == "need" }.sumOf { it.amt }
        val want = dayTx.filter { it.kind == "want" }.sumOf { it.amt }
        val waste = dayTx.filter { it.kind == "waste" }.sumOf { it.amt }
        val total = need + want + waste
        val saved = daySv.sumOf { it.amt }

        // summary card
        val sum = card()
        sum.addView(tv("Kharcha", 12f, false, mute))
        sum.addView(tv(inr(total), 28f, true))
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val w = tv("Bacha sakte the\n" + inr(waste), 13f, true, red)
        val s = tv("Bachaya\n" + inr(saved), 13f, true, green)
        row.addView(w, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(s, LinearLayout.LayoutParams(0, -2, 1f))
        sum.addView(row)
        sum.addView(gap(8))
        val rest = Store.budget - total
        sum.addView(stackedBar(listOf(need to blue, want to amber, waste to red, maxOf(rest, 0.0) to 0xFFE6E4DD.toInt())))
        sum.addView(tv(
            if (Store.budget > 0) {
                if (rest >= 0) "Budget ${inr(Store.budget.toDouble())} mein ${inr(rest)} bacha hai"
                else "Budget se ${inr(-rest)} zyada ⚠️"
            } else "Budget set nahi hai",
            12f, false, mute
        ))
        val bud = tv("Budget badlo", 12f, true, blue)
        bud.setPadding(0, dp(6), 0, 0)
        bud.setOnClickListener { budgetDialog() }
        sum.addView(bud)
        root.addView(sum)

        // action buttons
        val btns = LinearLayout(this)
        btns.orientation = LinearLayout.HORIZONTAL
        val b1 = button("+ Kharcha (cash)", ink) { addDialog() }
        val b2 = button("🌱 Bachat", green) { savingDialog() }
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
        val pending = dayTx.count { !it.confirmed }
        list.addView(tv("Is din ki entries", 15f, true))
        if (pending > 0) {
            list.addView(tv("⚠ $pending kharche ka tag/category check karo (tag pe tap karke badlo)", 12f, false, amber))
        }
        if (dayTx.isEmpty() && daySv.isEmpty()) {
            val e = tv("Koi entry nahi.", 14f, false, mute)
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
        val name = tv(c.emoji + " " + t.merchant, 15f, true)
        name.maxLines = 1
        top.addView(name, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(tv(inr(t.amt), 15f, true))
        box.addView(top)
        box.addView(tv(srcLabel(t.src), 11f, false, mute))

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
            AlertDialog.Builder(this).setMessage("Ye entry hata du?")
                .setPositiveButton("Haan") { _, _ ->
                    Store.removeTxn(t)
                    render()
                }.setNegativeButton("Nahi", null).show()
        }
        val lp = LinearLayout.LayoutParams(-2, -2)
        lp.rightMargin = dp(8)
        bottom.addView(kindPill, lp)
        bottom.addView(catPill)
        val fill = View(this)
        bottom.addView(fill, LinearLayout.LayoutParams(0, 1, 1f))
        bottom.addView(del)
        if (!t.confirmed) {
            val hint = tv("  ⚠ confirm karo", 11f, false, amber)
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
        val tx = Store.snapTxns().filter { it.ts in start until end }
        val sv = Store.savings.filter { it.ts in start until end }
        val total = tx.sumOf { it.amt }
        val waste = tx.filter { it.kind == "waste" }.sumOf { it.amt }
        val want = tx.filter { it.kind == "want" }.sumOf { it.amt }
        val saved = sv.sumOf { it.amt }

        c.addView(tv("📊 Pichle 7 din ki report", 15f, true))
        c.addView(gap(6))
        c.addView(tv("Kul kharcha: ${inr(total)}   (roz ka avg ${inr(total / 7)})", 14f))
        c.addView(tv("Optional: ${inr(want)}   ·   Bacha sakte the: ${inr(waste)}", 14f, false, red))
        c.addView(tv("Bachaya: ${inr(saved)}", 14f, false, green))
        val denom = saved + waste
        if (denom > 0) {
            val score = Math.round(saved / denom * 100).toInt()
            val verdict = when {
                score >= 60 -> "Badhiya, bachat aage hai 👏"
                score >= 30 -> "Theek hai, aur bacha sakte ho"
                else -> "Dhyaan do, fizool kharcha zyada hai"
            }
            c.addView(gap(6))
            c.addView(tv("Bachat score: $score%  –  $verdict", 14f, true))
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
        c.addView(tv("■ neela = zaroori+optional   ■ laal = bacha sakte the", 11f, false, mute))

        // tips: top categories by avoidable, then optional
        c.addView(gap(10))
        c.addView(tv("💡 Kaha bacha sakte ho", 15f, true))
        val byCat = Cats.list.map { cat ->
            Triple(cat, tx.filter { it.cat == cat.id && it.kind == "waste" }.sumOf { it.amt },
                tx.filter { it.cat == cat.id && it.kind == "want" }.sumOf { it.amt })
        }
        val tips = byCat.filter { it.second > 0 }.sortedByDescending { it.second }.take(3)
        if (tips.isEmpty()) {
            val opt = byCat.filter { it.third > 0 }.maxByOrNull { it.third }
            if (opt != null) {
                c.addView(tv("${opt.first.emoji} ${opt.first.name}: ${inr(opt.third)} optional mein gaya. ${opt.first.tip}", 13f))
            } else {
                c.addView(tv("Abhi koi fizool kharcha nahi 🎉", 13f, false, green))
            }
        }
        for ((cat, w, _) in tips) {
            val t = tv("${cat.emoji} ${cat.name}: ${inr(w)} bacha sakte the. ${cat.tip}", 13f)
            t.setPadding(0, dp(6), 0, 0)
            c.addView(t)
        }
        return c
    }

    private fun srcLabel(src: String): String {
        val parts = src.split("+")
        return when {
            parts.contains("sms") && parts.contains("mail") -> "📩 SMS + ✉️ Gmail (ek hi entry, duplicate hata diya)"
            parts.contains("sms") -> "📩 SMS se"
            parts.contains("mail") -> "✉️ Gmail se"
            else -> "✍️ Haath se"
        }
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
            mailStatus = "Password padh nahi paya, Gmail dobara jodo"
            return
        }
        syncing = true
        mailStatus = "Sync ho raha hai…"
        Thread {
            val msg = try {
                val n = MailSync.sync(email, pass, if (first) 30 else 7)
                Store.lastMailSync = System.currentTimeMillis()
                Store.save()
                "Sync ho gaya ($n nayi entry)"
            } catch (e: javax.mail.AuthenticationFailedException) {
                "Login fail: email ya App Password galat hai"
            } catch (e: Exception) {
                "Sync nahi hua: " + (e.message ?: "internet check karo")
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
            "Normal password nahi, Gmail ka App Password chahiye:\n" +
                "1) myaccount.google.com/apppasswords kholo (2-Step Verification on honi chahiye)\n" +
                "2) Naam 'Kharcha' likho, 16 akshar ka password banao\n" +
                "3) Neeche paste karo. Ye sirf is phone mein encrypt hokar rehta hai.\n" +
                "App sirf bank aur receipt wale mails padhta hai, kuch bhejta ya badalta nahi.",
            12f, false, mute
        ))
        val em = EditText(this)
        em.hint = "Gmail address"
        em.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        em.setText(Store.mailEmail)
        val pw = EditText(this)
        pw.hint = "App Password (16 akshar)"
        pw.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        box.addView(em)
        box.addView(pw)
        val b = AlertDialog.Builder(this).setTitle("Gmail jodo").setView(box)
            .setPositiveButton("Jodo") { _, _ ->
                val e = em.text.toString().trim()
                val p = pw.text.toString().replace(" ", "").trim()
                if (e.isEmpty() || p.isEmpty()) {
                    Toast.makeText(this, "Email aur App Password dono daalo", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Check ho raha hai…", Toast.LENGTH_SHORT).show()
                    Thread {
                        val err = try {
                            MailSync.test(e, p)
                            null
                        } catch (ex: javax.mail.AuthenticationFailedException) {
                            "Login fail: email ya App Password galat hai"
                        } catch (ex: Exception) {
                            "Connect nahi hua: " + (ex.message ?: "internet check karo")
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
            b.setNeutralButton("Gmail hatao") { _, _ ->
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

    // ---------- dialogs ----------
    private fun catDialog(t: Txn) {
        AlertDialog.Builder(this).setTitle("Category chuno")
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
        AlertDialog.Builder(this).setTitle("Roz ka budget (₹)").setView(e)
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
        note.hint = "Kis cheez pe? (jaise: chai)"
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
        AlertDialog.Builder(this).setTitle("Kharcha add karo").setView(box)
            .setPositiveButton("Save") { _, _ ->
                val a = amt.text.toString().toDoubleOrNull()
                if (a == null || a <= 0) {
                    Toast.makeText(this, "Amount sahi daalo", Toast.LENGTH_SHORT).show()
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
        amt.hint = "Kitna bachaya (₹)"
        amt.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        val note = EditText(this)
        note.hint = "Kaise? (jaise: Rapido ki jagah bus)"
        box.addView(amt)
        box.addView(note)
        AlertDialog.Builder(this).setTitle("Aaj kya bachaya?").setView(box)
            .setPositiveButton("Save") { _, _ ->
                val a = amt.text.toString().toDoubleOrNull()
                val n = note.text.toString().trim()
                if (a == null || a <= 0 || n.isEmpty()) {
                    Toast.makeText(this, "Amount aur kaise bachaya, dono likho", Toast.LENGTH_SHORT).show()
                } else {
                    val ts = if (day == startOfDay(System.currentTimeMillis())) System.currentTimeMillis() else day + 12 * 60 * 60 * 1000L
                    Store.addSaving(Saving("s:" + UUID.randomUUID(), ts, a, n))
                    render()
                }
            }.setNegativeButton("Cancel", null).show()
    }
}
