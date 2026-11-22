package com.alok.kharcha

import java.util.Calendar

/** What reading one message gave: a debit, or the reason it was not counted. */
class Parsed(val txn: Txn?, val reason: String, val owner: String = "")

/**
 * Reads bank SMS and bank alert mails and keeps only completed debits (money that actually left an
 * account or a card). Pure Kotlin, so every rule here is covered by unit tests (ParserCases).
 */
object Parser {
    private val I = RegexOption.IGNORE_CASE

    /** Owner's own names, learned from bank mails ("Dear RAVI KUMAR ..."), to spot transfers to self. */
    @Volatile var ownerNames: List<String> = emptyList()

    // ---------------- amounts ----------------
    // (?<![A-Za-z]) keeps "rs" inside words (hours, users, MRS) from being read as rupees.
    private val amountRx = Regex("(?<![A-Za-z])(?:rs|inr|₹)\\.?\\s*[:\\-]?\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)", I)
    // "debited by 76.0" (SBI writes no currency)
    private val bareAmountRx = Regex(
        "\\b(?:debited|spent|withdrawn|deducted|paid|sent)\\s+(?:by|for|of|with)?\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)\\b(?!\\s*[-/:])", I
    )
    // Text just before an amount that marks it as a balance or a limit, not the payment.
    private val balanceBefore = Regex(
        "(?:\\bbal\\b|balance|\\bavl|avail|available|limit|outstanding|\\bo/s\\b|\\bdue\\b|minimum|closing|opening)[^0-9]{0,26}$", I
    )

    private fun pickAmount(t: String): Double? {
        for (m in amountRx.findAll(t)) {
            val before = t.substring(maxOf(0, m.range.first - 32), m.range.first)
            if (balanceBefore.containsMatchIn(before)) continue
            val v = m.groupValues[1].replace(",", "").trimEnd('.').toDoubleOrNull() ?: continue
            if (v > 0) return v
        }
        val b = bareAmountRx.find(t)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()
        return if (b != null && b > 0) b else null
    }

    // ---------------- debit or not ----------------
    private val debitRx = Regex(
        "\\bdebited\\b|\\bdebit\\s+(?:of|for|by|from)\\b|\\bdr\\b|\\bspent\\b|\\bwithdrawn\\b|\\bwithdrawal\\b|\\bdeducted\\b|" +
            "\\btransferred\\b|\\btrf\\b|\\bsent\\b|\\bpaid\\b|\\bused for\\b|\\btransaction of\\b|\\btxn of\\b|\\bpurchase\\b|\\bcharged\\b", I
    )
    // Words that alone prove the money already went (used to override reminder-like words such as "mandate").
    private val firmDebitRx = Regex(
        "\\bdebited\\b|\\bdr\\b|\\bspent\\b|\\bwithdrawn\\b|\\bdeducted\\b|\\bused for a transaction\\b|\\btransferred\\b|" +
            "\\bsent\\s*(?:rs|inr|₹)|\\bpaid\\s*(?:rs|inr|₹)|\\bpaid\\s+to\\b", I
    )
    private val creditRx = Regex(
        "\\bcredited\\b|\\breceived\\b|\\bdeposited\\b|\\brefund(?:ed)?\\b|\\breversed\\b|\\breversal\\b|\\bcashback\\b|\\bcr\\b", I
    )
    // Never a completed debit, whatever else the message says.
    private val hardSkip = Regex(
        "\\bdeclined\\b|\\bfailed\\b|\\bunsuccessful\\b|\\binsufficient\\b|\\bcould not be\\b|\\bnot (?:been )?processed\\b|" +
            "\\b(?:will|would|to) be (?:debited|deducted|charged)\\b|\\bcollect request\\b|\\bhas requested\\b|\\bpayment request\\b|" +
            "\\bis requesting\\b|\\btrade confirmation\\b|\\bcontract note\\b|\\btrade bal\\b|\\bpre-?approved\\b|\\bloan offer\\b", I
    )
    // Reminders, statements, mandate set-ups: skipped unless a firm debit word is present.
    private val softSkip = Regex(
        "\\bdue\\b|\\bstatement\\b|\\bmandate\\b|\\bauto-?pay\\b|\\bregistered\\b|\\breminder\\b|\\boffer\\b|\\bexpir|" +
            "\\bvalidity\\b|\\brecharge now\\b|\\bpay now\\b", I
    )
    private val otpRx = Regex("\\b(?:otp|one[- ]time password|verification code|passcode)\\b", I)
    private val codeRx = Regex("(?<![0-9.,])[0-9]{4,8}(?![0-9.,])")
    // Something that ties the message to an account, card, UPI or wallet.
    private val acctCtx = Regex(
        "a/c|\\bac\\b|acct|account|\\bcard\\b|\\bupi\\b|\\bvpa\\b|wallet|\\bbank\\b|\\bneft\\b|\\bimps\\b|\\brtgs\\b|\\batm\\b|\\bpos\\b|" +
            "paytm|phonepe|gpay|google pay|amazon pay|mobikwik|freecharge|simpl|lazypay|@", I
    )

    // ---------------- payee ----------------
    private const val STOP = "(?:on|ref|refno|upi|imps|neft|rtgs|via|dt|date|a/c|ac|from|if|not|avl|bal|using|thru|with|txn|info|call|sms|new|total|is|has|was|the|and)"
    private val payeeRx = listOf(
        Regex("Info:\\s*(?:(?:UPI|IMPS|NEFT|POS|ECOM|BIL|ACH)[-/ ]*[0-9]*[-/ ]*)?([^.]{2,40}?)\\s*(?:\\.\\s|\\.$|$)", I),  // ICICI
        Regex("Payee\\s*Name\\s*:\\s*(.+?)\\s+(?:[0-9]+\\.|Amount\\b)", I),                                                // Union Bank mail
        Regex("\\bFv\\.\\s*([^,]{1,40}?)\\s*,", I),                                                                       // Union Bank SMS
        Regex("UPI/(?:[A-Z0-9]{2,4}/)?[0-9]{6,}/([^/]{2,40}?)(?:/|\\.\\s|\\.$|\\s{2,}|\\s+(?:Not|on|SMS|Avl|Bal|New|If)\\b|$)", I),
        Regex(";\\s*([A-Za-z0-9 .&@_-]{2,40}?)\\s+credited\\b", I),                                                        // ICICI savings
        Regex("\\bcredited to\\s+(?:VPA\\s+|A/c\\s+)?([A-Za-z][A-Za-z0-9.&@_ -]{1,40}?)(?:\\s*\\(|\\.\\s|\\.$|\\s+$STOP\\b|$)", I),
        Regex("\\bCr\\.?\\s+to\\s+([A-Za-z][A-Za-z0-9.&@_ -]{1,40}?)(?:\\.\\s|\\.$|\\s*\\(|\\s+$STOP\\b|$)", I),
        Regex("\\b(?:trf to|paid to|sent to|transfer to|to|towards|at)\\s+(?:VPA\\s+)?(?!(?:rs|inr|your|a/c|ac|account|upi)\\b)([A-Za-z][A-Za-z0-9.&@_ -]{1,40}?)(?:\\s*\\(|\\.\\s|\\.$|,|\\s+$STOP\\b|$)", I),
        Regex("UPI-[0-9]{6,}-([A-Za-z][A-Za-z0-9 &_-]{1,30})", I)
    )

    private fun pickPayee(t: String): String {
        for (rx in payeeRx) {
            val raw = rx.find(t)?.groupValues?.get(1)?.trim() ?: continue
            val clean = raw.replace(Regex("^(?:VPA|UPI|IMPS|NEFT|RTGS|POS)[-/: ]+", I), "")
                .replace(Regex("^[0-9]{6,}[-/ ]+"), "")
                .trim().trimEnd('.', '-', ',', ' ', '*')
            if (clean.length < 2) continue
            if (clean.all { it.isDigit() || it == ' ' || it == '-' }) continue   // "Fv.86012": number, not a name
            if (Regex("^(?:atm|xx|card|bank)$", I).matches(clean)) continue
            return if (clean.length > 30) clean.substring(0, 30).trim() else clean
        }
        return ""
    }

    // ---------------- reference, account, bank ----------------
    private val refRx = listOf(
        Regex("UPI/(?:[A-Z0-9]{2,4}/)?([0-9]{9,16})", I),
        Regex("(?:\\bRRN|\\bUTR|\\bMB|\\bUPI(?:\\s*Ref(?:\\s*No)?)?|\\bIMPS|\\bNEFT|\\bRTGS|Ref(?:\\s*No)?|Refno|Ref\\.?\\s*No)[\\s:#.\\-]*([0-9]{9,16})", I),
        Regex("Transaction ID\\s*/?\\s*RRN\\s*:\\s*([0-9]{6,16})", I)
    )
    private fun pickRef(t: String): String {
        for (rx in refRx) rx.find(t)?.groupValues?.get(1)?.let { return it }
        return ""
    }

    private val acctRx = Regex(
        "(?:a/c|\\bac\\b|acct|account|\\bcard\\b)[^A-Za-z0-9]{0,4}(?:no\\.?|number|num)?[^A-Za-z0-9]{0,4}(?:ending(?:\\s+with)?\\s*)?([0-9xX*]{3,20})", I
    )
    private fun pickAcct(t: String): String {
        for (m in acctRx.findAll(t)) {
            val digits = Regex("[0-9]+$").find(m.groupValues[1])?.value ?: continue
            if (digits.length >= 3) return digits.takeLast(4)
        }
        return ""
    }

    private val bankNames = listOf(
        "unionb" to "Union Bank", "ubi.bank" to "Union Bank", "unionbank" to "Union Bank", "union bank" to "Union Bank",
        "icici" to "ICICI", "hdfc" to "HDFC", "sbi" to "SBI", "axis" to "Axis", "kotak" to "Kotak", "pnb" to "PNB",
        "baroda" to "Bank of Baroda", "bob" to "Bank of Baroda", "canbnk" to "Canara", "canara" to "Canara",
        "idfc" to "IDFC First", "yesbnk" to "Yes Bank", "yesbank" to "Yes Bank", "indus" to "IndusInd",
        "fedbnk" to "Federal", "federal" to "Federal", "aubank" to "AU Bank", "rbl" to "RBL", "paytm" to "Paytm",
        "airtel" to "Airtel Payments Bank", "centbk" to "Central Bank", "iobchn" to "IOB", "ucobnk" to "UCO"
    )
    /** Mail senders treated as banks (also used to search Gmail). */
    val bankMailKeys = listOf(
        "ubi.bank", "unionbank", "icici", "hdfc", "sbi", "axis", "kotak", "pnb", "bankofbaroda", "bobcard",
        "canarabank", "idfc", "yesbank", "indusind", "federalbank", "aubank", "rblbank", "paytm", "airtel"
    )

    private fun bankOf(sender: String, text: String): String {
        val s = sender.lowercase()
        var name = bankNames.firstOrNull { s.contains(it.first) }?.second
            ?: bankNames.firstOrNull { text.lowercase().contains(it.first + " bank") }?.second
            ?: ""
        // "Card XX7006", "Card 5678", "Card ending 1234" -> a card payment (not the "never share your card number" footer)
        val cardUse = Regex("\\bcard\\b[^A-Za-z0-9]{0,4}(?:no\\.?\\s*)?(?:ending(?:\\s+with)?\\s*)?[xX*]*[0-9]{3,}", I)
        if (name.isNotEmpty() && cardUse.containsMatchIn(text) && !name.contains("Card")) name += " Card"
        return name
    }

    // ---------------- time written in the message ----------------
    private val monthNames = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    private const val HMS = "([0-2]?[0-9]):([0-5][0-9])(?::([0-5][0-9]))?\\s*([ap]\\.?m\\.?)?"
    private val tNumeric = Regex("\\b([0-3]?[0-9])[-/.]([01]?[0-9])[-/.]((?:20)?[0-9]{2})\\b[ ,]*(?:at\\s*)?$HMS", I)
    private val tIso = Regex("\\b(20[0-9]{2})[-:/]([01][0-9])[-:/]([0-3][0-9])[ T:]+$HMS", I)
    private val tMon1 = Regex("\\b(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\.?\\s+([0-3]?[0-9]),?\\s+(20[0-9]{2})[ ,]*(?:at\\s*)?$HMS", I)
    private val tMon2 = Regex("\\b([0-3]?[0-9])[- ](jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*[- ,]+((?:20)?[0-9]{2})[ ,]*(?:at\\s*)?$HMS", I)

    /**
     * The payment time written inside the message, if it is close to when the message arrived. Times without
     * AM/PM in 12-hour style (ICICI writes 7:56 PM as "07:56:00") get the AM/PM nearest to the arrival time.
     * Falls back to the arrival time.
     */
    fun txnTime(text: String, received: Long): Long {
        fun year(s: String) = s.toInt().let { if (it < 100) 2000 + it else it }
        var y = 0; var mo = 0; var d = 0; var hms: List<String>? = null
        tIso.find(text)?.let { m -> y = m.groupValues[1].toInt(); mo = m.groupValues[2].toInt(); d = m.groupValues[3].toInt(); hms = m.groupValues.drop(4) }
        if (hms == null) tMon1.find(text)?.let { m -> mo = monthNames.indexOf(m.groupValues[1].lowercase().take(3)) + 1; d = m.groupValues[2].toInt(); y = year(m.groupValues[3]); hms = m.groupValues.drop(4) }
        if (hms == null) tMon2.find(text)?.let { m -> d = m.groupValues[1].toInt(); mo = monthNames.indexOf(m.groupValues[2].lowercase().take(3)) + 1; y = year(m.groupValues[3]); hms = m.groupValues.drop(4) }
        if (hms == null) tNumeric.find(text)?.let { m -> d = m.groupValues[1].toInt(); mo = m.groupValues[2].toInt(); y = year(m.groupValues[3]); hms = m.groupValues.drop(4) }
        val parts = hms ?: return received
        if (mo !in 1..12 || d !in 1..31) return received
        val h = parts[0].toIntOrNull() ?: return received
        val mi = parts[1].toIntOrNull() ?: return received
        val s = parts[2].toIntOrNull() ?: 0
        val ampm = parts[3].lowercase().replace(".", "")
        if (h > 23) return received
        fun at(hour: Int): Long {
            val c = Calendar.getInstance()
            c.set(y, mo - 1, d, hour, mi, s)
            c.set(Calendar.MILLISECOND, 0)
            return c.timeInMillis
        }
        val candidates = when {
            ampm == "am" -> listOf(at(h % 12))
            ampm == "pm" -> listOf(at(h % 12 + 12))
            h in 1..12 -> listOf(at(h % 12), at(h % 12 + 12))
            else -> listOf(at(h))
        }
        val late = received + 10 * 60_000L
        val early = received - 36 * 60 * 60_000L
        return candidates.filter { it in early..late }.maxOrNull() ?: received
    }

    // ---------------- own transfers and card bills ----------------
    private val honorific = setOf("MR", "MRS", "MS", "SHRI", "SMT", "KUMARI", "DR", "M", "S")
    private fun words(s: String) = s.uppercase().replace(Regex("[^A-Z ]"), " ").split(" ").filter { it.isNotBlank() }

    fun isOwnName(payee: String): Boolean {
        val p = words(payee).filter { it !in honorific }
        if (p.size < 2) return false
        return ownerNames.any { o ->
            val ow = words(o)
            ow.size >= 2 && p[0] == ow[0] && ow[1].startsWith(p[1])
        }
    }

    private val cardBillRx = Regex("^cred\\b|\\bcred club\\b|credit\\s*card\\s*(?:bill|payment)|\\bcc\\s*(?:bill|payment)|card\\s*bill", I)
    private val ownerRx = Regex("\\bDear\\s+\\*?([A-Z][A-Z.]+(?:\\s+[A-Z][A-Z.]*){1,4})\\*?\\s*,")
    private val notNames = setOf("CUSTOMER", "CARDMEMBER", "CARD MEMBER", "SIR", "MADAM", "USER", "MEMBER", "VALUED CUSTOMER", "SIR MADAM")

    fun ownerFrom(text: String): String {
        val n = ownerRx.find(text)?.groupValues?.get(1)?.trim()?.replace(Regex("\\s+"), " ") ?: return ""
        if (n in notNames || n.split(" ").size < 2) return ""
        return n
    }

    // ---------------- categories ----------------
    fun guessCat(name: String): Cat {
        val m = name.lowercase()
        fun has(vararg w: String) = w.any { m.contains(it) }
        val id = when {
            has("swiggy", "zomato", "domino", "mcdonald", "kfc", "pizza", "restaurant", "burger", "dhaba", "biryani", "hotel", "bhojnalaya", "eatery", "kitchen", "food") -> "eatout"
            has("rapido", "uber", "olacabs", "ola ", "irctc", "metro", "redbus", "petrol", "fuel", "hpcl", "bpcl", "iocl", "fastag", "railway", "travels") -> "travel"
            has("chai", "tea ", "cafe", "coffee", "bakery", "sweet", "snack", "juice", "chaat") -> "chai"
            has("blinkit", "zepto", "bigbasket", "instamart", "kirana", "mart", "sabzi", "vegetable", "dairy", "milk", "grocery", "dmart", "general store", "fruit") -> "grocery"
            has("amazon", "flipkart", "myntra", "ajio", "meesho", "nykaa", "store", "trends", "fashion", "shop") -> "shop"
            has("electricity", "bijli", "uppcl", "power", "gas", "water", "rent", "emi", "insurance", "lic ", "loan") -> "bills"
            has("jio", "airtel", "vodafone", "bsnl", "recharge", "netflix", "hotstar", "prime", "spotify", "youtube") -> "subs"
            has("pharmacy", "medical", "medicos", "chemist", "apollo", "medplus", "hospital", "clinic", "diagnostic", "pathlab") -> "health"
            has("bookmyshow", "pvr", "inox", "cinema") -> "fun"
            has("atm") -> "cash"
            else -> "other"
        }
        return Cats.of(id)
    }

    private fun finish(id: String, ts: Long, amt: Double, payee: String, src: String, ref: String, acct: String, bank: String, text: String): Txn {
        var cat = guessCat(payee)
        var kind = cat.kind
        if (cardBillRx.containsMatchIn(payee) || Regex("credit\\s*card\\s*bill|cc bill payment", I).containsMatchIn(text) || isOwnName(payee)) {
            cat = Cats.of("notspend"); kind = "skip"
        }
        return Txn(id, ts, amt, payee, cat.id, kind, src, false, ref, acct, bank)
    }

    private fun norm(s: String) = s.replace('\u00A0', ' ').replace(Regex("\\s+"), " ").trim()

    // ---------------- SMS ----------------

    fun parseSms(sender: String, body: String, received: Long): Parsed {
        val id = "sms:" + body.hashCode() + "-" + body.length
        return parseText(sender, norm(body), received, "sms", id)
    }

    private fun parseText(sender: String, t: String, received: Long, src: String, id: String): Parsed {
        if (Regex("-P$", I).containsMatchIn(sender.trim())) return Parsed(null, "promotional sender")
        hardSkip.find(t)?.let { return Parsed(null, "not completed (" + it.value.lowercase() + ")") }
        val firm = firmDebitRx.containsMatchIn(t)
        otpRx.find(t)?.let { m ->
            val near = t.substring(maxOf(0, m.range.first - 45), minOf(t.length, m.range.last + 45))
            if (!firm && codeRx.containsMatchIn(near)) return Parsed(null, "OTP message")
        }
        val amt = pickAmount(t) ?: return Parsed(null, "no payment amount")
        val d = debitRx.find(t) ?: return Parsed(null, "not a debit")
        val c = creditRx.find(t)
        if (c != null && c.range.first < d.range.first) return Parsed(null, "money received (credit)")
        if (!firm && softSkip.containsMatchIn(t)) return Parsed(null, "reminder / statement / mandate")
        if (!acctCtx.containsMatchIn(t)) return Parsed(null, "not from a bank, card or wallet")
        val payee = pickPayee(t)
        val txn = finish(id, txnTime(t, received), amt, payee, src, pickRef(t), pickAcct(t), bankOf(sender, t), t)
        return Parsed(txn, "ok")
    }

    // ---------------- mail ----------------

    private val fieldAmount = Regex("Amount\\s*:\\s*(?:rs\\.?|inr|₹)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)", I)
    private val subjectSkip = Regex("statement|e-?mandate|payment received|credited|credit transaction|\\boffer|reward|newsletter|\\botp\\b|password|login|\\bkyc\\b", I)

    fun isBankMail(from: String) = from.lowercase().let { f -> bankMailKeys.any { f.contains(it) } }

    /** Bank alert mails become debits; merchant receipts (Swiggy, Rapido...) only name a debit already found. */
    fun parseMail(from: String, subject: String, body: String, received: Long, idKey: String): Parsed {
        val t = norm(body)
        val owner = ownerFrom(t)
        if (isBankMail(from)) {
            if (subjectSkip.containsMatchIn(subject)) return Parsed(null, "statement / credit / offer mail", owner)
            // Table-style alert (Union Bank "DEBIT TRANSACTION ALERT")
            val fa = fieldAmount.find(t)
            if (fa != null && Regex("debit", I).containsMatchIn(subject)) {
                if (Regex("status\\s*:\\s*(?:fail|declin|reject|pending)", I).containsMatchIn(t)) return Parsed(null, "failed transaction", owner)
                val amt = fa.groupValues[1].replace(",", "").toDoubleOrNull()
                if (amt != null && amt > 0) {
                    val txn = finish("email:$idKey", txnTime(t, received), amt, pickPayee(t), "email", pickRef(t), pickAcct(t), bankOf(from, t), t)
                    return Parsed(txn, "ok", owner)
                }
            }
            val p = parseText(from, t, received, "email", "email:$idKey")
            return Parsed(p.txn, p.reason, owner)
        }
        val r = parseReceipt(from, subject, t, received, idKey)
        return Parsed(r, if (r == null) "not a bank alert" else "ok", owner)
    }

    private val brands = mapOf(
        "rapido" to "Rapido", "uber" to "Uber", "olacabs" to "Ola", "swiggy" to "Swiggy",
        "zomato" to "Zomato", "irctc" to "IRCTC", "blinkit" to "Blinkit", "zepto" to "Zepto",
        "amazon" to "Amazon", "flipkart" to "Flipkart", "bigbasket" to "BigBasket"
    )
    private val receiptWords = listOf("receipt", "invoice", "order", "ride", "trip", "booking confirmation", "ticket", "delivered", "payment")
    private val promoWords = listOf("offer", "sale", "% off", "discount", "deal", "coupon", "cashback", "save up")
    private val receiptAmount = Regex(
        "(?:grand total|total fare|total paid|amount paid|total amount|total|fare|paid)[^0-9₹]{0,40}(?:₹|rs\\.?|inr)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)", I
    )

    private fun parseReceipt(from: String, subject: String, body: String, ts: Long, idKey: String): Txn? {
        val f = from.lowercase()
        val brand = brands.entries.firstOrNull { f.contains(it.key) }?.value ?: return null
        val subj = subject.lowercase()
        if (promoWords.any { subj.contains(it) }) return null
        if (!receiptWords.any { subj.contains(it) }) return null
        val amt = receiptAmount.find(body)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull() ?: return null
        if (amt <= 0) return null
        val cat = guessCat(brand)
        return Txn("receipt:$idKey", ts, amt, brand, cat.id, cat.kind, "receipt", false)
    }

    /** Quick check used by the SMS check screen: does this text mention money at all? */
    fun mentionsMoney(t: String) = amountRx.containsMatchIn(t)
}
