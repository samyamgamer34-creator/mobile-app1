package com.theftguard.app

import android.util.Log
import java.util.Properties
import javax.mail.Authenticator
import javax.mail.Message
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

/**
 * Sends the location by email over SMTP (STARTTLS). Used only when the user chose
 * email delivery; it needs a network connection, which is why email is offered
 * only when Shizuku can turn mobile data / Wi-Fi on at alarm time.
 *
 * Credentials (an app password, not the account's real password) are the user's
 * own, entered in setup and stored in the app's device-protected storage.
 */
object EmailSender {

    private const val TAG = "EmailSender"

    /** Blocks until sent or failed; call from a background thread. Returns true on success. */
    fun send(config: AlarmState.MailConfig, subject: String, body: String): Boolean {
        if (!config.isComplete) return false
        return try {
            val props = Properties().apply {
                put("mail.smtp.auth", "true")
                put("mail.smtp.starttls.enable", "true")
                put("mail.smtp.host", config.host)
                put("mail.smtp.port", config.port.toString())
                // Fall back to SMTPS if the server speaks TLS directly (e.g. port 465).
                if (config.port == 465) {
                    put("mail.smtp.ssl.enable", "true")
                }
                put("mail.smtp.connectiontimeout", "20000")
                put("mail.smtp.timeout", "20000")
                put("mail.smtp.writetimeout", "20000")
            }
            val session = Session.getInstance(props, object : Authenticator() {
                override fun getPasswordAuthentication() =
                    PasswordAuthentication(config.user, config.password)
            })
            val message = MimeMessage(session).apply {
                setFrom(InternetAddress(config.user))
                setRecipients(Message.RecipientType.TO, InternetAddress.parse(config.recipient))
                setSubject(subject)
                setText(body)
            }
            Transport.send(message)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Email send failed", e)
            false
        }
    }
}
