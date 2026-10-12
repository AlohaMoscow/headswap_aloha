package com.yegorkapomidorka.headswap.autosrv

/** Что автоматизация умеет делать с экраном; реализует AccessibilityService, в тестах подменяется. */
interface ServerUi {
    /** Экран готов к работе (включён, без блокировки). false = заблокирован, причина в detail. */
    fun prepareScreen(): Pair<Boolean, String>

    /** Открыть настройки Android Auto заново. false = система не дала запустить. */
    fun openAaSettings(): Boolean

    /** Активное окно принадлежит Android Auto. */
    fun aaVisible(): Boolean

    /** Найти кнопку «Ещё» (contentDescription) и нажать её. */
    fun clickMore(): Boolean

    /** Тексты всех узлов окон AA (меню видно в них же). */
    fun texts(): List<String>

    /** View id (entry name без пакета, как в дампе) узлов окон AA. */
    fun viewIds(): List<String>

    /** Нажать узел (или ближайшего кликабельного предка) с текстом, подходящим под предикат. */
    fun clickText(match: (CharSequence?) -> Boolean): Boolean

    fun back()

    /** Вернуться в наше приложение (или Home). */
    fun returnToApp()

    /** Компактный дамп дерева узлов для лога, когда что-то не нашлось. */
    fun dump(): String

    fun now(): Long

    fun sleep(ms: Long)
}

/**
 * Сценарий «перезапустить сервер головного устройства» в настройках Android Auto:
 * открыть настройки -> ⋮ -> «Остановить…» (если есть) -> ⋮ -> «Запустить…» -> проверка -> назад в приложение.
 * Каждый шаг пишет строку в лог (префикс AUTOSRV ставит вызывающий).
 */
class ServerAutomation(
    private val ui: ServerUi,
    private val log: (String) -> Unit,
    /** Сервер запущен (Запустить нажат и прошла пауза на старт): коннектор можно отпускать. */
    private val onStarted: () -> Unit = {},
    private val pageTimeoutMs: Long = 6_000,
    private val menuTimeoutMs: Long = 3_000,
    private val pollMs: Long = 150,
    private val settleAfterStartMs: Long = 1_000,
) {
    private val t0 = ui.now()

    private fun done(r: AutoResult, detail: String): AutoOutcome {
        val o = AutoOutcome(r, detail, ui.now() - t0)
        log("итог: $o")
        return o
    }

    private fun waitUntil(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val end = ui.now() + timeoutMs
        while (true) {
            if (cond()) return true
            if (ui.now() >= end) return false
            ui.sleep(pollMs)
        }
    }

    /** Поверх настроек экран первой настройки AA (согласие/разрешения): дальше нельзя ничего нажимать. */
    private class ConsentScreen : RuntimeException()

    /** Проверка перед каждым действием; при экране согласия прерывает сценарий (restart() его ловит). */
    private fun guard() {
        if (AutoServerText.isConsent(ui.viewIds(), ui.texts())) throw ConsentScreen()
    }

    private fun guardedClick(match: (CharSequence?) -> Boolean): Boolean {
        guard()
        return ui.clickText(match)
    }

    private fun guardedBack() {
        guard()
        ui.back()
    }

    private fun menuHas(): Pair<Boolean, Boolean> {
        val t = ui.texts()
        return Pair(t.any { AutoServerText.isStop(it) }, t.any { AutoServerText.isStart(it) })
    }

    /** Нажать «Ещё» и дождаться пунктов сервера. null = меню не открылось (или без нужных пунктов). */
    private fun openMenu(step: String): Pair<Boolean, Boolean>? {
        for (attempt in 1..2) {
            if (!waitUntil(menuTimeoutMs) { guard(); ui.clickMore() }) {
                log("$step: кнопка «Ещё» не найдена (попытка $attempt)")
                log("дамп: " + ui.dump())
                continue
            }
            log("$step: нажала «Ещё» (попытка $attempt)")
            var found: Pair<Boolean, Boolean>? = null
            waitUntil(menuTimeoutMs) {
                guard()
                val h = menuHas()
                if (h.first || h.second) found = h
                found != null
            }
            val f = found
            if (f != null) {
                log("$step: меню открыто: Остановить=${f.first} Запустить=${f.second}")
                return f
            }
            log("$step: в меню нет пунктов сервера (попытка $attempt); тексты: ${ui.texts().take(20)}")
            log("дамп: " + ui.dump())
            guardedBack()
            ui.sleep(400)
        }
        return null
    }

    fun restart(): AutoOutcome = try {
        runScenario()
    } catch (_: ConsentScreen) {
        // без back и returnToApp: диалог должен остаться на экране, пока пользователь не пройдёт настройку
        log("дамп: " + ui.dump())
        done(AutoResult.CONSENT, "на телефоне экран настройки Android Auto (согласие/разрешения), жду пользователя")
    }

    private fun runScenario(): AutoOutcome {
        val (ready, why) = ui.prepareScreen()
        log("экран: $why")
        if (!ready) return done(AutoResult.LOCKED, why)

        if (!ui.openAaSettings()) return done(AutoResult.ERROR, "система не дала открыть настройки AA")
        log("настройки AA запущены, жду окно")
        if (!waitUntil(pageTimeoutMs) { ui.aaVisible() }) {
            val (stillReady, why2) = ui.prepareScreen()
            return if (!stillReady) done(AutoResult.LOCKED, why2)
            else done(AutoResult.NOT_FOUND, "окно Android Auto не появилось за ${pageTimeoutMs} мс (блок фонового запуска?)")
        }
        log("окно AA на экране")
        guard()
        ui.sleep(300)                     // тулбар дорисовывается после появления окна

        var items = openMenu("шаг 1") ?: return done(AutoResult.NOT_FOUND, "меню сервера ГУ не открылось")
        if (items.first) {
            if (!guardedClick { AutoServerText.isStop(it) }) return done(AutoResult.ERROR, "пункт «Остановить…» не нажался")
            log("шаг 2: нажала «Остановить сервер головного устройства»")
            ui.sleep(700)
            var started = false
            for (attempt in 1..5) {
                items = openMenu("шаг 3.$attempt") ?: return done(AutoResult.NOT_FOUND, "после «Остановить» меню не открылось")
                if (items.second) {
                    if (!guardedClick { AutoServerText.isStart(it) }) return done(AutoResult.ERROR, "пункт «Запустить…» не нажался")
                    log("шаг 4: нажала «Запустить сервер головного устройства»")
                    started = true
                    break
                }
                log("шаг 3.$attempt: «Запустить» ещё нет (сервер останавливается), закрываю меню и жду")
                guardedBack()
                ui.sleep(800)
            }
            if (!started) return done(AutoResult.TIMEOUT, "после «Остановить» пункт «Запустить» не появился")
        } else if (items.second) {
            if (!guardedClick { AutoServerText.isStart(it) }) return done(AutoResult.ERROR, "пункт «Запустить…» не нажался")
            log("шаг 2: сервер был остановлен, нажала «Запустить сервер головного устройства»")
        } else {
            guardedBack()
            return done(AutoResult.NOT_FOUND, "в меню нет ни «Остановить», ни «Запустить»")
        }

        ui.sleep(settleAfterStartMs)
        onStarted()

        // проверка: после «Запустить» в меню должен быть «Остановить…»
        var verified = false
        var startAgain = false
        for (attempt in 1..2) {
            val v = openMenu("проверка.$attempt")
            if (v == null) {
                log("проверка.$attempt: меню не открылось")
                break
            }
            if (v.first) {
                verified = true
                startAgain = false
                log("проверка.$attempt: в меню «Остановить…» (сервер запущен)")
                guardedBack()
                ui.sleep(300)
                break
            }
            startAgain = true
            log("проверка.$attempt: в меню снова «Запустить…»: сервер не запустился, нажимаю ещё раз")
            if (!guardedClick { AutoServerText.isStart(it) }) break
            ui.sleep(settleAfterStartMs)
        }
        guard()
        ui.returnToApp()
        return when {
            verified -> done(AutoResult.OK, "сервер запущен, подтверждено меню")
            startAgain -> done(AutoResult.ERROR, "после «Запустить» меню снова предлагает «Запустить»")
            else -> done(AutoResult.OK, "«Запустить» нажато, подтвердить меню не удалось")
        }
    }
}
