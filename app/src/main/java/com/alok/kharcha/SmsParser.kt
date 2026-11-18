package com.alok.kharcha

object SmsParser {

    // Messages that are not completed debits.
    private val skipWords = listOf(
        "will be debited", "due on", "is due", "failed", "declined", "reversed",
        "requested", "collect request", "unsuccessful", "insufficient"
    )

    // A real OTP message (code next to the word OTP). Bank debit alerts often only carry a
    // "never share OTP" warning, so the word OTP alone must not reject a message.
    private val otpMsg = Regex(
        "\\botp\\b[^.]{0,25}\\b[0-9]{4,8}\\b|\\b[0-9]{4,8}\\b[^.]{0,25}\\botp\\b|one[- ]time password",
        RegexOption.IGNORE_CASE
    )

    // Clear signs of money going out: debited, spent, sent Rs, Dr. from, transferred, trf to...
    private val strongDebit = Regex(
        "debited|\\bdebit\\b|\\bspent\\b|\\bsent\\s*(?:rs|inr|₹)|withdrawn|transferred|\\btrf\\b|" +
            "\\bdr\\.?\\s*(?:from|to|a/c|ac|rs|inr|₹)|deducted|paid\\s+to|\\bpaid\\b|transfer\\s+to",
        RegexOption.IGNORE_CASE
    )
    private val weakDebitWords = listOf(
        "used for a transaction", "purchase of", "txn of", "payment of rs", "payment of inr"
    )
    private val creditWords = listOf("credited", "received", "refund", "deposited")

    private const val CUR = "(?:rs\\.?|inr|₹)\\s*[:\\-]?\\s*"
    private const val NUM = "([0-9][0-9,]*(?:\\.[0-9]{1,2})?)"

    private val debitAmount = Regex(
        "(?:debited|debit|spent|paid|withdrawn|sent|transferred|deducted)\\s*(?:by|for|with|of)?\\s*(?:$CUR)?$NUM",
        RegexOption.IGNORE_CASE
    )
    private val anyAmount = Regex(CUR + NUM, RegexOption.IGNORE_CASE)
    private val infoField = Regex(
        "Info:\\s*(.+?)(?:\\.\\s+(?:The|Avl|Available|If|Not)|\\s*$)",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val upiName = Regex("(?:UPI|IMPS)[-/ ]*[0-9]{9,16}[-/ ]*([A-Za-z][A-Za-z0-9 &_-]{1,28})", RegexOption.IGNORE_CASE)
    private val toField = Regex("\\b(?:to|at)\\s+([A-Za-z0-9][A-Za-z0-9 .&@_-]{1,34})", RegexOption.IGNORE_CASE)
    private val prefixJunk = Regex("^(?:UPI|IMPS|NEFT|RTGS|POS|ECOM|VPA)[-/ ]*[0-9]*[-/ ]*", RegexOption.IGNORE_CASE)
    private val tailCut = Regex(
        "\\s+(?:on|ref|refno|ref\\.|upi|imps|neft|rtgs|via|dt|date|a/c|ac|trf|if|not|call|never|do|avl|available|bal|sms|dial|fwd|your|account)\\b.*$",
        RegexOption.IGNORE_CASE
    )
    private val axisUpi = Regex("UPI/P2[AM]/[0-9]+/([A-Za-z][A-Za-z0-9 ]{1,28})", RegexOption.IGNORE_CASE)
    private val fvField = Regex("\\bFv\\.\\s*([A-Za-z][A-Za-z0-9 .&_-]{0,28}?)\\s*(?:,|$)", RegexOption.IGNORE_CASE)
    private val refField = Regex("(?:UPI|IMPS|NEFT|RTGS|MB|RRN|Ref(?:\\s*No)?\\.?)[-/:# ]*([0-9]{9,16})", RegexOption.IGNORE_CASE)

    /** Parses a bank debit alert (SMS body or alert-email text). Returns null if it is not a debit. */
    fun parse(sender: String, body: String, ts: Long, src: String = "sms", idKey: String? = null): Txn? {
        val text = body.replace('\n', ' ').trim()
        val low = text.lowercase()
        if (skipWords.any { low.contains(it) }) return null
        val strong = strongDebit.containsMatchIn(text)
        if (!strong && otpMsg.containsMatchIn(text)) return null
        val isDebit = strong || weakDebitWords.any { low.contains(it) }
        if (!isDebit) return null
        val isCredit = creditWords.any { low.contains(it) }
        if (isCredit && !strong) return null

        val amtStr = debitAmount.find(text)?.groupValues?.get(1)
            ?: anyAmount.find(text)?.groupValues?.get(1)
            ?: return null
        val amt = amtStr.replace(",", "").toDoubleOrNull() ?: return null
        if (amt <= 0) return null

        // Structured fields (Info:, UPI/..., UPI-ref-name) already hold a clean name.
        var merchant: String = infoField.find(text)?.groupValues?.get(1)?.trim().orEmpty()
        if (merchant.isBlank()) merchant = fvField.find(text)?.groupValues?.get(1)?.trim().orEmpty()
        if (merchant.isBlank()) {
            val a = axisUpi.find(text)?.groupValues?.get(1).orEmpty()
            merchant = (" $a").replace(tailCut, "").trim()
        }
        if (merchant.isBlank()) merchant = upiName.find(text)?.groupValues?.get(1)?.trim().orEmpty()
        merchant = merchant.replace(prefixJunk, "").trim().trimEnd('.', '-', ' ')
        if (merchant.isBlank()) {
            // Free text like "... trf to RAJESH KUMAR Ref No 123 ...": cut at the first filler word.
            val raw = toField.find(text)?.groupValues?.get(1)?.trim().orEmpty()
            merchant = (" " + raw.replace(prefixJunk, "")).replace(tailCut, "").trim().trimEnd('.', '-', ' ')
        }
        if (merchant.isNotEmpty() && merchant.all { it.isDigit() || it == ' ' }) merchant = ""
        if (merchant.isBlank()) merchant = cleanSender(sender)
        if (merchant.length > 30) merchant = merchant.substring(0, 30)

        val ref = refField.find(text)?.groupValues?.get(1).orEmpty()
        val cat = guessCat(merchant.lowercase())
        return Txn(
            id = src + ":" + (idKey ?: (body.hashCode().toString() + "-" + body.length)),
            ts = ts,
            amt = amt,
            merchant = merchant,
            cat = cat.id,
            kind = cat.kind,
            src = src,
            confirmed = false,
            ref = ref
        )
    }

    private fun cleanSender(s: String): String {
        val core = s.replace(Regex("^[A-Z]{2}-"), "").replace(Regex("-[A-Z]$"), "")
        return when {
            core.contains("UNION", true) || core.contains("ubi.bank", true) -> "Union Bank (naam nahi)"
            core.contains("ICICI", true) -> "ICICI (naam nahi)"
            core.contains("SBI", true) -> "SBI (naam nahi)"
            core.contains("HDFC", true) -> "HDFC (naam nahi)"
            else -> core
        }
    }

    private val fieldAmount = Regex("Amount\\s*:\\s*(?:rs\\.?|inr|₹)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)", RegexOption.IGNORE_CASE)
    private val fieldPayee = Regex("Payee\\s*Name\\s*:\\s*(.+?)\\s+(?:[0-9]+\\.|Amount)", RegexOption.IGNORE_CASE)

    /**
     * Table-style bank alert mails, e.g. Union Bank "DEBIT TRANSACTION ALERT":
     * "1. Payee Name : SHIVANCH 2. Amount : Rs. 1500.00 ... Transaction ID/RRN : 2405... Status : Success".
     */
    fun parseAlertMail(from: String, subject: String, text: String, ts: Long, idKey: String): Txn? {
        val subj = subject.lowercase()
        if (!subj.contains("debit") || subj.contains("credit")) return null
        val low = text.lowercase()
        if (Regex("status\\s*:\\s*(?:fail|declin|reject|pending)").containsMatchIn(low)) return null
        val amt = fieldAmount.find(text)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull() ?: return null
        if (amt <= 0) return null
        var payee = fieldPayee.find(text)?.groupValues?.get(1)?.trim().orEmpty()
        if (payee.isBlank()) payee = cleanSender(from)
        if (payee.length > 30) payee = payee.substring(0, 30)
        val ref = refField.find(text)?.groupValues?.get(1).orEmpty()
        val cat = guessCat(payee.lowercase())
        return Txn("email:$idKey", ts, amt, payee, cat.id, cat.kind, "email", false, ref)
    }

    private val brands = mapOf(
        "rapido" to "Rapido", "uber" to "Uber", "olacabs" to "Ola", "swiggy" to "Swiggy",
        "zomato" to "Zomato", "irctc" to "IRCTC", "blinkit" to "Blinkit", "zepto" to "Zepto",
        "amazon" to "Amazon", "flipkart" to "Flipkart", "bigbasket" to "BigBasket"
    )
    private val receiptWords = listOf("receipt", "invoice", "order", "ride", "trip", "booking confirmation", "ticket", "delivered", "payment")
    private val promoWords = listOf("offer", "sale", "% off", "discount", "deal", "coupon", "cashback", "save up")
    private val receiptAmount = Regex(
        "(?:grand total|total fare|total paid|amount paid|total amount|total|fare|paid)[^0-9₹]{0,40}(?:₹|rs\\.?|inr)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)",
        RegexOption.IGNORE_CASE
    )

    /** Parses a merchant receipt email (Rapido, Swiggy, IRCTC...). Returns null for promos / non-receipts. */
    fun parseReceipt(from: String, subject: String, body: String, ts: Long, idKey: String): Txn? {
        val f = from.lowercase()
        val brand = brands.entries.firstOrNull { f.contains(it.key) }?.value ?: return null
        val subj = subject.lowercase()
        if (promoWords.any { subj.contains(it) }) return null
        if (!receiptWords.any { subj.contains(it) }) return null
        val amt = receiptAmount.find(body)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull() ?: return null
        if (amt <= 0) return null
        val cat = guessCat(brand.lowercase())
        return Txn("receipt:$idKey", ts, amt, brand, cat.id, cat.kind, "receipt", false, "")
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
