package com.yegorkapomidorka.headswap

import android.content.Intent
import android.os.ParcelFileDescriptor

/** В release TCP-транспорта нет: мост работает только с USB-аксессуаром. */
object DebugTransport {
    fun port(intent: Intent?): Int? = null

    fun connect(port: Int): ParcelFileDescriptor? = null
}
