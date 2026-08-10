package com.jarvis.assistant.control

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.SmsManager

/**
 * Communication: calls, SMS and WhatsApp.
 */
class CommunicationController(private val context: Context) {

    fun call(number: String, name: String?): String {
        val resolved = if (number.isNotBlank()) number else name?.let { lookupNumber(it) }.orEmpty()
        if (resolved.isBlank()) return "${name ?: "Contact"} phonebook me nahi mila."
        val uri = Uri.parse("tel:$resolved")
        return try {
            context.startActivity(Intent(Intent.ACTION_CALL, uri))
            ""
        } catch (e: SecurityException) {
            runCatching {
                context.startActivity(Intent(Intent.ACTION_DIAL, uri))
            }
            "Direct call ke liye Call permission chahiye — abhi dialer khol diya."
        } catch (e: Exception) {
            "Call open nahi ho paya."
        }
    }

    @SuppressLint("MissingPermission")
    fun sendSms(target: String, message: String): String {
        val number = resolveNumber(target) ?: return "Contact nahi mila — number batao."
        if (message.isBlank()) {
            runCatching {
                context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")))
            }
            return "SMS compose screen khol di."
        }
        return try {
            SmsManager.getDefault().sendTextMessage(number, null, message, null, null)
            "Message bhej diya."
        } catch (e: SecurityException) {
            runCatching {
                context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")))
            }
            "Send karne ke liye SMS permission chahiye — compose screen khol di."
        } catch (e: Exception) {
            "Message send nahi ho paya."
        }
    }

    fun whatsapp(target: String?, text: String?): String {
        // Try to open the specific chat first
        if (target != null) {
            val number = resolveNumber(target)
            if (number != null) {
                val waNumber = if (number.startsWith("+")) number.removePrefix("+") else "91$number"
                val chatIntent = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://wa.me/$waNumber?text=${Uri.encode(text.orEmpty())}")
                ).apply { setPackage("com.whatsapp") }
                if (context.packageManager.resolveActivity(chatIntent, 0) != null) {
                    context.startActivity(chatIntent)
                    return ""
                }
            }
        }
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            setPackage("com.whatsapp")
            putExtra(Intent.EXTRA_TEXT, text.orEmpty())
        }
        return if (context.packageManager.resolveActivity(sendIntent, 0) != null) {
            context.startActivity(sendIntent)
            if (text.isNullOrBlank()) "WhatsApp khol diya." else "WhatsApp pe bhej raha hoon."
        } else {
            "WhatsApp install nahi hai."
        }
    }

    /** Music recognition — opens Shazam (or falls back to a song-search browser). */
    fun openShazam(): String {
        val shazam = context.packageManager.getLaunchIntentForPackage("com.shazam.android")
        return if (shazam != null) {
            context.startActivity(shazam)
            ""
        } else {
            runCatching {
                context.startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("market://details?id=com.shazam.android")
                    )
                )
            }
            "Shazam install nahi hai — Play Store khol diya."
        }
    }

    private fun resolveNumber(target: String): String? {
        val cleaned = target.replace(Regex("[^+\\d]"), "")
        if (cleaned.length >= 6) return cleaned
        return lookupNumber(target)
    }

    private fun lookupNumber(name: String): String? {
        if (context.checkSelfPermission(android.Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) return null
        return try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$name%"),
                null
            )?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (e: Exception) {
            null
        }
    }
}
