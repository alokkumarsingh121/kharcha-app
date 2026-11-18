package com.alok.kharcha

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        Store.init(ctx.applicationContext)
        val msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        val grouped = LinkedHashMap<String, StringBuilder>()
        for (m in msgs) {
            val addr = m.originatingAddress ?: "SMS"
            grouped.getOrPut(addr) { StringBuilder() }.append(m.messageBody ?: "")
        }
        for ((addr, body) in grouped) {
            val t = SmsParser.parse(addr, body.toString(), System.currentTimeMillis())
            if (t != null) Store.addTxnIfNew(t)
        }
    }
}
