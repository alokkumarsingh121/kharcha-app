package com.alok.kharcha

import android.text.Html
import java.util.Date
import java.util.Properties
import javax.mail.Folder
import javax.mail.Message
import javax.mail.Multipart
import javax.mail.Part
import javax.mail.Session
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage
import javax.mail.search.AndTerm
import javax.mail.search.ComparisonTerm
import javax.mail.search.FromStringTerm
import javax.mail.search.OrTerm
import javax.mail.search.ReceivedDateTerm
import javax.mail.search.SearchTerm

/**
 * Reads Gmail over IMAP (read-only) and only looks at mails from banks and a fixed list of
 * payment / ride / food / travel senders. Nothing is sent anywhere; mails are parsed on the phone.
 */
object MailSync {
    private val bankKeys = Parser.bankMailKeys
    private val merchantKeys = listOf(
        "rapido", "uber", "olacabs", "swiggy", "zomato", "irctc", "blinkit", "zepto",
        "amazon", "flipkart", "bigbasket"
    )

    private fun connect(email: String, pass: String): javax.mail.Store {
        val props = Properties()
        props["mail.store.protocol"] = "imaps"
        props["mail.imaps.host"] = "imap.gmail.com"
        props["mail.imaps.port"] = "993"
        props["mail.imaps.ssl.enable"] = "true"
        props["mail.imaps.connectiontimeout"] = "15000"
        props["mail.imaps.timeout"] = "30000"
        val session = Session.getInstance(props)
        val store = session.getStore("imaps")
        store.connect("imap.gmail.com", email, pass)
        return store
    }

    /** Throws if login fails. */
    fun test(email: String, pass: String) {
        connect(email, pass).close()
    }

    /** Returns the number of brand-new entries added. */
    fun sync(email: String, pass: String, days: Int): Int {
        val store = connect(email, pass)
        try {
            val since = Date(System.currentTimeMillis() - days * 24L * 60 * 60 * 1000)
            val senderTerms: Array<SearchTerm> =
                (bankKeys + merchantKeys).map { FromStringTerm(it) as SearchTerm }.toTypedArray()
            val term = AndTerm(ReceivedDateTerm(ComparisonTerm.GE, since), OrTerm(senderTerms))
            var added = 0
            // "All Mail" covers inbox + archived; Trash is separate in Gmail. Many bank alerts get auto-trashed.
            for (folder in folders(store)) {
                try {
                    folder.open(Folder.READ_ONLY)
                    val msgs = folder.search(term).takeLast(300).toTypedArray()
                    val fp = javax.mail.FetchProfile()
                    fp.add(javax.mail.FetchProfile.Item.ENVELOPE)
                    folder.fetch(msgs, fp)
                    for (m in msgs) {
                        try {
                            if (handle(m)) added++
                        } catch (e: Exception) {
                            // skip a single unreadable mail
                        }
                    }
                    folder.close(false)
                } catch (e: Exception) {
                    // skip a folder we cannot open
                }
            }
            return added
        } finally {
            try { store.close() } catch (e: Exception) { }
        }
    }

    private fun folders(store: javax.mail.Store): List<Folder> {
        val out = mutableListOf<Folder>()
        try {
            for (f in store.defaultFolder.list("*")) {
                val attrs = (f as? com.sun.mail.imap.IMAPFolder)?.attributes ?: continue
                if (attrs.any { it.equals("\\All", true) || it.equals("\\Trash", true) }) out.add(f)
            }
        } catch (e: Exception) {
        }
        if (out.none { f -> (f as? com.sun.mail.imap.IMAPFolder)?.attributes?.any { it.equals("\\All", true) } == true }) {
            out.add(0, store.getFolder("INBOX"))
        }
        return out
    }

    private fun handle(m: Message): Boolean {
        val from = ((m.from?.firstOrNull()) as? InternetAddress)?.address ?: return false
        val subj = m.subject ?: ""
        val ts = (m.receivedDate ?: m.sentDate ?: Date()).time
        val idKey = (m as? MimeMessage)?.messageID ?: (from + subj + ts)
        if (Store.mailDone(idKey)) return false          // already read in an earlier sync
        val text = textOf(m)
        val p = Parser.parseMail(from, subj, text, ts, idKey)
        if (p.owner.isNotBlank()) Store.addOwner(p.owner)
        val t = p.txn
        if (t == null) { Store.markMailDone(idKey); return false }
        val r = Store.add(t)
        if (r != Store.HELD) Store.markMailDone(idKey)   // unmatched receipts are retried next sync
        return r == Store.ADDED
    }

    private fun textOf(p: Part): String {
        return when {
            p.isMimeType("text/plain") -> (p.content as? String) ?: ""
            p.isMimeType("text/html") ->
                Html.fromHtml((p.content as? String) ?: "", Html.FROM_HTML_MODE_LEGACY).toString()
            p.isMimeType("multipart/*") -> {
                val mp = p.content as Multipart
                val sb = StringBuilder()
                for (i in 0 until mp.count) sb.append(textOf(mp.getBodyPart(i))).append(' ')
                sb.toString()
            }
            else -> ""
        }
    }
}
