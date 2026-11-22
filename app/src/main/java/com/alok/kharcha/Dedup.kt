package com.alok.kharcha

/**
 * Decides when two records are the same payment (bank SMS + bank mail + receipt mail) and merges them.
 * Rules, strongest first:
 *  1. Same reference number (UPI ref / RRN) and same amount -> same payment, whatever the source.
 *  2. Both have reference numbers but they differ -> different payments (two real Rs 87 payments stay two).
 *  3. Different account/card digits -> different payments.
 *  4. Otherwise same amount from different kinds of source within 15 minutes (3 hours for receipt mails).
 */
object Dedup {
    private const val MIN = 60_000L

    fun sameRef(a: String, b: String) =
        a.isNotEmpty() && b.isNotEmpty() && (a == b || (a.length >= 8 && b.length >= 8 && a.takeLast(8) == b.takeLast(8)))

    private fun srcs(t: Txn) = t.src.split("+").filter { it.isNotEmpty() }.toSet()

    fun isSame(a: Txn, b: Txn): Boolean {
        if (Math.abs(a.amt - b.amt) > 0.009) return false
        if (a.ref.isNotEmpty() && b.ref.isNotEmpty()) return sameRef(a.ref, b.ref)
        if (a.acct.isNotEmpty() && b.acct.isNotEmpty() && a.acct != b.acct) return false
        val sa = srcs(a)
        val sb = srcs(b)
        if (sa.intersect(sb).isNotEmpty()) return false
        val win = if ("receipt" in sa || "receipt" in sb) 180 * MIN else 15 * MIN
        return Math.abs(a.ts - b.ts) <= win
    }

    /** The existing record (including deleted ones) that t duplicates, or null. Manual entries never merge. */
    fun find(existing: List<Txn>, t: Txn): Txn? {
        if (t.src == "manual") return null
        val pool = existing.filter { it.src != "manual" }
        pool.firstOrNull { sameRef(it.ref, t.ref) && Math.abs(it.amt - t.amt) < 0.01 }?.let { return it }
        return pool.filter { isSame(it, t) }.minByOrNull { Math.abs(it.ts - t.ts) }
    }

    private fun isWeakName(n: String) = n.isBlank() || n.all { it.isDigit() || it == ' ' } || n.contains("(no name)")

    /** Prefers a real name over a blank one and a fuller name ("MOHANLAL") over a cut one ("MOHAN"). */
    fun betterName(old: String, new: String, forceNew: Boolean): String {
        if (isWeakName(new)) return old
        if (forceNew || isWeakName(old)) return new
        val o = old.lowercase().trim()
        val n = new.lowercase().trim()
        if (n.length > o.length && (n.startsWith(o) || (o.length >= 3 && n.startsWith(o.take(3))))) return new
        return old
    }

    fun merge(into: Txn, t: Txn) {
        into.src = (into.src.split("+") + t.src.split("+")).filter { it.isNotEmpty() }.distinct().joinToString("+")
        if (into.ref.isEmpty()) into.ref = t.ref
        if (into.acct.isEmpty()) into.acct = t.acct
        if (into.bank.isEmpty()) into.bank = t.bank
        val oldName = into.merchant
        into.merchant = betterName(into.merchant, t.merchant, t.src == "receipt")
        if (into.confirmed) return
        when {
            t.kind == "skip" -> { into.kind = "skip"; into.cat = t.cat }
            into.kind == "skip" -> {}
            t.src == "receipt" || (into.cat == "other" && t.cat != "other") -> { into.cat = t.cat; into.kind = t.kind }
            into.merchant != oldName && into.cat == "other" -> {
                val c = Parser.guessCat(into.merchant)
                into.cat = c.id; into.kind = c.kind
            }
        }
    }
}
