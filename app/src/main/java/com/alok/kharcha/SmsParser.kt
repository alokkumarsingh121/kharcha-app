package com.alok.kharcha

object SmsParser {

    private val skipWords = listOf(
        "otp", "one time password", "will be debited", "due on", "is due", "failed",
        "declined", "reversed", "requested", "collect request", "unsuccessful"
    )
    private val debitWords = listOf(
        "debited", "spent", "paid", "sent rs", "sent inr", "withdrawn",
        "used for a transaction", "purchase of", "txn of"
    )
    private val creditWords = listOf("credited", "received", "refund", "deposited")

    private val debitAmount = Regex(
        "(?:debited|spent|paid|withdrawn|sent)\\s*(?:by|for|with|of)?\\s*(?:rs\\.?|inr|₹)?\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)",
        RegexOption.IGNORE_CASE
    )
    private val anyAmount = Regex(
        "(?:rs\\.?|inr|₹)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)",
        RegexOption.IGNORE_CASE
    )
    private val infoField = Regex("Info:\\s*(.+?)(?:\\.\\s+(?:The|Avl|Available|If|Not)|\\s*$)", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val toField = Regex("\\b(?:to|at)\\s+([A-Za-z0-9][A-Za-z0-9 .&@_-]{1,28})", RegexOption.IGNORE_CASE)
    private val prefixJunk = Regex("^(?:UPI|IMPS|NEFT|POS|ECOM|VPA)[-/ ]*[0-9]*[-/ ]*", RegexOption.IGNORE_CASE)

    fun parse(sender: String, body: String, ts: Long): Txn? {
        val text = body.replace('\n', ' ').trim()
        val low = text.lowercase()
        if (skipWords.any { low.contains(it) }) return null
        val isDebit = debitWords.any { low.contains(it) }
        val isCredit = creditWords.any { low.contains(it) }
        if (!isDebit) return null
        if (isCredit && !low.contains("debited") && !low.contains("spent")) return null

        val amtStr = debitAmount.find(text)?.groupValues?.get(1)
            ?: anyAmount.find(text)?.groupValues?.get(1)
            ?: return null
        val amt = amtStr.replace(",", "").toDoubleOrNull() ?: return null
        if (amt <= 0) return null

        var merchant: String = infoField.find(text)?.groupValues?.get(1)?.trim().orEmpty()
        if (merchant.isBlank()) {
            merchant = toField.find(text)?.groupValues?.get(1)?.trim().orEmpty()
        }
        if (merchant.isBlank()) merchant = sender
        merchant = merchant.replace(prefixJunk, "").trim().trimEnd('.', '-', ' ')
        if (merchant.isBlank()) merchant = sender
        if (merchant.length > 30) merchant = merchant.substring(0, 30)

        val cat = guessCat(merchant.lowercase())
        return Txn(
            id = "sms:" + body.hashCode() + "-" + body.length,
            ts = ts,
            amt = amt,
            merchant = merchant,
            cat = cat.id,
            kind = cat.kind,
            src = "sms",
            confirmed = false
        )
    }

    private fun guessCat(m: String): Cat {
        fun has(vararg w: String) = w.any { m.contains(it) }
        val id = when {
            has("rapido", "uber", "ola", "irctc", "metro", "redbus", "petrol", "fuel", "hpcl", "bpcl", "iocl", "fastag") -> "travel"
            has("swiggy", "zomato", "domino", "mcdonald", "kfc", "pizza", "restaurant", "burger", "dhaba", "biryani") -> "eatout"
            has("chai", "tea", "cafe", "coffee", "bakery", "sweet", "snack", "juice") -> "chai"
            has("blinkit", "zepto", "bigbasket", "instamart", "kirana", "mart", "sabzi", "vegetable", "dairy", "milk") -> "grocery"
            has("amazon", "flipkart", "myntra", "ajio", "meesho", "nykaa") -> "shop"
            has("electricity", "bijli", "uppcl", "gas", "water", "rent", "emi", "insurance") -> "bills"
            has("jio", "airtel", "vodafone", "bsnl", "recharge", "netflix", "hotstar", "prime", "spotify", "youtube") -> "subs"
            has("pharmacy", "medical", "apollo", "medplus", "hospital", "clinic") -> "health"
            has("bookmyshow", "pvr", "inox") -> "fun"
            else -> "other"
        }
        return Cats.of(id)
    }
}
