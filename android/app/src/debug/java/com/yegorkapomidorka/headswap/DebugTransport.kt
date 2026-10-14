package com.yegorkapomidorka.headswap

import android.content.Intent
import android.os.ParcelFileDescriptor
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Debug-транспорт: CarLife-поток по TCP к эмулятору ГУ на ПК вместо USB-аксессуара.
 * На ПК: `adb reverse tcp:7240 tcp:7240`, `python pc-headunit/carlife_hu.py`; на телефоне:
 * `adb shell am start-foreground-service -n com.yegorkapomidorka.headswap/.BridgeService --ei debug_tcp 7240`.
 */
object DebugTransport {
    const val EXTRA_PORT = "debug_tcp"

    fun port(intent: Intent?): Int? =
        intent?.getIntExtra(EXTRA_PORT, 0)?.takeIf { it in 1..65535 }

    /** Блокирующее подключение, не с main-потока. Сокет dup-ается в PFD, сам он больше не нужен. */
    fun connect(port: Int): ParcelFileDescriptor? = try {
        Socket().use { s ->
            s.tcpNoDelay = true
            s.connect(InetSocketAddress("127.0.0.1", port), 5000)
            ParcelFileDescriptor.fromSocket(s)
        }
    } catch (e: Exception) {
        AppLog.e("debug TCP 127.0.0.1:$port", e)
        null
    }
}
