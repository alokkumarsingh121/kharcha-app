package com.alok.kharcha

// Plain data types. No Android imports here, so the parser and its tests run on any JVM.

data class Cat(val id: String, val name: String, val emoji: String, val kind: String, val tip: String)

object Cats {
    val list = listOf(
        Cat("grocery", "Groceries", "🥬", "need", "Make a weekly list and buy once; local markets are cheaper."),
        Cat("eatout", "Eating out / Delivery", "🍔", "want", "Delivery fees make food 30-40% costlier. Keep it to twice a week, cook the rest."),
        Cat("chai", "Tea / Snacks", "☕", "want", "2-3 tea/snack stops a day add up to thousands a month. Carry snacks or a flask."),
        Cat("travel", "Travel / Fuel", "🚗", "need", "For short trips use bus, e-rickshaw or metro instead of Rapido/cab, or share a ride."),
        Cat("shop", "Shopping", "🛍️", "want", "24-hour rule: wait a day before buying; buy only if you still want it."),
        Cat("bills", "Bills / Rent / EMI", "🧾", "need", "Avoid late fees with due-date reminders."),
        Cat("fun", "Entertainment", "🎬", "want", "Give movies and outings a fixed monthly limit."),
        Cat("health", "Health", "💊", "need", "Generic medicines (Jan Aushadhi) are much cheaper; ask your doctor."),
        Cat("subs", "Recharge / Subscriptions", "📱", "want", "Cancel subscriptions you don't use."),
        Cat("cash", "ATM / Cash withdrawal", "🏧", "need", "Cash spends are not tracked automatically; add big cash spends by hand."),
        Cat("transfer", "Money sent to people", "👤", "want", "Note what each transfer was for so you can see where the money goes."),
        Cat("notspend", "Card bill / Own transfer", "🔁", "skip", "Paying your credit card bill or moving money to your own account is not new spending, so it is not counted."),
        Cat("other", "Other", "📦", "want", "Give big 'Other' expenses a proper category to see where money goes.")
    )

    /** Unknown ids fall back to "Other" (kept last in the list). */
    fun of(id: String): Cat = list.firstOrNull { it.id == id } ?: list.last()
}

object Kinds {
    val order = listOf("need", "want", "waste", "skip")
    fun label(k: String) = when (k) {
        "need" -> "Essential"
        "want" -> "Optional"
        "waste" -> "Avoidable"
        else -> "Not spending"
    }
    fun color(k: String) = when (k) {
        "need" -> 0xFF2F6FDB.toInt()
        "want" -> 0xFFE0A21A.toInt()
        "waste" -> 0xFFD8472F.toInt()
        else -> 0xFF8A8F98.toInt()
    }
    fun next(k: String) = order[(order.indexOf(k).coerceAtLeast(0) + 1) % order.size]
}

/**
 * One payment. src is "sms" (bank SMS), "email" (bank alert mail), "receipt" (Swiggy/Rapido/... receipt mail)
 * or "manual", or a combination like "sms+email" once duplicates of one payment are merged.
 * ref = UPI/RRN/reference number, acct = last 4 digits of the account or card, bank = "Union Bank", "ICICI Card"...
 * kind "skip" (card bill, own transfer) is shown but never counted. deleted entries are kept hidden so the
 * same payment arriving again from the other source is recognised and not added back.
 */
data class Txn(
    val id: String,
    var ts: Long,
    val amt: Double,
    var merchant: String,
    var cat: String,
    var kind: String,
    var src: String,
    var confirmed: Boolean,
    var ref: String = "",
    var acct: String = "",
    var bank: String = "",
    var deleted: Boolean = false
) {
    /** The original bank SMS / mail texts this entry was read from (one per source, merged ones together). */
    val msgs: MutableList<Msg> = mutableListOf()

    val counted: Boolean get() = !deleted && kind != "skip"
    fun displayName(): String = merchant.ifBlank { if (bank.isNotBlank()) "Payment · $bank" else "Payment" }
}

/**
 * One original message behind an entry. key = the message's own id ("sms:…", "email:<Message-ID>", "receipt:…"),
 * src = "sms" / "email" / "receipt", from = sender, ts = when it arrived, text = SMS text or the useful part of the mail.
 */
data class Msg(val key: String, val src: String, val from: String, val ts: Long, val subject: String, val text: String)

data class Saving(val id: String, val ts: Long, val amt: Double, val note: String)
