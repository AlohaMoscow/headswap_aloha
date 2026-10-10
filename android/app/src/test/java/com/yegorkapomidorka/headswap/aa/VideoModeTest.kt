package com.yegorkapomidorka.headswap.aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.yegorkapomidorka.headswap.aa.AapMessages as M

class VideoModeTest {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test
    fun table() {
        data class Row(val aa: String, val mw: Int, val mh: Int, val density: Int, val w: Int, val h: Int, val bitrate: Int)
        val expected = mapOf(
            VideoMode.R800 to Row("800x480", 0, 0, 120, 800, 480, 4_000_000),
            VideoMode.R720 to Row("1280x720", 0, 0, 160, 1280, 720, 4_000_000),
            VideoMode.R1080 to Row("1920x1080", 0, 0, 240, 1920, 1080, 8_000_000),
            VideoMode.WIDE to Row("1280x720", 0, 240, 160, 1280, 480, 4_000_000),
        )
        assertEquals(expected.keys, VideoMode.values().toSet())
        for ((m, r) in expected) {
            assertEquals(m.name, r.aa, m.aaResolution)
            assertEquals(m.name, r.mw, m.marginW)
            assertEquals(m.name, r.mh, m.marginH)
            assertEquals(m.name, r.density, m.density)
            assertEquals(m.name, r.w, m.outW)
            assertEquals(m.name, r.h, m.outH)
            assertEquals(m.name, r.bitrate, m.bitrate)
            // выход CarLife = кадр AA без margin-ов
            assertEquals(m.name, m.aaW - m.marginW, m.outW)
            assertEquals(m.name, m.aaH - m.marginH, m.outH)
        }
        assertEquals(VideoMode.R720, VideoMode.DEFAULT)
    }

    @Test
    fun cropFractions() {
        for (m in listOf(VideoMode.R800, VideoMode.R720, VideoMode.R1080)) assertTrue(m.name, m.crop.isNone)
        val c = VideoMode.WIDE.crop
        assertFalse(c.isNone)
        assertEquals(0f, c.u0, 1e-6f)
        assertEquals(1f, c.u1, 1e-6f)
        assertEquals(120f / 720f, c.v0, 1e-6f)          // 0.1667
        assertEquals(600f / 720f, c.v1, 1e-6f)          // 0.8333
        assertTrue(VideoMode.WIDE.needsTranscode)
        assertFalse(VideoMode.R1080.needsTranscode)
    }

    @Test
    fun effectiveAndParse() {
        assertEquals(VideoMode.R720, VideoMode.WIDE.effective(transcode = false))
        assertEquals(VideoMode.WIDE, VideoMode.WIDE.effective(transcode = true))
        assertEquals(VideoMode.R1080, VideoMode.R1080.effective(transcode = false))
        assertEquals(VideoMode.R800, VideoMode.R800.effective(transcode = false))
        assertEquals(VideoMode.WIDE, VideoMode.parse("WIDE"))
        assertEquals(VideoMode.R720, VideoMode.parse(null))
        assertEquals(VideoMode.R720, VideoMode.parse("мусор"))
    }

    @Test
    fun describeLine() {
        assertEquals(
            "видео-режим WIDE: AA 1280x720 margins 0x240 density 160, CarLife 1280x480, кроп v 0.167..0.833",
            VideoMode.WIDE.describe(),
        )
        assertEquals(
            "видео-режим R720: AA 1280x720 margins 0x0 density 160, CarLife 1280x720, без кропа",
            VideoMode.R720.describe(),
        )
    }

    @Test
    fun touchMappingR720IsIdentity() {
        assertEquals(640 to 360, VideoMode.R720.mapTouch(640, 360))
        assertEquals(0 to 0, VideoMode.R720.mapTouch(0, 0))
        assertEquals(40 to 675, VideoMode.R720.mapTouch(40, 675))
        for (m in listOf(VideoMode.R800, VideoMode.R1080)) assertEquals(m.name, 7 to 9, m.mapTouch(7, 9))
    }

    @Test
    fun touchMappingWideShiftsByHalfMargin() {
        val w = VideoMode.WIDE
        assertEquals(640 to 120, w.mapTouch(640, 0))
        assertEquals(640 to 599, w.mapTouch(640, 479))
        assertEquals(0 to 360, w.mapTouch(0, 240))
        assertEquals(1279 to 360, w.mapTouch(1279, 240))
        // за пределами кадра ГУ - clamp в кадр AA
        assertEquals(0 to 0, w.mapTouch(-5, -300))
        assertEquals(1279 to 719, w.mapTouch(2000, 900))
    }

    @Test
    fun sdrWideHasMarginHeightAndFullSizeTouchscreen() {
        val wide = hex(M.serviceDiscoveryResponse(VideoMode.WIDE.sdOptions(fullAudio = false)).payload)
        // VideoConfiguration: res=2 fps=2 margin_w=0 margin_h=240 (20 f0 01) density 160 par 10000 codec 3
        assertTrue(wide, wide.contains("08021002180020f00128a00140904e5003"))
        // touchscreen остаётся 1280x720 (поле 2: 1280 = 80 0a, 720 = d0 05)
        assertTrue(wide, wide.contains("1206" + "08800a" + "10d005"))
    }

    @Test
    fun sdrDefaultModeEqualsPlainDefault() {
        val viaMode = M.serviceDiscoveryResponse(VideoMode.R720.sdOptions(fullAudio = false)).payload
        assertEquals(hex(M.serviceDiscoveryResponse().payload), hex(viaMode))
    }

    @Test
    fun sdrOtherModes() {
        val r1080 = hex(M.serviceDiscoveryResponse(VideoMode.R1080.sdOptions(false)).payload)
        assertTrue(r1080, r1080.contains("0803100218002000" + "28f001"))      // res=3, margins 0, density 240
        val r800 = hex(M.serviceDiscoveryResponse(VideoMode.R800.sdOptions(false)).payload)
        assertTrue(r800, r800.contains("0801100218002000" + "2878"))          // res=1, margins 0, density 120
    }

    @Test
    fun coreMapsTouchByMode() {
        val core = AaBridgeCore({ _, _ -> })
        val peer = FakePeer()
        core.peer = peer
        core.onCarlifeTouch(0, 640, 0)                       // по умолчанию R720: 1:1
        core.videoMode = VideoMode.WIDE
        core.onCarlifeTouch(0, 640, 0)
        core.onCarlifeTouch(2, 640, 479)
        core.onCarlifeTouch(1, 640, 479)
        assertEquals(
            listOf(Triple(0, 640, 0), Triple(0, 640, 120), Triple(2, 640, 599), Triple(1, 640, 599)),
            peer.touches.toList(),
        )
    }

    @Test
    fun coreLogsRawAndMappedTouch() {
        val logs = ArrayList<String>()
        val core = AaBridgeCore({ l, _ -> logs.add(l) })
        core.peer = FakePeer()
        core.videoMode = VideoMode.WIDE
        core.onCarlifeTouch(0, 640, 0)
        assertTrue(logs.toString(), logs.any { it.contains("640,0") && it.contains("640,120") })
    }
}
