package com.yegorkapomidorka.headswap.autosrv

import com.yegorkapomidorka.headswap.aa.AaBridgeCore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoServerTextTest {
    @Test
    fun menuItemsMatchRuAndEnIgnoringCaseAndYo() {
        assertTrue(AutoServerText.isStop("Остановить сервер головного устройства"))
        assertTrue(AutoServerText.isStop("ОСТАНОВИТЬ СЕРВЕР ГОЛОВНОГО УСТРОЙСТВА"))
        assertTrue(AutoServerText.isStop("Stop head unit server"))
        assertTrue(AutoServerText.isStop("  stop  HEAD UNIT server "))
        assertTrue(AutoServerText.isStart("Запустить сервер головного устройства"))
        assertTrue(AutoServerText.isStart("Start head unit server"))
        assertTrue(AutoServerText.isStart("Start head unit server (dev)"))          // contains
        assertTrue(AutoServerText.isStart("Запустить сервер головного устройства"))   // неразрывный пробел
    }

    @Test
    fun actualStringsOfAndroidAuto177() {
        // aapt2 dump strings: base.apk (en) и split_config.ru.apk, ресурсы menu_action_developer_head_unit_network_server[_stop]
        assertTrue(AutoServerText.isStart("Start head unit server"))
        assertTrue(AutoServerText.isStop("Stop head unit server"))
        assertTrue(AutoServerText.isStart("Вкл. сервер головного устр-ва"))
        assertTrue(AutoServerText.isStop("Остановить сервер головного устройства"))
        // соседние строки AA не должны нажиматься вместо пунктов сервера
        for (other in listOf(
            "Запуск сервера головного устройства…", "Остановка сервера головного устройства…", "Сервер головного устройства запущен",
            "Head unit server running", "Выйти из режима разработчика", "Quit developer mode", "Настройки разработчика", "Developer settings",
        )) {
            assertFalse(other, AutoServerText.isStart(other))
            assertFalse(other, AutoServerText.isStop(other))
        }
    }

    @Test
    fun stopAndStartDoNotCrossMatch() {
        assertFalse(AutoServerText.isStart("Остановить сервер головного устройства"))
        assertFalse(AutoServerText.isStop("Запустить сервер головного устройства"))
        assertFalse(AutoServerText.isStart("Stop head unit server"))
        assertFalse(AutoServerText.isStop("Start head unit server"))
        assertFalse(AutoServerText.isStop(null))
        assertFalse(AutoServerText.isStart(""))
        assertFalse(AutoServerText.isStop("Автоматический запуск Android Auto"))
    }

    @Test
    fun labelsFromAndroidAutoResourcesMatchAnyLanguageExactly() {
        try {
            // как будто AA на немецком: подписи прочитаны из его ресурсов
            AutoServerText.setAaLabels(
                start = listOf("Server für Haupteinheit starten"),
                stop = listOf("Server für Haupteinheit beenden"),
                more = listOf("Weitere Optionen"),
            )
            assertTrue(AutoServerText.isStart("Server für Haupteinheit starten"))
            assertTrue(AutoServerText.isStop("SERVER FÜR HAUPTEINHEIT BEENDEN"))
            assertFalse(AutoServerText.isStart("Server für Haupteinheit beenden"))
            assertFalse(AutoServerText.isStop("Server für Haupteinheit starten"))
            assertFalse("только целиком, не по contains", AutoServerText.isStart("Server für Haupteinheit starten…"))
            assertTrue(AutoServerText.isMore("Weitere Optionen"))
            assertTrue(AutoServerText.isMore("Weitere Optionen, Schaltfläche"))
            // встроенные ru/en списки продолжают работать вместе с подписями AA
            assertTrue(AutoServerText.isStart("Start head unit server"))
            assertTrue(AutoServerText.isStop("Остановить сервер головного устройства"))
            assertTrue(AutoServerText.isMore("More options"))
        } finally {
            AutoServerText.setAaLabels(emptyList(), emptyList(), emptyList())
        }
        assertFalse(AutoServerText.isStart("Server für Haupteinheit starten"))
    }

    @Test
    fun consentScreenByViewIdsOrTexts() {
        val none = emptyList<String>()
        // дампы первого запуска AA: приветствие и разрешения
        assertTrue(AutoServerText.isConsent(listOf("touch_outside", "exit_button", "dialog_title", "dialog_car_info"), none))
        assertTrue(AutoServerText.isConsent(listOf("secondary_button", "accept_button", "subtitle"), none))
        assertTrue(AutoServerText.isConsent(listOf("lock_allow_switch"), none))
        assertFalse("один accept_button - не согласие", AutoServerText.isConsent(listOf("accept_button", "toolbar"), none))
        assertFalse(AutoServerText.isConsent(listOf("secondary_button"), none))
        assertFalse(AutoServerText.isConsent(none, listOf("Android Auto", "Подключение")))
        // запасной путь по текстам
        assertTrue(AutoServerText.isConsent(none, listOf("Добро пожаловать в Android Auto!")))
        assertTrue(AutoServerText.isConsent(none, listOf("Welcome to Android Auto")))
        assertTrue(AutoServerText.isConsent(none, listOf("Эти разрешения необходимы Android Auto")))
        assertTrue(AutoServerText.isConsent(none, listOf("Android Auto needs these permissions")))
    }

    @Test
    fun moreButtonByContentDescription() {
        assertTrue(AutoServerText.isMore("Ещё"))
        assertTrue(AutoServerText.isMore("ЕЩЕ"))
        assertTrue(AutoServerText.isMore("Ещё..."))
        assertTrue(AutoServerText.isMore("More options"))
        assertTrue(AutoServerText.isMore("More options, button"))
        assertTrue(AutoServerText.isMore("Больше настроек"))
        assertFalse(AutoServerText.isMore("Больше"))
        assertFalse(AutoServerText.isMore("Настройки"))
        assertFalse("«Перейти вверх» и прочие кнопки тулбара", AutoServerText.isMore("Перейти вверх"))
        assertFalse(AutoServerText.isMore("Ещё один пункт"))
        assertFalse(AutoServerText.isMore("Подробнее"))
        assertFalse(AutoServerText.isMore(null))
    }
}

class AutoServerPolicyTest {
    private fun skip(
        t: Trigger, pref: Boolean = true, svc: Boolean = true, usb: Boolean = true, warm: Boolean = false, locked: Boolean = false,
        pending: Boolean = false, hold: Boolean = false,
    ) = AutoServerPolicy.skipReason(t, pref, svc, usb, warm, locked, pending, hold)

    @Test
    fun buttonRunsRegardlessOfPrefButNeedsService() {
        assertNull(skip(Trigger.BUTTON, pref = false, usb = false))
        assertTrue(skip(Trigger.BUTTON, svc = false)!!.contains("служба"))
    }

    @Test
    fun usbAttachSkipsOnWarmSessionOrDisabledPref() {
        assertNull(skip(Trigger.USB_ATTACH))
        assertTrue(skip(Trigger.USB_ATTACH, warm = true)!!.contains("тёплая"))
        assertTrue(skip(Trigger.USB_ATTACH, pref = false)!!.contains("выключен"))
        assertTrue(skip(Trigger.USB_ATTACH, svc = false) != null)
        assertTrue(skip(Trigger.USB_ATTACH, usb = false) != null)
    }

    @Test
    fun afterSessionOnlyWhileUsbIsStillConnected() {
        assertNull(skip(Trigger.AFTER_SESSION))
        assertTrue(skip(Trigger.AFTER_SESSION, usb = false) != null)
        assertTrue(skip(Trigger.AFTER_SESSION, pref = false) != null)
    }

    @Test
    fun retryOnlyAfterLockedAttempt() {
        assertTrue(skip(Trigger.RETRY, locked = false) != null)
        assertNull(skip(Trigger.RETRY, locked = true))
        assertTrue(skip(Trigger.RETRY, locked = true, usb = false) != null)
    }

    @Test
    fun retryCatchesUpStartSkippedBeforeServiceConnected() {
        // лог пользователя: USB_ATTACH пропущен за 86 мс до подключения службы, сервер ГУ так и не запустился
        assertNull(skip(Trigger.RETRY, pending = true))
        assertTrue(skip(Trigger.RETRY, pending = true, svc = false) != null)
        assertTrue(skip(Trigger.RETRY, pending = true, pref = false) != null)
        assertTrue(skip(Trigger.RETRY, pending = true, usb = false) != null)
    }

    @Test
    fun consentHoldSkipsAutoTriggersOnly() {
        assertEquals(AutoServerPolicy.CONSENT_SKIP, skip(Trigger.AFTER_SESSION, hold = true))
        assertEquals(AutoServerPolicy.CONSENT_SKIP, skip(Trigger.RETRY, locked = true, hold = true))
        assertTrue(AutoServerPolicy.CONSENT_SKIP.contains("настройку Android Auto"))
        assertNull(skip(Trigger.BUTTON, hold = true))
        assertNull(skip(Trigger.USB_ATTACH, hold = true))
        assertNull(skip(Trigger.AFTER_SESSION, hold = false))
    }

    @Test
    fun consentResultText() {
        for (t in Trigger.values()) assertEquals(AaBridgeCore.TEXT_AA_CONSENT, AutoServerPolicy.resultText(t, AutoResult.CONSENT))
        assertEquals("Завершите настройку Android Auto на телефоне", AaBridgeCore.TEXT_AA_CONSENT)
    }

    @Test
    fun placeholderTexts() {
        assertEquals(AaBridgeCore.TEXT_UNLOCK_PHONE, AutoServerPolicy.resultText(Trigger.USB_ATTACH, AutoResult.LOCKED))
        assertEquals("Разблокируйте телефон", AaBridgeCore.TEXT_UNLOCK_PHONE)
        assertEquals(AaBridgeCore.TEXT_START_SERVER, AutoServerPolicy.resultText(Trigger.USB_ATTACH, AutoResult.NOT_FOUND))
        assertEquals(AaBridgeCore.TEXT_AA_LOST, AutoServerPolicy.resultText(Trigger.AFTER_SESSION, AutoResult.TIMEOUT))
        assertEquals(AaBridgeCore.TEXT_WAIT_FRAME, AutoServerPolicy.resultText(Trigger.AFTER_SESSION, AutoResult.OK))
        assertEquals(AaBridgeCore.TEXT_RESTARTING_SERVER, AutoServerPolicy.runningText(Trigger.AFTER_SESSION))
        assertEquals(AaBridgeCore.TEXT_STARTING_SERVER, AutoServerPolicy.runningText(Trigger.USB_ATTACH))
        assertTrue(AutoServerPolicy.waitMs(Trigger.USB_ATTACH) in 7_000..9_000)      // ~8 с до коннектора
    }

    @Test
    fun enabledServicesListParsing() {
        val pkg = "com.yegorkapomidorka.headswap"
        val cls = "com.yegorkapomidorka.headswap.autosrv.AutoServerService"
        assertTrue(AutoServerPolicy.isListed("$pkg/$cls", pkg, cls))
        assertTrue(AutoServerPolicy.isListed("a.b/.C:$pkg/.autosrv.AutoServerService", pkg, cls))
        assertTrue(AutoServerPolicy.isListed("x/y:$pkg/$cls:z/w", pkg, cls))
        assertFalse(AutoServerPolicy.isListed("a.b/.C", pkg, cls))
        assertFalse(AutoServerPolicy.isListed("", pkg, cls))
        assertFalse(AutoServerPolicy.isListed(null, pkg, cls))
    }
}

/** Сценарий нажатий против поддельного экрана AA; время виртуальное, sleep мгновенный. */
class ServerAutomationTest {
    /**
     * Модель настроек AA: сервер запущен/остановлен, меню открывается кнопкой «Ещё».
     * stopLagMenus: сколько открытий меню после «Остановить» ещё показывают «Остановить» (сервер гаснет не мгновенно).
     */
    private class FakeAa(
        var serverRunning: Boolean,
        val locked: String? = null,
        val aaOpens: Boolean = true,
        val hasMoreButton: Boolean = true,
        val menuHasItems: Boolean = true,
        var stopLagMenus: Int = 0,
        val startIgnored: Boolean = false,
        /** Действие из actions, после которого поверх настроек появляется экран согласия AA ("open" = сразу). */
        val consentAfter: String? = null,
        val consentIds: List<String> = listOf("touch_outside", "exit_button", "dialog_title", "dialog_car_info"),
    ) : ServerUi {
        var t = 0L
        val actions = ArrayList<String>()
        var opened = false
        var menuOpen = false
        var lag = 0
        var returned = false
        var backs = 0

        override fun prepareScreen() = if (locked != null) Pair(false, locked) else Pair(true, "interactive=true keyguard=false")
        override fun openAaSettings(): Boolean {
            actions.add("open")
            opened = aaOpens
            return true
        }

        override fun aaVisible() = opened
        override fun clickMore(): Boolean {
            if (!opened || !hasMoreButton) return false
            if (menuOpen) return false
            menuOpen = true
            actions.add("more")
            if (lag > 0) lag--
            return true
        }

        override fun texts(): List<String> {
            if (!opened) return emptyList()
            val page = listOf("Android Auto", "Подключение", "Автомобили")
            if (!menuOpen || !menuHasItems) return page
            val showStop = serverRunning || lag > 0
            return page + if (showStop) "Остановить сервер головного устройства" else "Запустить сервер головного устройства"
        }

        override fun viewIds(): List<String> {
            if (!opened) return emptyList()
            return if (consentAfter != null && consentAfter in actions) consentIds else listOf("toolbar", "recycler")
        }

        override fun clickText(match: (CharSequence?) -> Boolean): Boolean {
            if (!menuOpen) return false
            val item = texts().firstOrNull { match(it) } ?: return false
            menuOpen = false
            if (AutoServerText.isStop(item)) {
                actions.add("stop")
                serverRunning = false
                lag = stopLagMenus
            } else {
                actions.add("start")
                if (!startIgnored) serverRunning = true
            }
            return true
        }

        override fun back() {
            backs++
            actions.add("back")
            menuOpen = false
        }

        override fun returnToApp() {
            returned = true
            actions.add("return")
        }

        override fun dump() = "dump"
        override fun now() = t
        override fun sleep(ms: Long) {
            t += ms
        }
    }

    private fun run(aa: FakeAa, onStarted: () -> Unit = {}): Pair<AutoOutcome, List<String>> {
        val log = ArrayList<String>()
        val o = ServerAutomation(aa, { log.add(it) }, onStarted).restart()
        return o to log
    }

    @Test
    fun runningServerIsStoppedThenStartedThenVerified() {
        val aa = FakeAa(serverRunning = true)
        var started = 0
        val (o, log) = run(aa) { started++ }
        assertEquals(o.toString(), AutoResult.OK, o.result)
        assertEquals(listOf("open", "more", "stop", "more", "start", "more", "back", "return"), aa.actions)
        assertTrue("в конце сервер запущен", aa.serverRunning)
        assertEquals("коннектор отпускается один раз, после Запустить", 1, started)
        assertTrue(log.any { it.contains("подтверждено") || it.contains("«Остановить…»") })
        assertTrue(aa.returned)
        assertTrue("весь сценарий укладывается в разумное время: ${o.tookMs}", o.tookMs < 15_000)
    }

    @Test
    fun stoppedServerIsJustStarted() {
        val aa = FakeAa(serverRunning = false)
        val (o, _) = run(aa)
        assertEquals(AutoResult.OK, o.result)
        assertEquals(listOf("open", "more", "start", "more", "back", "return"), aa.actions)
        assertTrue(aa.serverRunning)
        assertEquals("Остановить не нажимался", 0, aa.actions.count { it == "stop" })
    }

    @Test
    fun slowStopMakesAutomationWaitForStartItem() {
        val aa = FakeAa(serverRunning = true, stopLagMenus = 3)
        val (o, _) = run(aa)
        assertEquals(AutoResult.OK, o.result)
        assertTrue(aa.serverRunning)
        assertEquals(1, aa.actions.count { it == "stop" })
        assertEquals(1, aa.actions.count { it == "start" })
        assertTrue("при запаздывающем меню были лишние закрытия", aa.backs >= 3)
    }

    @Test
    fun lockedPhoneStopsBeforeTouchingAnything() {
        val aa = FakeAa(serverRunning = true, locked = "телефон заблокирован (keyguard)")
        val (o, _) = run(aa)
        assertEquals(AutoResult.LOCKED, o.result)
        assertTrue(aa.actions.isEmpty())
        assertTrue(aa.serverRunning)
    }

    @Test
    fun aaWindowNeverAppearsIsReportedNotFound() {
        val aa = FakeAa(serverRunning = true, aaOpens = false)
        val (o, _) = run(aa)
        assertEquals(AutoResult.NOT_FOUND, o.result)
        assertTrue(o.detail.contains("окно Android Auto"))
    }

    @Test
    fun missingMoreButtonOrEmptyMenuIsNotFound() {
        val noMore = run(FakeAa(serverRunning = true, hasMoreButton = false)).first
        assertEquals(AutoResult.NOT_FOUND, noMore.result)
        val noItems = FakeAa(serverRunning = true, menuHasItems = false)
        val o = run(noItems).first
        assertEquals(AutoResult.NOT_FOUND, o.result)
        assertEquals("после пустого меню оно закрыто", false, noItems.menuOpen)
        assertTrue(noItems.serverRunning)
    }

    @Test
    fun startThatDoesNotStickIsReportedAsError() {
        val aa = FakeAa(serverRunning = false, startIgnored = true)
        val (o, _) = run(aa)
        assertEquals(AutoResult.ERROR, o.result)
        assertTrue(o.detail.contains("снова предлагает «Запустить»"))
        assertEquals("Запустить нажат ещё дважды при проверке", 3, aa.actions.count { it == "start" })
    }

    @Test
    fun consentVisibleRightAfterOpenTouchesNothing() {
        val aa = FakeAa(serverRunning = true, consentAfter = "open")
        val (o, log) = run(aa)
        assertEquals(o.toString(), AutoResult.CONSENT, o.result)
        assertEquals(listOf("open"), aa.actions)
        assertEquals(0, aa.backs)
        assertEquals(false, aa.returned)
        assertEquals("дамп в лог один раз", 1, log.count { it.startsWith("дамп") })
        assertTrue(aa.serverRunning)
    }

    @Test
    fun consentAppearingDuringVerificationLeavesDialogOnScreen() {
        val aa = FakeAa(serverRunning = false, consentAfter = "start")
        var started = 0
        val (o, _) = run(aa) { started++ }
        assertEquals(o.toString(), AutoResult.CONSENT, o.result)
        assertEquals(listOf("open", "more", "start"), aa.actions)
        assertEquals(0, aa.backs)
        assertEquals(false, aa.returned)
        assertEquals(1, started)
    }

    @Test
    fun consentWithPermissionsDialogAlsoRecognized() {
        val aa = FakeAa(serverRunning = true, consentAfter = "open", consentIds = listOf("secondary_button", "accept_button", "subtitle"))
        assertEquals(AutoResult.CONSENT, run(aa).first.result)
        assertEquals(listOf("open"), aa.actions)
    }

    @Test
    fun lonelyAcceptButtonIsNotConsent() {
        val aa = FakeAa(serverRunning = false, consentAfter = "open", consentIds = listOf("accept_button", "toolbar"))
        val (o, _) = run(aa)
        assertEquals(AutoResult.OK, o.result)
        assertEquals(listOf("open", "more", "start", "more", "back", "return"), aa.actions)
    }

    @Test
    fun unlockDetectedWhenAaWindowDoesNotShowUp() {
        // экран «заблокировался» уже после запуска: окна нет и prepareScreen теперь говорит LOCKED
        val aa = object : ServerUi by FakeAa(serverRunning = true, aaOpens = false) {
            var calls = 0
            override fun prepareScreen(): Pair<Boolean, String> = if (calls++ == 0) Pair(true, "ok") else Pair(false, "keyguard")
        }
        val o = ServerAutomation(aa, {}).restart()
        assertEquals(AutoResult.LOCKED, o.result)
    }
}
