package com.yegorkapomidorka.headswap.link.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarLogicTest {
    @Test
    fun macComparisonIgnoresCase() {
        assertTrue(CarMac.same("AA:BB:CC:DD:EE:FF", "aa:bb:cc:dd:ee:ff"))
        assertTrue(CarMac.same("aa:bb:cc:dd:ee:ff", "aa:bb:cc:dd:ee:ff"))
        assertFalse(CarMac.same("AA:BB:CC:DD:EE:FF", "AA:BB:CC:DD:EE:00"))
    }

    @Test
    fun macNullNeverMatches() {
        assertFalse(CarMac.same(null, "aa:bb:cc:dd:ee:ff"))
        assertFalse(CarMac.same("aa:bb:cc:dd:ee:ff", null))
        assertFalse(CarMac.same(null, null))
    }

    @Test
    fun debounceFiresOnlyAfterDelay() {
        var now = 1_000L
        val d = AutoOffDebounce(10_000L) { now }
        assertFalse(d.fire())      // не взведён
        d.arm()
        assertTrue(d.pending)
        now += 9_999L
        assertFalse(d.fire())
        assertTrue(d.pending)      // рано: остаётся взведённым
        now += 1L
        assertTrue(d.fire())
        assertFalse(d.pending)
        assertFalse(d.fire())      // срабатывает один раз
    }

    @Test
    fun debounceCancelledByReconnect() {
        var now = 0L
        val d = AutoOffDebounce(10_000L) { now }
        d.arm()
        now += 5_000L
        d.cancel()                 // пришёл ACL_CONNECTED
        now += 60_000L
        assertFalse(d.fire())
        assertFalse(d.pending)
    }

    @Test
    fun debounceBurstRearmsFromLastDisconnect() {
        // всплески ACL у ГУ: каждый новый DISCONNECTED сдвигает срок
        var now = 0L
        val d = AutoOffDebounce(10_000L) { now }
        d.arm()
        now += 7_000L
        d.arm()
        now += 7_000L              // 14 с от первого, 7 с от второго
        assertFalse(d.fire())
        now += 3_000L
        assertTrue(d.fire())
    }

    @Test
    fun minIntervalLimitsRate() {
        var now = 5_000L
        val m = MinInterval(60_000L) { now }
        assertTrue(m.tryAcquire())
        now += 59_999L
        assertFalse(m.tryAcquire())
        now += 1L
        assertTrue(m.tryAcquire())
        assertEquals(false, m.tryAcquire())
    }

    /** Все условия выполнены; тесты по одному ломают по условию. */
    private fun request(
        carWifiSet: Boolean = true,
        waiting: Boolean = true,
        session: Boolean = false,
        usb: Boolean = false,
        requesting: Boolean = false,
        waitedMs: Long = CAR_NET_DELAY_MS,
        sixOfSix: Boolean = false,
    ) = CarNetPolicy.shouldRequest(carWifiSet, waiting, session, usb, requesting, waitedMs, sixOfSix)

    @Test
    fun carNetRequestedAfterDelayWhileWaiting() {
        assertTrue(request())
        assertTrue(request(waitedMs = CAR_NET_DELAY_MS + 1))
    }

    @Test
    fun carNetNotRequestedBeforeDelay() {
        assertFalse(request(waitedMs = CAR_NET_DELAY_MS - 1))
        assertFalse(request(waitedMs = 0))
    }

    @Test
    fun carNetNotRequestedWithoutCarWifi() {
        assertFalse(request(carWifiSet = false))
    }

    @Test
    fun carNetNotRequestedWhenNotWaiting() {
        assertFalse(request(waiting = false))
    }

    @Test
    fun carNetNotRequestedDuringAnySession() {
        assertFalse(request(session = true))
        assertFalse(request(usb = true))
        assertFalse(request(session = true, usb = true))
    }

    @Test
    fun carNetNotRequestedTwiceOrAfterSixOfSix() {
        assertFalse(request(requesting = true))
        assertFalse(request(sixOfSix = true))
    }

    @Test
    fun carNetReleasedWhenWaitingStopsOrServiceDies() {
        assertTrue(CarNetPolicy.shouldRelease(CarNetEvent.AUTO_OFF))
        assertTrue(CarNetPolicy.shouldRelease(CarNetEvent.STOP))
        assertTrue(CarNetPolicy.shouldRelease(CarNetEvent.USB_WINS))
        assertTrue(CarNetPolicy.shouldRelease(CarNetEvent.DESTROY))
    }

    @Test
    fun carNetHeldAcrossSessions() {
        assertFalse(CarNetPolicy.shouldRelease(CarNetEvent.SESSION_START))
        assertFalse(CarNetPolicy.shouldRelease(CarNetEvent.SESSION_END))
    }
}
