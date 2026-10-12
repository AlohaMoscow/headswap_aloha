package com.yegorkapomidorka.headswap.aa

import android.content.Context
import android.os.SystemClock
import com.yegorkapomidorka.headswap.AnnexB
import com.yegorkapomidorka.headswap.AppLog
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Самопроверка транскодера без AA и USB: `--es cmd transcode_test`. Берёт самый крупный aa-*.h264 (сырой Annex-B от AA, кладётся в getExternalFilesDir через adb push),
 * гонит его через декодер -> энкодер ~10 с (30 fps, посреди пауза 3 с без входных кадров: энкодер обязан
 * повторять кадр) и логирует кадры на входе/выходе, fps, первые NAL выхода, задержку.
 */
object TranscodeSelfCheck {
    private const val TOTAL_MS = 10_000L
    private const val PAUSE_AT_FRAME = 90          // после 3 с при 30 fps
    private const val PAUSE_MS = 3_000L
    private const val FRAME_MS = 33L

    fun run(ctx: Context, tuning: String = "") {
        Thread({ runBlocking(ctx, tuning) }, "transcode-test").start()
    }

    private fun runBlocking(ctx: Context, tuning: String) {
        val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        val file = dir.listFiles { f -> f.name.startsWith("aa-") && f.name.endsWith(".h264") }?.maxByOrNull { it.length() }
        if (file == null) {
            AppLog.i("transcode_test: в $dir нет aa-*.h264")
            return
        }
        val (cfg, aus) = H264Stream.split(file.readBytes())
        AppLog.i("transcode_test: файл ${file.name} ${file.length() / 1024} КиБ, access unit-ов ${aus.size} (IDR ${aus.count { it.idr }}), config ${cfg?.size ?: 0} Б")
        if (cfg == null || aus.isEmpty()) {
            AppLog.i("transcode_test: нет SPS/PPS или кадров")
            return
        }
        val firstIdr = aus.indexOfFirst { it.idr }
        if (firstIdr < 0) {
            AppLog.i("transcode_test: в файле нет IDR")
            return
        }

        // выход энкодера как он уходит на ГУ (SPS/PPS склеены только с первым IDR, как в AaBridge.onEncoderFrame):
        // enc-out.h264 рядом, смотреть ffmpeg на ПК
        val dump = File(dir, "enc-out.h264").outputStream().buffered()
        var dumpGotIdr = false

        class Out : TranscodeOutput {
            val frames = AtomicLong()
            val keys = AtomicLong()
            @Volatile var firstNals: List<Int>? = null
            @Volatile var csd: ByteArray? = null
            @Volatile var pauseStartFrames = -1L
            @Volatile var pauseEndFrames = -1L
            @Volatile var errors = 0

            override fun onEncoderConfig(csd: ByteArray) {
                this.csd = csd
            }

            override fun onEncoderFrame(data: ByteArray, key: Boolean) {
                if (frames.getAndIncrement() == 0L) {
                    val c = csd
                    firstNals = AnnexB.nalTypes(if (c != null) AnnexB.glue(c, data) else data)
                }
                if (key) keys.incrementAndGet()
                val c = csd
                synchronized(dump) {
                    if (!dumpGotIdr && !key) return
                    dump.write(if (!dumpGotIdr && c != null && !AnnexB.startsWithSps(data)) AnnexB.glue(c, data) else data)
                    dumpGotIdr = true
                }
            }

            override fun onTranscodeError(msg: String) {
                errors++
                AppLog.i("transcode_test: ОШИБКА $msg")
            }

            override fun onNeedAaKeyFrame() {
                AppLog.i("transcode_test: декодер потерял кадр")
            }
        }

        val out = Out()
        // "placeholder": параллельно второй энкодер заглушки, как в живой сессии (VideoSource + транскодер)
        val ph = if (tuning.contains("placeholder")) try {
            com.yegorkapomidorka.headswap.VideoSource(1280, 720, 30, 4_000_000, object : com.yegorkapomidorka.headswap.VideoSource.Listener {
                override fun onConfig(csd: ByteArray) {}
                override fun onFrame(data: ByteArray, key: Boolean) {}
                override fun onError(msg: String, t: Throwable?) {
                    AppLog.e("transcode_test: ОШИБКА заглушки $msg", t)
                }
            }).also { it.banner = "test"; it.start(); AppLog.i("transcode_test: заглушка ${it.encoderName} запущена") }
        } catch (e: Exception) {
            AppLog.e("transcode_test: заглушка не создана", e)
            null
        } else null
        if (tuning.contains("phonly")) {
            Thread.sleep(TOTAL_MS)
            ph?.let { AppLog.i("transcode_test: только заглушка, отрисовала ${it.framesRendered} кадров"); it.stop() }
            return
        }
        val t = try {
            AaTranscoder(1280, 720, 30, 4_000_000, out, { AppLog.i("transcode_test: $it") }, tuning).also { it.start() }
        } catch (e: Exception) {
            AppLog.e("transcode_test: транскодер не создан", e)
            return
        }
        t.feedConfig(cfg)
        t.requestSyncFrame()
        val t0 = SystemClock.elapsedRealtime()
        var fed = 0
        var idx = firstIdr
        var pauseStartMs = -1L
        var pauseEndMs = -1L
        var lastStats = t0
        while (SystemClock.elapsedRealtime() - t0 < TOTAL_MS) {
            if (fed == PAUSE_AT_FRAME && pauseStartMs < 0 && !tuning.contains("nopause")) {
                pauseStartMs = SystemClock.elapsedRealtime()
                out.pauseStartFrames = out.frames.get()
                AppLog.i("transcode_test: пауза входа ${PAUSE_MS / 1000} с (кадров на выходе до паузы ${out.pauseStartFrames})")
                Thread.sleep(PAUSE_MS)
                pauseEndMs = SystemClock.elapsedRealtime()
                out.pauseEndFrames = out.frames.get()
                AppLog.i("transcode_test: пауза кончилась: за ${(pauseEndMs - pauseStartMs) / 1000.0} с энкодер выдал ${out.pauseEndFrames - out.pauseStartFrames} кадров = %.1f fps".format((out.pauseEndFrames - out.pauseStartFrames) * 1000.0 / (pauseEndMs - pauseStartMs)))
            }
            if (idx >= aus.size) idx = firstIdr          // конец файла: по кругу с IDR
            val au = aus[idx++]
            t.feedFrame(au.data, fed * 33_333L)
            fed++
            if (SystemClock.elapsedRealtime() - lastStats >= 5000) {
                AppLog.i("transcode_test: ${t.statsLine()}")
                lastStats = SystemClock.elapsedRealtime()
            }
            Thread.sleep(FRAME_MS)
        }
        Thread.sleep(500)
        val totalMs = SystemClock.elapsedRealtime() - t0
        AppLog.i("transcode_test: ${t.statsLine()}")
        val frames = out.frames.get()
        AppLog.i(
            "transcode_test ИТОГ: вход AA=$fed кадров, на выходе энкодера=$frames кадров (ключевых ${out.keys.get()}), %.1f fps за %.1f с, ".format(frames * 1000.0 / totalMs, totalMs / 1000.0) +
                "декодировано=${t.totalDecoded.get()}, первые NAL выхода (SPS+PPS склеены с первым кадром)=${out.firstNals}, ошибок=${out.errors}, энкодер=${t.encoderName}"
        )
        t.stop()
        synchronized(dump) { dump.close() }
        AppLog.i("transcode_test: выход энкодера записан в ${File(dir, "enc-out.h264")}")
        ph?.let { AppLog.i("transcode_test: заглушка отрисовала ${it.framesRendered} кадров"); it.stop() }
    }
}
