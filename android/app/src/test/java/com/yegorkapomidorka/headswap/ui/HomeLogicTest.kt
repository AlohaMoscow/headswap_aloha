package com.yegorkapomidorka.headswap.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeLogicTest {
    private fun inp(
        running: Boolean = false,
        status: String = "не запущено",
        protocol: Boolean = false,
        serverUp: Boolean = false,
        autoReady: Boolean = false,
        lost: Boolean = false,
        graceLeft: Int = -1,
        usbAudio: Boolean = true,
        setupError: String? = null,
    ) = Inputs(running, status, protocol, serverUp, autoReady, lost, graceLeft, 8, usbAudio, setupError)

    @Test
    fun errorWhenCertificateMissing() {
        val s = HomeLogic.compute(inp(setupError = "Нет сертификата head unit"))
        assertEquals(Phase.ERROR, s.phase)
        assertEquals("Нет сертификата head unit", s.sub)
        assertEquals(Phase.WAITING, HomeLogic.compute(inp(serverUp = true)).phase)
    }

    @Test
    fun offWhenNothingStarted() {
        val s = HomeLogic.compute(inp())
        assertEquals(Phase.OFF, s.phase)
        assertEquals(PrimaryAction.START_SERVER, s.primary)
        assertEquals(StageState.TODO, s.stages[0].second.first)
    }

    @Test
    fun waitingWhenServerStartedOrAuto() {
        assertEquals(Phase.WAITING, HomeLogic.compute(inp(serverUp = true)).phase)
        val auto = HomeLogic.compute(inp(autoReady = true))
        assertEquals(Phase.WAITING, auto.phase)
        assertEquals(OutlineAction.OPEN_AA, auto.outline)
    }

    @Test
    fun connectedFromBridgeStatus() {
        val s = HomeLogic.compute(inp(running = true, status = "мост: AA -> CarLife (перекодирование)"))
        assertEquals(Phase.CONNECTED, s.phase)
        assertEquals(OutlineAction.STOP, s.outline)
        assertEquals("USB", s.stages[4].second.second)
        assertEquals("Видео · звук по USB", s.notifText)
        val noAudio = HomeLogic.compute(inp(running = true, status = "мост: AA -> CarLife", usbAudio = false))
        assertEquals(StageState.PEND, noAudio.stages[4].second.first)
    }

    @Test
    fun reconnectingShowsCountdown() {
        val s = HomeLogic.compute(inp(running = true, status = "мост: USB потерян, AA жива 8 с", graceLeft = 5))
        assertEquals(Phase.RECONNECTING, s.phase)
        assertEquals(5, s.graceLeftS)
        assertEquals("USB отключился · ещё 5 с", s.notifText)
        assertEquals(StageState.PAUSE, s.stages[4].second.first)
    }

    @Test
    fun errorAfterLossWhenServiceStopped() {
        val s = HomeLogic.compute(inp(lost = true, status = "мост: AA отключился"))
        assertEquals(Phase.ERROR, s.phase)
        assertEquals(PrimaryAction.RESTART_SERVER, s.primary)
        assertEquals(true, s.logLink)
        assertEquals("Перезапустите сервер ГУ", s.notifTitle)
    }

    @Test
    fun cableButServerRefusesIsOff() {
        val s = HomeLogic.compute(inp(running = true, status = "мост: жду сервер ГУ AA", protocol = true))
        assertEquals(Phase.OFF, s.phase)
        assertEquals(StageState.DONE, s.stages[1].second.first)
    }

    @Test
    fun cableConnectingIsWaiting() {
        val s = HomeLogic.compute(inp(running = true, status = "мост: старт"))
        assertEquals(Phase.WAITING, s.phase)
        assertEquals("Подключаюсь", s.title)
        assertNull(s.primary)
    }
}
