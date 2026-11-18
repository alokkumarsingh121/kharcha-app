package com.alok.kharcha

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class Cat(val id: String, val name: String, val emoji: String, val kind: String, val tip: String)

object Cats {
    val list = listOf(
        Cat("grocery", "Rashan / Sabzi", "🥬", "need", "Hafte ka list banake ek hi baar kharido, mandi se lo."),
        Cat("eatout", "Bahar khana / Delivery", "🍔", "want", "Delivery mein charges jodke 30-40% mehenga padta hai. Hafte mein 2 baar tak rakho, baaki ghar pe banao."),
        Cat("chai", "Chai / Snacks", "☕", "want", "Roz 2-3 baar bahar ki chai/snacks mahine mein hazaaron ban jaate hain. Ghar se nashta ya thermos lo."),
        Cat("travel", "Travel / Petrol", "🚗", "need", "Chhoti doori ke liye Rapido/cab ki jagah bus, e-rickshaw ya metro lo, ya ride share karo."),
        Cat("shop", "Shopping", "🛍️", "want", "24 ghante ka rule: pasand aaye to ek din ruko, phir bhi chahiye to lo."),
        Cat("bills", "Bill / Rent / EMI", "🧾", "need", "Late fee se bacho, due date pe reminder lagao."),
        Cat("fun", "Entertainment", "🎬", "want", "Movie/outing ko mahine ka fixed limit do."),
        Cat("health", "Health", "💊", "need", "Generic dawai (Jan Aushadhi) sasti hoti hai, doctor se pooch lo."),
        Cat("subs", "Recharge / Subscription", "📱", "want", "Jo subscription use nahi ho rahe unhe band karo."),
        Cat("other", "Other", "📦", "want", "Bade 'Other' kharche ko sahi category do taaki pata chale paisa kaha ja raha hai.")
    )

    fun of(id: String): Cat = list.firstOrNull { it.id == id } ?: list.last()
}

object Kinds {
    val order = listOf("need", "want", "waste")
    fun label(k: String) = when (k) {
        "need" -> "Zaroori"
        "want" -> "Optional"
        else -> "Bacha sakte the"
    }
    fun color(k: String) = when (k) {
        "need" -> 0xFF2F6FDB.toInt()
        "want" -> 0xFFE0A21A.toInt()
        else -> 0xFFD8472F.toInt()
    }
    fun next(k: String) = order[(order.indexOf(k) + 1) % order.size]
}

data class Txn(
    val id: String,
    val ts: Long,
    val amt: Double,
    var merchant: String,
    var cat: String,
    var kind: String,
    val src: String,
    var confirmed: Boolean
)

data class Saving(val id: String, val ts: Long, val amt: Double, val note: String)

object Store {
    private lateinit var sp: SharedPreferences
    private val lock = Any()
    private var ready = false

    var txns = mutableListOf<Txn>()
    var savings = mutableListOf<Saving>()
    var budget = 500

    fun init(ctx: Context) {
        synchronized(lock) {
            if (ready) return
            sp = ctx.getSharedPreferences("kharcha", Context.MODE_PRIVATE)
            load()
            ready = true
        }
    }

    private fun load() {
        budget = sp.getInt("budget", 500)
        txns = mutableListOf()
        savings = mutableListOf()
        try {
            val a = JSONArray(sp.getString("txns", "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                txns.add(
                    Txn(
                        o.getString("id"), o.getLong("ts"), o.getDouble("amt"),
                        o.getString("m"), o.getString("c"), o.getString("k"),
                        o.getString("s"), o.getBoolean("ok")
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
                        .put("s", t.src).put("ok", t.confirmed)
                )
            }
            val b = JSONArray()
            for (s in savings) {
                b.put(JSONObject().put("id", s.id).put("ts", s.ts).put("amt", s.amt).put("n", s.note))
            }
            sp.edit().putString("txns", a.toString()).putString("savings", b.toString())
                .putInt("budget", budget).apply()
        }
    }

    fun addTxnIfNew(t: Txn): Boolean {
        synchronized(lock) {
            if (txns.any { it.id == t.id }) return false
            txns.add(t)
            save()
            return true
        }
    }

    fun addSaving(s: Saving) {
        synchronized(lock) {
            savings.add(s)
            save()
        }
    }
}
