package com.jarvis.assistant.control

import android.content.Context
import android.content.Intent
import com.jarvis.assistant.MainActivity
import com.jarvis.assistant.ai.Command
import com.jarvis.assistant.info.InfoHub
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Routes parsed commands to the right controller and returns a spoken status.
 * Every command here is fast (intents / system services — no heavy work).
 */
class DeviceCommander(private val context: Context) {

    private val media = MediaController(context)
    private val timers = TimerController(context)
    private val system = SystemController(context)
    private val comms = CommunicationController(context)
    private val launcher = AppLauncher(context)
    private val weather = WeatherController(context)
    private val info = InfoHub(context)
    private val smartHome = SmartHomeController(context)
    private val ir = IrController(context)
    private val geofence = GeofenceController(context)

    suspend fun execute(command: Command): String = when (command) {
        is Command.Time -> "Abhi time ho raha hai ${SimpleDateFormat("h:mm a", Locale.ENGLISH).format(Date())}."
        is Command.Date -> "Aaj ki tarikh hai ${SimpleDateFormat("d MMMM yyyy", Locale.ENGLISH).format(Date())}."
        is Command.Day -> "Aaj ${SimpleDateFormat("EEEE", Locale.ENGLISH).format(Date())} hai."
        is Command.Battery -> system.batteryStatus()
        is Command.StatusReport -> system.statusReport()
        is Command.Media -> media.control(command.action)
        is Command.PlaySomething -> launcher.playSomething(command.query)
        is Command.Volume -> system.setVolume(command.direction, command.level)
        is Command.Toggle -> system.toggle(command.target, command.on)
        is Command.Brightness -> system.setBrightness(command.direction, command.level)
        is Command.Ringer -> system.setRinger(command.mode)
        is Command.Timer -> timers.startTimer(command.seconds)
        is Command.Alarm -> timers.setAlarm(command.hour, command.minute)
        is Command.Reminder -> timers.setReminder(command.text, command.hour, command.minute)
        is Command.Routine -> timers.scheduleRoutine(command.hour, command.minute, command.action)
        is Command.Call -> comms.call(command.number, command.name)
        is Command.Sms -> comms.sendSms(command.number, command.message)
        is Command.WhatsApp -> comms.whatsapp(command.target, command.text)
        is Command.OpenApp -> launcher.open(command.app)
        is Command.Weather -> weather.report()
        is Command.UiAction -> system.uiAction(command.action, command.target)

        // ---- v2.0 ----
        is Command.Briefing -> info.dailyBriefing()
        is Command.Cricket -> info.cricketScore()
        is Command.GoldRates -> info.goldRates()
        is Command.News -> info.topNews(5)
        is Command.Pnr -> info.pnrStatus(command.code)
        is Command.Hotspot -> system.setHotspot(command.on)
        is Command.ScreenRecord -> {
            if (command.start) {
                if (ScreenRecorderService.isActive()) {
                    "Recording pehle se chal rahi hai!"
                } else {
                    // open MainActivity to get MediaProjection consent, then it starts the recorder
                    val i = Intent(context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra("screen_record", true)
                    context.startActivity(i)
                    ""
                }
            } else {
                ScreenRecorderService.stop(context)
                ""
            }
        }
        is Command.Clipboard -> system.setClipboard(command.text)
        is Command.Paste -> system.pasteClipboard()
        is Command.FindPhone -> {
            FindMyPhone.start(context)
            ""
        }
        is Command.MusicId -> comms.openShazam()
        is Command.SmartHome -> smartHome.execute(command.device, command.action, command.value)
        is Command.IrControl -> ir.execute(command.device, command.action)
        is Command.GeofenceReminder -> geofence.addHomeReminder(command.text)
        is Command.ReadNotifications -> NotificationReader.read(context)
        else -> ""
    }
}
