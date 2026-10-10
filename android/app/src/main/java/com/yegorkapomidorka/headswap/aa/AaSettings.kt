package com.yegorkapomidorka.headswap.aa

import android.content.Context
import android.content.SharedPreferences
import com.yegorkapomidorka.headswap.AppLog
import java.io.FileNotFoundException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicLong

/** Настройки AA-режима (отдельный файл prefs). */
class AaPrefs(ctx: Context) {
    private val sp: SharedPreferences = ctx.getSharedPreferences("aa", Context.MODE_PRIVATE)

    /** jvc (по умолчанию, принят AA 17.7 живьём) или hur. */
    var certSet: String
        get() = sp.getString("certSet", "jvc") ?: "jvc"
        set(v) = sp.edit().putString("certSet", v).apply()

    /** Звук AA -> CarLife по USB; по умолчанию вкл. */
    var usbAudio: Boolean
        get() = sp.getBoolean("usbAudio", true)
        set(v) = sp.edit().putBoolean("usbAudio", v).apply()

    /** Видео моста: перекодирование (по умолчанию) или passthrough H.264 AA как есть. */
    var transcode: Boolean
        get() = sp.getBoolean("transcode", true)
        set(v) = sp.edit().putBoolean("transcode", v).apply()

    /** Разрешение видео (имя VideoMode, по умолчанию 1280×720). Читается один раз на подключение. */
    var videoMode: VideoMode
        get() = VideoMode.parse(sp.getString("videoMode", VideoMode.DEFAULT.name))
        set(v) = sp.edit().putString("videoMode", v.name).apply()

    /** Автозапуск сервера ГУ (USB подключён / сессия AA закончилась); работает, только если включена служба спецвозможностей. */
    var autoServer: Boolean
        get() = sp.getBoolean("autoServer", true)
        set(v) = sp.edit().putBoolean("autoServer", v).apply()

    /** Источник звука для микрофона AA: машина / телефон / авто (ГУ, при молчании 2 с - телефон). */
    var micMode: MicMode
        get() = MicMode.parse(sp.getString("micMode", MicMode.AUTO.key))
        set(v) = sp.edit().putString("micMode", v.key).apply()

    /** Вместе с запросом записи у ГУ слать MODULE_STATUS VR=1/0 (как Baidu). */
    var micVrStatus: Boolean
        get() = sp.getBoolean("micVrStatus", true)
        set(v) = sp.edit().putBoolean("micVrStatus", v).apply()
}

/** Состояние AA-режима для экрана; пишет сервис, читает activity (один процесс). */
object AaState {
    @Volatile var status: String = "не запущено"
    @Volatile var running: Boolean = false
    @Volatile var cert: String = "-"
    val frames = AtomicLong(0)
    @Volatile var bytes: Long = 0
    @Volatile var codecConfigs: Long = 0
    @Volatile var selftest: String = "-"
    @Volatile var bridgeSent: Long = 0
    @Volatile var bridgeDropped: Long = 0
    @Volatile var audioBytes: Long = 0

    /** Ошибка настройки, из-за которой мост не стартует (например, нет сертификата head unit); null = всё в порядке. */
    @Volatile var setupError: String? = null

    fun reset() {
        frames.set(0)
        bytes = 0
        codecConfigs = 0
        bridgeSent = 0
        bridgeDropped = 0
        audioBytes = 0
    }

    fun summary(): String =
        "AA: $status\nAA cert: $cert  selftest: $selftest\nAA кадров: ${frames.get()} (config $codecConfigs, ${bytes / 1024} КиБ)\nМОСТ: в CarLife отправлено $bridgeSent, отброшено $bridgeDropped, звук ${audioBytes / 1024} КиБ"
}

/** Ни один набор сертификата head unit не найден в assets. */
class AaCertsMissingException(message: String) : Exception(message)

/** Загрузка сертификатов из assets с откатом на второй набор и крупным логом отката. */
object AaCreds {
    const val MISSING_TEXT = "Нет сертификата head unit: положите cert.pem и key.pem в app/src/main/assets/aa/jvc/ (см. README)"

    /** @throws AaCertsMissingException если файлов сертификата нет ни в одном наборе */
    fun load(ctx: Context, preferred: String): AapCerts.Loaded {
        val opener: (String) -> InputStream = { ctx.assets.open(it) }
        val loaded = try {
            AapCerts.loadWithFallback(opener, preferred)
        } catch (e: FileNotFoundException) {
            AppLog.i("AA !!!!!! $MISSING_TEXT", true)
            throw AaCertsMissingException(MISSING_TEXT)
        }
        if (loaded.error != null) {
            AppLog.i("AA !!!!!! ${loaded.error}", true)
            AppLog.i("AA !!!!!! ОТКАТ НА СЕРТИФИКАТ '${loaded.creds.certSet}' (запрошен '$preferred') !!!!!!", true)
        } else {
            AppLog.i("AA сертификат '${loaded.creds.certSet}' загружен: ${loaded.creds.chain[0].subjectX500Principal}", true)
        }
        return loaded
    }

    /** notBefore/notAfter отдельно: у JVC формат UTCTime с зоной, парсится не везде. Только для лога. */
    fun describeValidity(creds: AapCredentials): String = try {
        val c = creds.chain[0]
        "notBefore=${c.notBefore} notAfter=${c.notAfter}"
    } catch (e: Throwable) {
        "notBefore/notAfter не читаются: ${e.javaClass.simpleName}: ${e.message}"
    }

    /** Самопроверка без сети: in-memory TLS-handshake обоими наборами, результат в лог. */
    fun selfTest(ctx: Context) {
        val opener: (String) -> InputStream = { ctx.assets.open(it) }
        val parts = ArrayList<String>()
        for (set in AapCerts.SETS) {
            try {
                AppLog.i("AA selftest [$set]: ${describeValidity(AapCerts.load(opener, set))}", true)
            } catch (e: Throwable) {
                AppLog.i("AA selftest [$set]: сертификат/ключ не загрузились: ${e.javaClass.simpleName}: ${e.message}", true)
            }
            val r = AapTlsSelfTest.run(opener, set)
            AppLog.i("AA selftest [$set]: TLS in-memory ${if (r.ok) "OK" else "FAIL"} ${r.detail}", true)
            parts.add("$set=${if (r.ok) "OK" else "FAIL"}")
        }
        AaState.selftest = parts.joinToString(" ")
    }
}
