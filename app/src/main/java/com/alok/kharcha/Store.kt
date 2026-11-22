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
                val o = a.getJSONObject(i)
                txns.add(
                    Txn(
                        o.getString("id"), o.getLong("ts"), o.getDouble("amt"),
                        o.getString("m"), o.getString("c"), o.getString("k"),
                        o.getString("s"), o.getBoolean("ok"), o.optString("r", ""),
                        o.optString("a", ""), o.optString("b", ""), o.optBoolean("del", false)
                    )
                )
            }
            val b = JSONArray(sp.getString("savings", "[]"))
            for (i in 0 until b.length()) {
                val o = b.getJSONObject(i)
                savings.add(Saving(o.getString("id"), o.getLong("ts"), o.getDouble("amt"), o.getString("n")))
            }
        } catch (e: Exception) {
            // corrupted data: start fresh rather than crash
        }
    }

    fun save() {
        synchronized(lock) {
            val a = JSONArray()
            for (t in txns) {
                a.put(
                    JSONObject().put("id", t.id).put("ts", t.ts).put("amt", t.amt)
                        .put("m", t.merchant).put("c", t.cat).put("k", t.kind)
                        .put("s", t.src).put("ok", t.confirmed).put("r", t.ref)
                        .put("a", t.acct).put("b", t.bank).put("del", t.deleted)
                )
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
    }

    const val SEEN = 0
    const val ADDED = 1
    const val MERGED = 2
    const val HELD = 3

    /**
     * Adds a payment unless it was already seen. The same payment arriving from another source
     * (bank SMS, bank mail, receipt mail) is merged into one entry - see Dedup for the rules.
     */
    fun add(t: Txn): Int {
        synchronized(lock) {
            if (seen.contains(t.id)) return SEEN
            val dup = Dedup.find(txns, t)
            if (dup != null) {
                seen.add(t.id)
                Dedup.merge(dup, t)
                save()
                return MERGED
            }
            // A receipt mail is not a debit: it only names a payment already seen from the bank.
            // Not marked seen, so it can still be matched if the bank alert arrives later.
            if (t.src == "receipt") return HELD
            seen.add(t.id)
            txns.add(t)
            save()
            return ADDED
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
