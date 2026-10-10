package com.yegorkapomidorka.headswap.aa

import java.util.Locale

/** Центральный прямоугольник исходного кадра в долях (u вправо, v вверх; кроп симметричный, поэтому ориентация v не важна). */
data class VideoCrop(val u0: Float, val v0: Float, val u1: Float, val v1: Float) {
    val isNone: Boolean get() = this == NONE

    companion object {
        val NONE = VideoCrop(0f, 0f, 1f, 1f)
    }
}

/**
 * Разрешение видео: единственный источник правды для AA (SDR), выхода CarLife (INIT_DONE, энкодер), кропа и тача.
 * Режим снимается один раз на подключение (AaBridgeCore.videoMode) и дальше везде берётся оттуда, prefs не читаются.
 */
enum class VideoMode(
    val title: String,
    val sub: String,
    /** Разрешение AA в SDR (SDR-строка AapMessages.serviceDiscoveryResponse). */
    val aaResolution: String,
    /** Поля margin_width / margin_height в VideoConfiguration SDR (суммарно, делятся пополам по краям). */
    val marginW: Int,
    val marginH: Int,
    val density: Int,
    /** Размер кадра, который уходит на ГУ (INIT_DONE и энкодер). */
    val outW: Int,
    val outH: Int,
    val bitrate: Int,
) {
    R800("800×480", "Для небольших экранов", "800x480", 0, 0, 120, 800, 480, 4_000_000),
    R720("1280×720 (стандарт)", "Подходит большинству ГУ", "1280x720", 0, 0, 160, 1280, 720, 4_000_000),
    R1080("1920×1080", "Чётче, но тяжелее для телефона и ГУ", "1920x1080", 0, 0, 240, 1920, 1080, 8_000_000),
    WIDE("Широкий 8:3 (обрезка)", "Для вытянутых экранов: 1280×480, 1920×720, 1728×648", "1280x720", 0, 240, 160, 1280, 480, 4_000_000);

    val aaW: Int = aaResolution.substringBefore('x').toInt()
    val aaH: Int = aaResolution.substringAfter('x').toInt()

    /** Кроп транскодера: центральная полоса без margin-ов (AA рисует интерфейс только внутри них). */
    val crop: VideoCrop =
        if (marginW == 0 && marginH == 0) VideoCrop.NONE
        else VideoCrop(marginW / 2f / aaW, marginH / 2f / aaH, 1f - marginW / 2f / aaW, 1f - marginH / 2f / aaH)

    /** Кроп возможен только при перекодировании: в passthrough WIDE недоступен. */
    val needsTranscode: Boolean get() = !crop.isNone

    /** Режим, который реально применится при данном переключателе перекодирования. */
    fun effective(transcode: Boolean): VideoMode = if (!transcode && needsTranscode) R720 else this

    /** Метка выхода для UI: «1280×720». */
    val outLabel: String get() = "${outW}×$outH"

    /**
     * Координаты тача ГУ (кадр outW×outH) -> координаты AA (кадр aaW×aaH): сдвиг на половину margin-ов.
     * Единственное место такого маппинга. Без margin-ов - 1:1 как есть, без clamp.
     */
    fun mapTouch(x: Int, y: Int): Pair<Int, Int> {
        if (marginW == 0 && marginH == 0) return x to y
        return (x + marginW / 2).coerceIn(0, aaW - 1) to (y + marginH / 2).coerceIn(0, aaH - 1)
    }

    /** Опции SDR для AA этого режима. */
    fun sdOptions(fullAudio: Boolean) = AapSdOptions(
        resolution = aaResolution, density = density, marginW = marginW, marginH = marginH, fullAudio = fullAudio,
    )

    /** Строка для лога: «видео-режим WIDE: AA 1280x720 margins 0x240 density 160, CarLife 1280x480, кроп v 0.167..0.833». */
    fun describe(): String {
        fun f(v: Float) = String.format(Locale.US, "%.3f", v)
        val cropText = if (crop.isNone) "без кропа" else buildString {
            append("кроп")
            if (crop.u0 != 0f) append(" u ${f(crop.u0)}..${f(crop.u1)}")
            if (crop.v0 != 0f) append(" v ${f(crop.v0)}..${f(crop.v1)}")
        }
        return "видео-режим $name: AA $aaResolution margins ${marginW}x$marginH density $density, CarLife ${outW}x$outH, $cropText"
    }

    companion object {
        val DEFAULT = R720

        /** По имени из prefs; неизвестное/пустое = по умолчанию. */
        fun parse(key: String?): VideoMode = values().firstOrNull { it.name == key } ?: DEFAULT
    }
}
