package com.yegorkapomidorka.headswap.autosrv

import java.util.Locale

/**
 * Тексты интерфейса Android Auto, по которым автоматизация находит кнопку «Ещё» и пункты меню сервера ГУ.
 * Чистая JVM-логика: сравнение без регистра, «ё» = «е», пробелы схлопываются, contains.
 */
object AutoServerText {
    /** contentDescription кнопки меню ⋮ в тулбаре настроек AA. */
    val MORE = listOf("ещё", "more options", "дополнительные параметры", "дополнительно", "больше настроек")

    val STOP = listOf(
        "остановить сервер головного устройства",
        "остановить сервер гу",
        "stop head unit server",
        "stop head-unit server",
        "stop headunit server",
    )

    /** Русская строка AA 17.7 (split_config.ru, menu_action_developer_head_unit_network_server): «Вкл. сервер головного устр-ва». */
    val START = listOf(
        "вкл. сервер головного устр-ва",
        "включить сервер головного устройства",
        "запустить сервер головного устройства",
        "запустить сервер гу",
        "start head unit server",
        "start head-unit server",
        "start headunit server",
    )

    /**
     * Подписи, прочитанные из ресурсов самого Android Auto на его текущем языке (AaLabels.load): работают на любом языке.
     * Сравниваются целиком (после norm), не по contains: у незнакомого языка «запуск» может оказаться частью «остановки».
     * Пусто = не прочитались (нет пакета, другое имя ресурса), тогда остаются только списки выше.
     */
    @Volatile
    var aaStart: List<String> = emptyList()

    @Volatile
    var aaStop: List<String> = emptyList()

    @Volatile
    var aaMore: List<String> = emptyList()

    fun setAaLabels(start: List<String>, stop: List<String>, more: List<String>) {
        aaStart = start.map { norm(it) }.filter { it.isNotEmpty() }
        aaStop = stop.map { norm(it) }.filter { it.isNotEmpty() }
        aaMore = more.map { norm(it).trimEnd('.', '…', ' ') }.filter { it.isNotEmpty() }
    }

    private fun equalsAny(text: CharSequence?, normalized: List<String>): Boolean {
        if (normalized.isEmpty()) return false
        val t = norm(text)
        return t.isNotEmpty() && t in normalized
    }

    fun norm(s: CharSequence?): String =
        (s?.toString() ?: "").lowercase(Locale.ROOT).replace('ё', 'е').replace(' ', ' ')
            .replace(Regex("\\s+"), " ").trim()

    private fun containsAny(text: CharSequence?, list: List<String>): Boolean {
        val t = norm(text)
        if (t.isEmpty()) return false
        return list.any { t.contains(norm(it)) }
    }

    fun isStop(text: CharSequence?) = equalsAny(text, aaStop) || containsAny(text, STOP)

    fun isStart(text: CharSequence?) = equalsAny(text, aaStart) || containsAny(text, START)

    /**
     * Кнопка «Ещё»: описание целиком равно варианту (без хвоста «...»); «more options» допускается как часть
     * («More options, button»). Короткие слова вроде «ещё» по contains не берём: попадётся любой пункт со словом.
     */
    fun isMore(desc: CharSequence?): Boolean {
        val t = norm(desc).trimEnd('.', '…', ' ')
        if (t.isEmpty()) return false
        if (aaMore.any { t == it || (it.contains(' ') && t.contains(it)) }) return true
        return MORE.any { m ->
            val n = norm(m)
            t == n || (n.contains(' ') && t.contains(n))
        }
    }
}
