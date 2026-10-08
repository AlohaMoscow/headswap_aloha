package com.yegorkapomidorka.headswap

/** Состояние для экрана; пишут сервис и сессия, читает activity (один процесс). */
object CarLifeState {
    @Volatile var status: String = "не запущено"
    @Volatile var accessory: String = "-"
    @Volatile var protocol: String = "-"
    @Volatile var auth: String = "-"
    @Volatile var video: String = "-"
    @Volatile var touch: String = "-"
    @Volatile var running: Boolean = false

    fun resetSession() {
        protocol = "-"
        auth = "-"
        video = "-"
        touch = "-"
    }

    fun summary(): String =
        "статус: $status\nаксессуар: $accessory\nпротокол: $protocol\nauth: $auth\nвидео: $video\nтач: $touch"
}
