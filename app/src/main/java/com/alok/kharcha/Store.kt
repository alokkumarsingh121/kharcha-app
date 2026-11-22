package com.alok.kharcha

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

object Store {
    private lateinit var sp: SharedPreferences
    private val lock = Any()
    private var ready = false

    private var txns = mutableListOf<Txn>()
    var savings = mutableListOf<Saving>()
    private var seen = mutableSetOf<String>()
    var budget = 500
    var mailEmail = ""
    var mailPassEnc = ""
    var lastMailSync = 0L
    private var mailChecked = LinkedHashSet<String>()

    // Message texts already kept with an entry, and older messages whose entry could not be found
    // (so an inbox re-scan does not look for them again and again).
    private val msgKeys = HashSet<String>()
    private val noOwner = HashSet<String>()

    // Message texts are kept for about 13 months; the entries themselves are kept for ever.
    private const val MSG_KEEP_MS = 400L * 24 * 60 * 60 * 1000

    /** Bumped on every change so an open screen can refresh itself. */
    @Volatile var version = 0

    fun init(ctx: Context) {
        synchronized(lock) {
            if (ready) return
            sp = ctx.getSharedPreferences("kharcha", Context.MODE_PRIVATE)
            load()
            ready = true
        }
    }

    /** Visible entries (deleted ones are kept only to recognise the same payment again). */
    fun snapTxns(): List<Txn> = synchronized(lock) { txns.filter { !it.deleted } }

    /** A copy of an entry's message list, safe to read while a sync adds to it. */
    fun msgsOf(t: Txn): List<Msg> = synchronized(lock) { t.msgs.toList() }

    private fun load() {
        budget = sp.getInt("budget", 500)
        mailEmail = sp.getString("mailEmail", "") ?: ""
        mailPassEnc = sp.getString("mailPass", "") ?: ""
        lastMailSync = sp.getLong("lastMailSync", 0L)
        txns = mutableListOf()
        savings = mutableListOf()
        seen = HashSet(sp.getStringSet("seen", emptySet()) ?: emptySet())
        mailChecked = LinkedHashSet((sp.getString("mailChecked", "") ?: "").split("\n").filter { it.isNotBlank() })
        Parser.ownerNames = (sp.getString("owners", "") ?: "").split("\n").filter { it.isNotBlank() }
        try {
            val a = JSONArray(sp.getString("txns", "[]"))
            for (i in 0 until a.length()) {
                try {
                    val o = a.getJSONObject(i)
                    val t = Txn(
                        o.getString("id"), o.getLong("ts"), o.getDouble("amt"),
                        o.getString("m"), o.getString("c"), o.getString("k"),
                        o.getString("s"), o.getBoolean("ok"), o.optString("r", ""),
                        o.optString("a", ""), o.optString("b", ""), o.optBoolean("del", false)
                    )
                    val ma = o.optJSONArray("msgs")
                    if (ma != null) for (j in 0 until ma.length()) {
                        val m = ma.optJSONObject(j) ?: continue
                        val msg = Msg(
                            m.optString("k", ""), m.optString("s", ""), m.optString("f", ""),
                            m.optLong("t", 0L), m.optString("j", ""), m.optString("x", "")
                        )
                        if (msg.text.isNotBlank()) { t.msgs.add(msg); msgKeys.add(msg.key) }
                    }
                    txns.add(t)
                } catch (e: Exception) {
                    // skip one unreadable entry, keep the rest
                }
            }
            val b = JSONArray(sp.getString("savings", "[]"))
            for (i in 0 until b.length()) {
                try {
                    val o = b.getJSONObject(i)
                    savings.add(Saving(o.getString("id"), o.getLong("ts"), o.getDouble("amt"), o.getString("n")))
                } catch (e: Exception) {
                }
            }
        } catch (e: Exception) {
            // corrupted data: start fresh rather than crash
        }
    }

    // While a thread runs a batch (an inbox scan, a mail sync) its saves are combined into one at the end.
    private val batchDepth = ThreadLocal<Int>()
    private var dirty = false

    fun <T> batch(block: () -> T): T {
        batchDepth.set((batchDepth.get() ?: 0) + 1)
        try {
            return block()
        } finally {
            val d = (batchDepth.get() ?: 1) - 1
            batchDepth.set(d)
            if (d == 0) synchronized(lock) { if (dirty) saveNow() }
        }
    }

    fun save() {
        synchronized(lock) {
            if ((batchDepth.get() ?: 0) > 0) { dirty = true; return }
            saveNow()
        }
    }

    private fun saveNow() {
        dirty = false
        val keepMsgsFrom = System.currentTimeMillis() - MSG_KEEP_MS
        val a = JSONArray()
        for (t in txns) {
            val o = JSONObject().put("id", t.id).put("ts", t.ts).put("amt", t.amt)
                .put("m", t.merchant).put("c", t.cat).put("k", t.kind)
                .put("s", t.src).put("ok", t.confirmed).put("r", t.ref)
                .put("a", t.acct).put("b", t.bank).put("del", t.deleted)
            if (t.msgs.isNotEmpty() && t.ts < keepMsgsFrom) t.msgs.clear()
            if (t.msgs.isNotEmpty()) {
                val ma = JSONArray()
                for (m in t.msgs) {
                    ma.put(
                        JSONObject().put("k", m.key).put("s", m.src).put("f", m.from)
                            .put("t", m.ts).put("j", m.subject).put("x", m.text)
                    )
                }
                o.put("msgs", ma)
            }
            a.put(o)
        }
        val b = JSONArray()
        for (s in savings) {
            b.put(JSONObject().put("id", s.id).put("ts", s.ts).put("amt", s.amt).put("n", s.note))
        }
        sp.edit()
            .putString("txns", a.toString())
            .putString("savings", b.toString())
            .putStringSet("seen", HashSet(seen))
            .putInt("budget", budget)
            .putString("mailEmail", mailEmail)
            .putString("mailPass", mailPassEnc)
            .putLong("lastMailSync", lastMailSync)
            .putString("mailChecked", mailChecked.joinToString("\n"))
            .putString("owners", Parser.ownerNames.joinToString("\n"))
            .apply()
        version++
    }

    const val SEEN = 0
    const val ADDED = 1
    const val MERGED = 2
    const val HELD = 3

    /**
     * Adds a payment unless it was already seen. The same payment arriving from another source
     * (bank SMS, bank mail, receipt mail) is merged into one entry - see Dedup for the rules.
     * A message seen before its text was kept (older app version) gets its text added to its entry.
     */
    fun add(t: Txn): Int {
        synchronized(lock) {
            if (seen.contains(t.id)) {
                keepOldText(t)
                return SEEN
            }
            val dup = Dedup.find(txns, t)
            if (dup != null) {
                seen.add(t.id)
                Dedup.merge(dup, t)
                t.msgs.forEach { msgKeys.add(it.key) }
                save()
                return MERGED
            }
            // A receipt mail is not a debit: it only names a payment already seen from the bank.
            // Not marked seen, so it can still be matched if the bank alert arrives later.
            if (t.src == "receipt") return HELD
            seen.add(t.id)
            txns.add(t)
            t.msgs.forEach { msgKeys.add(it.key) }
            save()
            return ADDED
        }
    }

    private fun keepOldText(t: Txn) {
        val m = t.msgs.firstOrNull() ?: return
        if (m.key in msgKeys || m.key in noOwner) return
        val o = Dedup.owner(txns, t)
        if (o == null || !Dedup.addMsgs(o, listOf(m))) { noOwner.add(m.key); return }
        msgKeys.add(m.key)
        save()
    }

    /** Keeps a message found later (searched in the SMS inbox or Gmail when the entry was opened). */
    fun attach(t: Txn, m: Msg) {
        synchronized(lock) {
            if (Dedup.addMsgs(t, listOf(m))) {
                msgKeys.add(m.key)
                save()
            }
        }
    }

    fun addTxnIfNew(t: Txn): Boolean = add(t) == ADDED

    /** Hides an entry but remembers it, so the other source of the same payment does not bring it back. */
    fun removeTxn(t: Txn) {
        synchronized(lock) {
            if (t.src == "manual") txns.remove(t) else t.deleted = true
            save()
        }
    }

    fun mailDone(id: String): Boolean = synchronized(lock) { mailChecked.contains(id) }

    fun markMailDone(id: String) {
        synchronized(lock) {
            mailChecked.add(id)
            while (mailChecked.size > 4000) mailChecked.remove(mailChecked.first())
        }
    }

    fun addOwner(name: String) {
        synchronized(lock) {
            if (name.isBlank() || Parser.ownerNames.contains(name)) return
            Parser.ownerNames = (Parser.ownerNames + name).takeLast(3)
            save()
        }
    }

    fun addSaving(s: Saving) {
        synchronized(lock) {
            savings.add(s)
            save()
        }
    }
}
