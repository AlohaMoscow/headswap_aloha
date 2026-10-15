package com.yegorkapomidorka.headswap.link.bt

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.CarLifeState
import com.yegorkapomidorka.headswap.link.diag.WLog
import java.io.IOException
import java.util.UUID

/**
 * Диагностика ветки A (Bluetooth + Wi-Fi Direct): RFCOMM-серверы `carlife`, как у Baidu MD (BluetoothClient:28-29,76,119).
 * Если ГУ умеет беспроводной CarLife через Wi-Fi Direct, она сама подключается сюда. Проба отвечает на первые два шага
 * (WIRELESS_INFO_REQUEST, TARGET_INFO_REQUEST) и пишет в WL-лог, что прислала ГУ. В P2P-группу не входит, IP не отдаёт.
 * Секретов в лог не пишет (см. BtSummary). Живёт, пока сервис ждёт машину по Wi-Fi.
 */
class CarlifeBtProbe(private val ctx: Context) {
    @Volatile private var running = false
    private val servers = ArrayList<BluetoothServerSocket>()
    private val lock = Any()
    @Volatile private var accepts = 0

    fun hasPermission(): Boolean =
        Build.VERSION.SDK_INT < 31 || ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start() {
        synchronized(lock) {
            if (running) return
            running = true
        }
        val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter
        when {
            adapter == null -> return fail("нет адаптера")
            !hasPermission() -> return fail("нет разрешения BLUETOOTH_CONNECT")
            !adapter.isEnabled -> return fail("Bluetooth выключен")
        }
        for ((tag, uuid) in UUIDS) {
            val ss = try {
                adapter!!.listenUsingRfcommWithServiceRecord(SERVICE_NAME, uuid)
            } catch (e: Exception) {
                WLog.ev(WLog.BT, "listen-fail", "uuid" to tag, "err" to e.message)
                continue
            }
            synchronized(lock) { servers += ss }
            WLog.ev(WLog.BT, "listen", "uuid" to tag, "name" to SERVICE_NAME)
            Thread({ acceptLoop(tag, ss) }, "bt-accept-$tag").apply { isDaemon = true }.start()
        }
        CarLifeState.btProbe = if (synchronized(lock) { servers.isEmpty() }) "не слушает" else "слушаю carlife, ГУ не подключалась"
    }

    fun stop(why: String) {
        val list = synchronized(lock) {
            if (!running) return
            running = false
            servers.toList().also { servers.clear() }
        }
        for (s in list) try { s.close() } catch (_: IOException) {}
        WLog.ev(WLog.BT, "stop", "why" to why, "accepts" to accepts)
    }

    private fun fail(why: String) {
        running = false
        CarLifeState.btProbe = "не слушает: $why"
        WLog.ev(WLog.BT, "off", "why" to why)
    }

    private fun acceptLoop(tag: String, ss: BluetoothServerSocket) {
        while (running) {
            val s = try {
                ss.accept()
            } catch (_: IOException) {
                return
            }
            accepts++
            talk(tag, s)
        }
    }

    @SuppressLint("MissingPermission")
    private fun talk(tag: String, s: BluetoothSocket) {
        val dev = s.remoteDevice
        val name = try { dev?.name } catch (_: SecurityException) { null }
        WLog.ev(WLog.BT, "accept", "uuid" to tag, "dev" to WLog.maskMac(dev?.address), "devName" to name)
        AppLog.i("BT carlife: ГУ подключилась по Bluetooth ($tag, ${name ?: "?"})")
        CarLifeState.btProbe = "ГУ подключилась ($tag, ${name ?: "?"})"
        val t0 = SystemClock.elapsedRealtime()
        val parser = BtFrameParser()
        var why = "?"
        var targetAsked = false
        try {
            val out = s.outputStream
            val inp = s.inputStream
            fun send(type: Int) {
                out.write(BtMsg.frame(type))
                out.flush()
                WLog.ev(WLog.BT, "tx", "type" to BtMsg.name(type))
            }
            Thread.sleep(300)
            send(BtMsg.MD_WIRELESS_INFO_REQUEST)
            val buf = ByteArray(1024)
            while (running && SystemClock.elapsedRealtime() - t0 < TALK_MS) {
                val n = inp.read(buf)
                if (n < 0) { why = "EOF"; break }
                for (f in parser.feed(buf, n)) {
                    val kv: List<Pair<String, Any?>> = when (f.type) {
                        BtMsg.HU_WIRELESS_INFO -> BtSummary.wirelessInfo(f.body)
                        BtMsg.HU_TARGET_INFO -> BtSummary.targetInfo(f.body)
                        BtMsg.HU_STATUS -> BtSummary.status(f.body)
                        else -> emptyList()
                    }
                    WLog.ev(WLog.BT, "rx", "type" to BtMsg.name(f.type), "len" to f.body.size, *kv.toTypedArray())
                    when (f.type) {
                        BtMsg.HU_WIRELESS_INFO -> {
                            CarLifeState.btProbe = "ГУ ответила: ${kv.joinToString(" ") { "${it.first}=${it.second}" }}"
                            if (!targetAsked) {
                                targetAsked = true
                                send(BtMsg.MD_TARGET_INFO_REQUEST)
                            }
                        }
                        BtMsg.HU_TARGET_INFO ->
                            CarLifeState.btProbe = "ГУ прислала реквизиты Wi-Fi Direct: ${kv.joinToString(" ") { "${it.first}=${it.second}" }}"
                    }
                }
            }
            if (why == "?") why = if (running) "проба окончена (${TALK_MS / 1000} с)" else "остановлено"
        } catch (e: Exception) {
            why = "ошибка: ${e.message}"
        } finally {
            try { s.close() } catch (_: IOException) {}
            WLog.ev(WLog.BT, "close", "why" to why, "aliveMs" to SystemClock.elapsedRealtime() - t0, "skipped" to parser.skipped)
        }
    }

    companion object {
        const val SERVICE_NAME = "carlife"
        val UUIDS = listOf(
            "spp" to UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"),
            "carlife" to UUID.fromString("a45bc7e5-bb50-4949-9de1-f78299cf6d78"),
        )
        private const val TALK_MS = 60_000L
    }
}
