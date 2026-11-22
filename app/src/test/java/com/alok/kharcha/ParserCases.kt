package com.alok.kharcha

import java.util.Calendar
import java.util.TimeZone

/**
 * Message formats modelled on Union Bank / ICICI alerts plus common Indian bank formats.
 * All names, account digits and reference numbers are made up.
 * run() returns a list of failures; empty means every case passed. Used by ParserTest (JUnit) in CI.
 */
object ParserCases {
    class S(val sender: String, val body: String, val amt: Double?, val payee: String = "", val ref: String = "", val acct: String = "", val kind: String = "")

    private val smsCases = listOf(
        // ---- Union Bank SMS format ----
        S("JK-UNIONB-S", "Union Bank of India A/c*4821 Dr Rs1500.00 on 18-11-26 14:24 by MB 310000000001 Fv.MOHAN, Bal Rs216.34 Not U?Call 18002333/SMS BLOCK 4821 to 8879635472", 1500.0, "MOHAN", "310000000001", "4821"),
        S("JX-UNIONB-S", "Union Bank of India A/c*4821 Dr Rs20.00 on 17-11-26 16:28 by MB 310000000002 Fv. Gopal, Bal Rs2716.34 Not U?Call 18002333/SMS BLOCK 4821 to 8879635472", 20.0, "Gopal", "310000000002", "4821"),
        S("JK-UNIONB-S", "Union Bank of India A/c*4821 Dr Rs5000.00 on 16-11-26 21:33 by MB 310000000003 Fv.SURESH, Bal Rs2756.34 Not U?Call 18002333/SMS BLOCK 4821 to 8879635472", 5000.0, "SURESH", "310000000003"),
        S("JX-UNIONB-S", "Union Bank of India A/c*4821 Dr Rs20.00 on 13-11-26 13:05 by MB 310000000004 Fv.55210, Bal Rs8444.14 Not U?Call 18002333/SMS BLOCK 4821 to 8879635472", 20.0, "", "310000000004"),
        S("JK-UNIONB-S", "Union Bank of India A/c*4821 Dr Rs25.00 on 11-11-26 19:30 by MB 310000000005 Fv.  Amit, Bal Rs8720.14 Not U?Call 18002333/SMS BLOCK 4821 to 8879635472", 25.0, "Amit"),
        S("JK-UNIONB-S", "Union Bank of India A/c*4821 Dr Rs10.00 on 12-11-26 18:45 by MB 310000000006 Fv.VIKAS, Bal Rs8564.14 Not U?Call 18002333/SMS BLOCK 4821 to 8879635472", 10.0, "VIKAS"),
        // ---- other common formats ----
        S("JD-UNIONB-S", "A/c *4821 Debited for Rs:500.00 on 19-11-2026 21:05:12 by Mob Bk ref no 532312345678 Avl Bal Rs:1000.00.If not you, Call 1800222243 -Union Bank of India", 500.0, "", "532312345678", "4821"),
        S("JM-UNIONB-S", "Rs.76.00 debited from A/c XX4821 on 19-11-26 and credited to rohit@okaxis (UPI Ref No 432752419206) - Union Bank of India", 76.0, "rohit@okaxis", "432752419206", "4821"),
        S("VM-ICICIT-S", "INR 116.00 spent using ICICI Bank Card XX3344 on 19-Nov-26 on UPI-410000000002-RAMESH K. Avl Limit: INR 52,105.24. If not you, call 1800 2662/SMS BLOCK 3344 to 9215676766", 116.0, "RAMESH K", "410000000002", "3344"),
        S("AX-ICICIT-T", "ICICI Bank Credit Card XX3344 has been used for a transaction of INR 1,288.00 on 21-Nov-26 at 12:33:28. Info: UPI-410000000009-SWIGGY I. Available Credit Limit: INR 51,577.24.", 1288.0, "SWIGGY I", "410000000009", "3344"),
        S("VM-ICICIB-S", "ICICI Bank Acct XX123 debited for Rs 240.00 on 19-Nov-26; RAPIDO credited. UPI:532312345678. Call 18002662 for dispute. SMS BLOCK 123 to 9215676766.", 240.0, "RAPIDO", "532312345678"),
        S("AD-HDFCBK-S", "Sent Rs.49.00\nFrom HDFC Bank A/C *1234\nTo RAPIDO\nOn 19/11/26\nRef 532312345678\nNot You?\nCall 18002586161/SMS BLOCK UPI to 7308080808", 49.0, "RAPIDO", "532312345678", "1234"),
        S("AD-HDFCBK-S", "Spent Rs.1288 On HDFC Bank Card 5678 At SWIGGY On 2026-11-21:12:33:28 Bal Rs.43577 Not You? Call 18002586161/SMS BLOCK CC 5678 to 7308080808", 1288.0, "SWIGGY", "", "5678"),
        S("JD-SBIUPI-S", "Dear UPI user A/C X1234 debited by 76.0 on date 19Nov26 trf to ROHIT KUMAR Refno 432752419206. If not u? call 1800111109. -SBI", 76.0, "ROHIT KUMAR", "432752419206", "1234"),
        S("AD-SBICRD-S", "Rs.2,500.00 spent on your SBI Credit Card ending 1234 at HOTEL BALAJI on 20/11/26. Trxn. not done by you? Report at https://sbicard.com/Dispute", 2500.0, "HOTEL BALAJI", "", "1234"),
        S("AD-AXISBK-S", "INR 5000.00 debited\nA/c no. XX1234\n18-11-26, 12:10:00\nUPI/P2A/623412345678/RAJESH K\nNot you? SMS BLOCKUPI Cust ID to 919951860002\nAxis Bank", 5000.0, "RAJESH K", "623412345678", "1234"),
        S("AD-KOTAKB-S", "Sent Rs.120.00 from Kotak Bank AC X1234 to rapido@ybl on 19-11-26.UPI Ref 532312345678. Not you, https://kotak.com/KBANKT/Fraud", 120.0, "rapido@ybl", "532312345678", "1234"),
        S("AD-BOBTXN-S", "Rs.5000 Dr. from A/C XXXX1234 and Cr. to rajesh@ybl. Ref:623412345678. AvlBal:Rs12000(2026:11:18 12:10:00). Not you? Call 18005700-BOB", 5000.0, "rajesh@ybl", "623412345678", "1234"),
        S("AD-PNBSMS-S", "Your A/c XX1234 has been debited for INR 300.00 on 19-11-2026 21:05:12 thru UPI:532312345678. Avl Bal INR 4,500.00 -PNB", 300.0, "", "532312345678", "1234"),
        S("AD-CANBNK-S", "An amount of INR 5,000.00 has been DEBITED to your account XX1234 on 18/11/2026 towards UPI/123456789012/PREM. Total Avail.bal INR 3,000.00 - Canara Bank", 5000.0, "PREM", "123456789012", "1234"),
        S("AD-IDFCFB-S", "Your A/C XXXXXXX1234 has been debited with INR 500.00 on 19/11/2026 21:00 towards UPI/532312345678/Swiggy. New balance: INR 1000.00", 500.0, "Swiggy", "532312345678", "1234"),
        S("AD-INDUSB-S", "IndusInd Bank: Your A/C 2023XXXX1234 is debited with INR 49.00 towards UPI/532312345678/rapido@ybl on 19-NOV-26.", 49.0, "rapido@ybl", "532312345678", "1234"),
        S("AD-FEDBNK-S", "Rs 500.00 debited from your A/c XXXX1234 to VPA swiggy@icici on 21-11-2026 12:33:28. Ref 410000000009", 500.0, "swiggy@icici", "410000000009", "1234"),
        S("AD-PAYTMB-S", "Paid Rs.49 to Rapido from Paytm Balance. Updated Balance: Paytm Wallet- Rs 51. Order ID: 1234567", 49.0, "Rapido"),
        S("AD-SBIINB-S", "Rs.2000.00 withdrawn at ATM S1ANVA12 from A/c XX1234 on 19-11-26. Avl Bal Rs.3000", 2000.0, "ATM S1ANVA12", "", "1234"),
        S("AD-HDFCBK-S", "Rs 199.00 debited from A/c XX1234 for Netflix AutoPay mandate on 19-11-26. UPI Ref 532312345678", 199.0, "", "532312345678"),
        S("JK-UNIONB-S", "Union Bank of India A/c*4821 Dr Rs1523.29 on 20-11-26 22:39 by MB 310000000007 Fv.CRED, Bal Rs100.00 Not U?Call 18002333", 1523.29, "CRED", "310000000007", "4821", "skip"),
        // ---- must be ignored ----
        S("BP-ICICIT-T", "Transaction of INR 387.00 at SWIGGY PVT LTD  on ICICI Bank Credit Card XX5566 was declined due to incorrect CVV. Please retry with correct CVV.", null),
        S("AD-GWRTHL-S", "Dear A/c1234567890 NSE FNO Trade confirmation Rs.106,840 Trade Bal: Rs.982,7070 Init By Withdraw at bit.ly/47egixi Bill 11111 GWORTH", null),
        S("AD-OMUDRA-S", "Days Position-Dear A/c1234567890 NSE SLBM-Trade confirmation Rs.106,545 Trade Bal: Rs.982,3887 Init By Withdraw Bill bit.ly/4yMZzwr Arham Shares- MUDRA OM", null),
        S("AD-ICICIT-S", "123456 is your OTP for txn of INR 5000.00 at AMAZON on ICICI Bank Card XX3344. Valid for 10 mins. Do not share OTP.", null),
        S("AD-HDFCBK-S", "OTP is 482910 for your UPI transaction of Rs 500. Never share it with anyone.", null),
        S("AD-HDFCBK-S", "Rs.5000.00 credited to A/c XX1234 on 18-11-26 by RAJESH (UPI Ref 123456789012). Avl bal Rs 9000", null),
        S("JK-UNIONB-S", "Union Bank of India A/c*4821 Cr Rs20000.00 on 21-11-26 10:50 by MB 310000000008 Fv.SALARY, Bal Rs20100.00", null),
        S("AD-ICICIT-S", "Dear Customer, Payment of INR 1,523.29 has been received towards your ICICI Bank Credit Card XX3344 on 20-Nov-26. Thank you.", null),
        S("AD-HDFCBK-S", "Your UPI transaction of Rs 500 to rapido@ybl failed. Amount if debited will be refunded within 48 hrs.", null),
        S("AD-HDFCBK-S", "Rs 500.00 refunded to your A/c XX1234 for UPI txn Ref 532312345678", null),
        S("AD-ICICIT-S", "ICICI Bank Credit Card XX3344 statement: Total amount due INR 15,234.00, minimum due INR 760 by 10-Dec-26. Pay via iMobile.", null),
        S("JM-JIOINF-S", "Your Jio plan expires on 25-Nov. Recharge now with Rs 299 for 28 days. Click https://jio.com", null),
        S("JM-JIOINF-S", "Recharge of Rs 299 successful for 9876543210. Plan valid till 20-Dec.", null),
        S("AD-HDFCBK-S", "Your AutoPay mandate for Rs 199.00 towards Netflix has been successfully registered on HDFC Bank A/C *1234.", null),
        S("AD-HDFCBK-S", "Rs 199 will be debited from your A/c XX1234 on 25-11-26 towards Netflix AutoPay", null),
        S("AD-HDFCBK-S", "rapido@ybl has requested Rs 49 from you via UPI. Approve in your UPI app.", null),
        S("AX-SWIGGY-P", "Flat 50% off! Order now and pay just Rs 99. Paid via UPI gets extra 10% cashback", null),
        S("AD-HDFCBK-S", "Pre-approved loan of Rs 5,00,000 for you. Apply now", null),
        S("AD-SBIINB-S", "Avl Bal in A/c XX1234 is Rs 5000.00 as on 19-11-26", null)
    )

    private const val UNION_MAIL = "Dear *RAVI KUMAR VERMA*, Greetings! Your fund transfer request through *UPI* has been processed successfully. Transaction Details 1. Payee Name : Mr Deepak 2. Amount : Rs. 87.00 3. Channel : UPI 4. Transaction ID/RRN : 420000000003 5. Transaction Status : Success 6. Transaction Date and Time : 20-11-2026 05:34:24 7. Debit Account Number : *4821 If you have not initiated this transaction, please report it immediately by way of: SMS UEBT to 8879365472 OR SMS \"BLOCK\"<SPACE><LAST FOUR DIGIT A/C NO.> TO 8879365472 OR Call our Toll Free Numbers: 1800 8333 / 1800 2333 Never share your Card Number, CVV, PIN, OTP, Internet Banking UserID, Password or URN with anyone. Regards, Union Bank of India"
    private const val UNION_MAIL2 = "Dear RAVI KUMAR VERMA, Greetings! Your fund transfer request through UPI has been processed successfully. Transaction Details 1. Payee Name : Mr Deepak 2. Amount : Rs. 87.00 3. Channel : UPI 4. Transaction ID/RRN : 420000000004 5. Transaction Status : Success 6. Transaction Date and Time : 20-11-2026 05:35:32 7. Debit Account Number : *4821"
    private const val UNION_SHIV = "Dear RAVI KUMAR VERMA, Greetings! Your fund transfer request through UPI has been processed successfully. Transaction Details 1. Payee Name : MOHANLAL 2. Amount : Rs. 1500.00 3. Channel : UPI 4. Transaction ID/RRN : 310000000001 5. Transaction Status : Success 6. Transaction Date and Time : 18-11-2026 14:24:46 7. Debit Account Number : *4821"
    private const val UNION_SELF = "Dear RAVI KUMAR VERMA, Greetings! Your fund transfer request through UPI has been processed successfully. Transaction Details 1. Payee Name : RAVI KUM 2. Amount : Rs. 3000.00 3. Channel : UPI 4. Transaction ID/RRN : 420000000005 5. Transaction Status : Success 6. Transaction Date and Time : 21-11-2026 10:00:00 7. Debit Account Number : *4821"
    private const val ICICI_MAIL = "ICICI Bank Online | Dear Customer, Your ICICI Bank Credit Card XX3344 has been used for a transaction of INR 116.00 on Nov 19, 2026 at 07:56:00. Info: UPI-410000000002-RAMESH K. The Available Credit Limit on your card is INR 52,105.24 and Total Credit Limit is INR 1,00,000.00. The above limits are a total of the limits of all the Credit Cards issued to the primary card holder. In case you have not done this transaction, to report it please call on 18002662 or SMS BLOCK space 3344 to 9215676766 from your registered mobile number and if you are outside India, call on 04071403333. Contact your RM @ Express Relationship Banking 022-44400000. Never share your OTP, URN, CVV or passwords with anyone even if the person claims to be a bank employee. Sincerely, Team ICICI Bank | This is an auto-generated e-mail. Discover a new way of paying your Credit Card bills from your bank account anytime anywhere by using ICICI Bank iMobile Pay."
    private const val ICICI_PAID = "Dear Customer, We have received payment of INR 1,523.29 towards your ICICI Bank Credit Card XX3344 on 20-Nov-26. Your available limit is INR 46,000.00."

    private fun ist(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int = 0): Long {
        val c = Calendar.getInstance()
        c.set(y, mo - 1, d, h, mi, s)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    private fun near(a: Double?, b: Double?) = (a == null && b == null) || (a != null && b != null && Math.abs(a - b) < 0.001)

    fun run(): List<String> {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"))
        val fails = mutableListOf<String>()
        val now = ist(2026, 11, 21, 21, 0)
        Parser.ownerNames = emptyList()

        for (c in smsCases) {
            val p = Parser.parseSms(c.sender, c.body, now)
            val t = p.txn
            val tag = c.body.take(55)
            if (!near(t?.amt, c.amt)) { fails += "AMOUNT  [$tag] want ${c.amt} got ${t?.amt} (${p.reason})"; continue }
            if (t == null) continue
            if (!t.merchant.equals(c.payee, true)) fails += "PAYEE   [$tag] want '${c.payee}' got '${t.merchant}'"
            if (c.ref.isNotEmpty() && t.ref != c.ref) fails += "REF     [$tag] want ${c.ref} got '${t.ref}'"
            if (c.acct.isNotEmpty() && t.acct != c.acct) fails += "ACCT    [$tag] want ${c.acct} got '${t.acct}'"
            if (c.kind.isNotEmpty() && t.kind != c.kind) fails += "KIND    [$tag] want ${c.kind} got ${t.kind}"
        }

        // ---- mails ----
        val unionRecv = ist(2026, 11, 20, 5, 34, 33)
        val um = Parser.parseMail("noreplyubi-txn@ubi.bank.in", "DEBIT TRANSACTION ALERT", UNION_MAIL, unionRecv, "m1")
        val u = um.txn
        if (u == null) fails += "MAIL union: not read (${um.reason})" else {
            if (!near(u.amt, 87.0) || u.merchant != "Mr Deepak" || u.ref != "420000000003" || u.acct != "4821")
                fails += "MAIL union: got ${u.amt} '${u.merchant}' ${u.ref} ${u.acct}"
            if (u.ts != ist(2026, 11, 20, 5, 34, 24)) fails += "MAIL union time: got ${u.ts}"
            if (u.bank != "Union Bank") fails += "MAIL union bank: '${u.bank}'"
        }
        if (um.owner != "RAVI KUMAR VERMA") fails += "OWNER: got '${um.owner}'"

        val iciciRecv = ist(2026, 11, 19, 19, 56, 18)
        val im = Parser.parseMail("credit_cards@icici.bank.in", "Transaction alert for your ICICI Bank Credit Card", ICICI_MAIL, iciciRecv, "m2")
        val ic = im.txn
        if (ic == null) fails += "MAIL icici: not read (${im.reason})" else {
            if (!near(ic.amt, 116.0) || ic.merchant != "RAMESH K" || ic.ref != "410000000002" || ic.acct != "3344")
                fails += "MAIL icici: got ${ic.amt} '${ic.merchant}' ${ic.ref} ${ic.acct}"
            if (ic.ts != ist(2026, 11, 19, 19, 56, 0)) fails += "MAIL icici time (07:56 must be 7:56 PM): got ${ic.ts}"
        }
        if (Parser.parseMail("credit_cards@icici.bank.in", "Payment received on your ICICI Bank Credit Card.", ICICI_PAID, iciciRecv, "m3").txn != null)
            fails += "MAIL icici payment-received should be ignored"
        if (Parser.parseMail("credit_cards@icici.bank.in", "ICICI Bank Credit Card Statement for the period October 22 2026 to November 21 2026", "Total Amount Due INR 15,234.00", iciciRecv, "m4").txn != null)
            fails += "MAIL statement should be ignored"

        // own transfer once the owner's name is known
        Parser.ownerNames = listOf("RAVI KUMAR VERMA")
        val self = Parser.parseMail("noreplyubi-txn@ubi.bank.in", "DEBIT TRANSACTION ALERT", UNION_SELF, ist(2026, 11, 21, 10, 0, 5), "m5").txn
        if (self?.kind != "skip") fails += "SELF transfer should be 'Not spending', got ${self?.kind}"
        Parser.ownerNames = emptyList()

        // ---- duplicates ----
        val list = mutableListOf<Txn>()
        fun add(t: Txn?): String {
            if (t == null) return "null"
            val d = Dedup.find(list, t)
            return if (d != null) { Dedup.merge(d, t); "merged" } else if (t.src == "receipt") "held" else { list += t; "added" }
        }
        val smsShiv = Parser.parseSms("JK-UNIONB-S", smsCases[0].body, ist(2026, 11, 18, 14, 24, 50)).txn
        val mailShiv = Parser.parseMail("noreplyubi-txn@ubi.bank.in", "DEBIT TRANSACTION ALERT", UNION_SHIV, ist(2026, 11, 18, 14, 24, 52), "d1").txn
        if (add(smsShiv) != "added") fails += "DEDUP sms not added"
        if (add(mailShiv) != "merged") fails += "DEDUP union sms+mail with same RRN should merge"
        if (list.size != 1 || list[0].merchant != "MOHANLAL") fails += "DEDUP name should become MOHANLAL, got ${list.map { it.merchant }}"
        if (list.firstOrNull()?.src != "sms+email") fails += "DEDUP src should be sms+email, got ${list.firstOrNull()?.src}"

        val a1 = Parser.parseMail("noreplyubi-txn@ubi.bank.in", "DEBIT TRANSACTION ALERT", UNION_MAIL, unionRecv, "d2").txn
        val a2 = Parser.parseMail("noreplyubi-txn@ubi.bank.in", "DEBIT TRANSACTION ALERT", UNION_MAIL2, unionRecv + 82_000, "d3").txn
        add(a1)
        if (add(a2) != "added") fails += "DEDUP two real Rs 87 payments (different RRN) must stay two"

        // same amount, close in time, but different account/card -> two payments
        val unionNoRef = Txn("sms:x1", ist(2026, 11, 21, 20, 10), 20.0, "", "other", "want", "sms", false, "", "4821", "Union Bank")
        val iciciMail = Txn("email:x2", ist(2026, 11, 21, 20, 5), 20.0, "Sonu", "other", "want", "email", false, "999988887777", "3344", "ICICI Card")
        add(unionNoRef)
        if (add(iciciMail) != "added") fails += "DEDUP different cards must not merge"
        // same account, no refs on one side, close in time -> same payment
        val unionMailNoRefSide = Txn("email:x3", ist(2026, 11, 21, 20, 11), 20.0, "Mr Sonu", "other", "want", "email", false, "777766665555", "4821", "Union Bank")
        if (add(unionMailNoRefSide) != "merged") fails += "DEDUP same account sms(no ref)+mail should merge"

        // receipt mail only names an existing debit
        val swiggyDebit = Parser.parseSms("AX-ICICIT-T", smsCases[9].body, ist(2026, 11, 21, 12, 33, 40)).txn
        add(swiggyDebit)
        val receipt = Txn("receipt:r1", ist(2026, 11, 21, 13, 10), 1288.0, "Swiggy", "eatout", "want", "receipt", false)
        if (add(receipt) != "merged") fails += "DEDUP receipt should merge into the card debit"
        val lonely = Txn("receipt:r2", ist(2026, 11, 21, 13, 10), 333.0, "Swiggy", "eatout", "want", "receipt", false)
        if (add(lonely) != "held") fails += "DEDUP receipt without a bank debit must not be counted"

        // ---- original message kept with each entry ----
        val hdfcBody = smsCases.first { it.body.startsWith("Sent Rs.49.00") }.body
        val hd = Parser.parseSms("AD-HDFCBK-S", hdfcBody, now).txn
        val hm = hd?.msgs?.singleOrNull()
        if (hm == null) fails += "MSG sms text not kept" else {
            if (hm.src != "sms" || hm.from != "AD-HDFCBK-S" || hm.key != hd.id) fails += "MSG sms fields: ${hm.src} ${hm.from} ${hm.key}"
            if (!hm.text.startsWith("Sent Rs.49.00\nFrom HDFC Bank A/C *1234\nTo RAPIDO")) fails += "MSG sms line breaks lost: ${hm.text.take(60)}"
        }
        val icm = Parser.parseMail("credit_cards@icici.bank.in", "Transaction alert for your ICICI Bank Credit Card", ICICI_MAIL, iciciRecv, "m9").txn?.msgs?.singleOrNull()
        if (icm == null) fails += "MSG mail text not kept" else {
            if (!icm.text.startsWith("Dear Customer, Your ICICI Bank Credit Card XX3344 has been used")) fails += "MSG icici snippet start: ${icm.text.take(60)}"
            if (!icm.text.contains("INR 116.00") || !icm.text.contains("RAMESH K")) fails += "MSG icici snippet lost the payment: ${icm.text}"
            if (icm.text.contains("In case you have not") || icm.text.contains("auto-generated")) fails += "MSG icici snippet kept the footer: ${icm.text}"
            if (icm.subject != "Transaction alert for your ICICI Bank Credit Card" || icm.src != "email") fails += "MSG icici subject/src: ${icm.subject} ${icm.src}"
        }
        val un = Parser.mailSnippet(UNION_MAIL)
        if (!un.startsWith("Dear RAVI KUMAR VERMA, Greetings!") || !un.contains("Amount : Rs. 87.00") || !un.endsWith("Debit Account Number : *4821"))
            fails += "MSG union snippet: $un"
        val html = "View in browser\n\n￼\nHi Ravi,\nYour ride is complete.\nTotal fare  ₹ 49.00\nPaid via UPI\n\nDownload the app for offers\n© Rapido"
        val rs = Parser.mailSnippet(html, 350)
        if (rs != "Hi Ravi,\nYour ride is complete.\nTotal fare ₹ 49.00\nPaid via UPI") fails += "MSG receipt snippet: '$rs'"
        val long = "Dear Customer, Rs 500.00 debited from A/c XX1234. " + "Some more words here. ".repeat(40)
        val ls = Parser.mailSnippet(long, 120)
        if (ls.length > 121 || !ls.endsWith("…") || !ls.startsWith("Dear Customer, Rs 500.00 debited")) fails += "MSG long mail not cut well: '$ls'"
        if (list[0].msgs.map { it.src } != listOf("sms", "email")) fails += "MSG merged entry should keep SMS + mail texts, got ${list[0].msgs.map { it.src }}"
        val cardDebit = Parser.parseSms("AX-ICICIT-T", smsCases[9].body, ist(2026, 11, 21, 12, 33, 40)).txn!!
        val swiggyMail = Parser.parseMail(
            "noreply@swiggy.in", "Your Swiggy order receipt",
            "Hi Ravi,\nYour order from Meghna Biryani was delivered.\nGrand Total ₹1,288.00\nPaid via UPI\nDownload the app", ist(2026, 11, 21, 13, 10), "r9"
        ).txn
        if (swiggyMail == null || swiggyMail.msgs.singleOrNull()?.src != "receipt") fails += "MSG receipt mail text not kept"
        else {
            Dedup.merge(cardDebit, swiggyMail)
            if (cardDebit.msgs.map { it.src } != listOf("sms", "receipt")) fails += "MSG receipt text should join the card debit, got ${cardDebit.msgs.map { it.src }}"
        }

        // entries saved by the older app have no text: a re-read message finds its own entry
        val oldSms = Txn("sms:old1", ist(2026, 11, 18, 14, 24, 50), 1500.0, "MOHAN", "transfer", "want", "sms", false, "310000000001", "4821", "Union Bank")
        val oldMerged = Txn("email:old2", ist(2026, 11, 19, 19, 56), 116.0, "RAMESH K", "other", "want", "email+sms", false, "410000000002", "3344", "ICICI Card")
        val oldOther = Txn("sms:old3", ist(2026, 11, 19, 20, 0), 116.0, "SOMEONE", "other", "want", "sms", false, "410000000099", "3344", "ICICI Card")
        val old = listOf(oldSms, oldMerged, oldOther)
        val again = Parser.parseSms("VM-ICICIT-S", smsCases[8].body, ist(2026, 11, 19, 19, 56, 30)).txn!!
        if (Dedup.owner(old, again) !== oldMerged) fails += "OWNER old merged entry not found by ref"
        val sameId = Txn("sms:old1", 0L, 1500.0, "", "other", "want", "sms", false)
        if (Dedup.owner(old, sameId) !== oldSms) fails += "OWNER own entry not found by id"
        val stranger = Txn("sms:new9", ist(2026, 11, 19, 19, 57), 116.0, "", "other", "want", "sms", false, "410000000777", "3344")
        if (Dedup.owner(old, stranger) != null) fails += "OWNER different ref must not be attached"
        oldMerged.msgs.add(again.msgs[0])
        val second = Txn("sms:x9", ist(2026, 11, 19, 19, 56), 116.0, "", "other", "want", "sms", false, "410000000002", "3344")
        if (Dedup.owner(listOf(oldMerged), second) != null) fails += "OWNER entry that already has its SMS text must not take another"

        // how it was paid
        if (Parser.modeOf(smsCases[8].body, "ICICI Card") != "UPI (credit card)") fails += "MODE icici upi card: ${Parser.modeOf(smsCases[8].body, "ICICI Card")}"
        if (Parser.modeOf(smsCases[12].body, "HDFC Card") != "Card") fails += "MODE hdfc card"
        if (Parser.modeOf(smsCases[0].body, "Union Bank") != "") fails += "MODE union 'by MB' should stay blank"
        if (Parser.modeOf("Rs.2000.00 withdrawn at ATM S1ANVA12 from A/c XX1234") != "ATM") fails += "MODE atm"

        // ---- day boundaries: 11:59 PM stays on its own day ----
        val late = Parser.txnTime("Dr Rs44.00 on 21-11-26 23:59 by MB 1", ist(2026, 11, 21, 23, 59, 30))
        if (late != ist(2026, 11, 21, 23, 59)) fails += "TIME 23:59 read as $late"
        return fails
    }
}
