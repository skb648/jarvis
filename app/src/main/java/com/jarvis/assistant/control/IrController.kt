package com.jarvis.assistant.control

import android.content.Context
import android.hardware.ConsumerIrManager
import android.os.Build

/**
 * IR blaster — "TV on", "TV volume up".
 * Uses standard NEC protocol codes (very widely supported by TVs).
 * Works only on phones with an IR emitter.
 */
class IrController(private val context: Context) {

    private val irManager: ConsumerIrManager? =
        if (Build.VERSION.SDK_INT >= 19) context.getSystemService(ConsumerIrManager::class.java) else null

    val hasIr: Boolean get() = irManager?.hasIrEmitter() == true

    // NEC protocol presets (address 0x10EF = common OEM code)
    private val tvCodes = mapOf(
        "power" to 0x10EFD02FL,
        "vol_up" to 0x10EF40BFL,
        "vol_down" to 0x10EFC03FL,
        "mute" to 0x10EF906FL
    )

    fun execute(device: String, action: String): String {
        if (!hasIr) return "Is phone pe IR blaster nahi hai, boss."
        val code = tvCodes[action] ?: return "TV ka ye action support nahi karta."
        val freq = irManager?.carrierFrequencies?.firstOrNull()?.let { (it.minFrequency + it.maxFrequency) / 2 }
            ?: 38000
        sendNec(freq, code)
        return ""
    }

    private fun sendNec(freq: Int, code: Long) {
        val pulses = ArrayList<Int>(68)
        // NEC timing (microseconds)
        val burst = 562
        // lead: 9ms high, 4.5ms low
        pulses.add(burst * 16)
        pulses.add(burst * 8)
        for (i in 31 downTo 0) {
            val bit = ((code shr i) and 1L) == 1L
            pulses.add(burst)          // mark
            pulses.add(if (bit) burst * 3 else burst) // 1: 1.687ms space, 0: 562us space
        }
        pulses.add(burst)
        pulses.add(0) // end
        runCatching { irManager?.transmit(freq, pulses.toIntArray()) }
    }
}
