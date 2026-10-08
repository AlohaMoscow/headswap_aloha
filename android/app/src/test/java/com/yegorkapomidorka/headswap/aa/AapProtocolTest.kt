package com.yegorkapomidorka.headswap.aa

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.yegorkapomidorka.headswap.aa.AapFraming as F
import com.yegorkapomidorka.headswap.aa.AapMessages as M

/** Эталоны (golden.txt) сгенерированы pc-headunit (aap/messages.py, aap/framing.py) на Python protobuf. */
object Golden {
    private val map: Map<String, ByteArray> by lazy {
        val text = Golden::class.java.getResourceAsStream("/aa/golden.txt")!!.readBytes().toString(Charsets.UTF_8)
        text.lines().filter { it.contains('=') }.associate {
            val (k, v) = it.split('=', limit = 2)
            k.trim() to hex(v.trim())
        }
    }

    fun hex(s: String): ByteArray {
        val t = s.replace(" ", "")
        return ByteArray(t.length / 2) { t.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    operator fun get(name: String): ByteArray = map[name] ?: error("нет эталона $name")
}

class AapFramingTest {
    @Test
    fun parserBulkAndExtendedHeaderAnySlicing() {
        val bulk = F.buildFrame(2, 0x0B, "abc".toByteArray())
        val first = F.buildFrame(2, 0x09, ByteArray(5) { 'X'.code.toByte() }, total = 12)
        val last = F.buildFrame(2, 0x0A, ByteArray(7) { 'Y'.code.toByte() })
        assertArrayEquals(byteArrayOf(2, 0x09, 0, 5, 0, 0, 0, 12), first.copyOfRange(0, 8))     // 4-байтный total
        val stream = bulk + first + last
        for (step in listOf(1, 2, 3, 7, stream.size)) {
            val p = F.Parser()
            val frames = ArrayList<F.Frame>()
            var i = 0
            while (i < stream.size) {
                val n = minOf(step, stream.size - i)
                frames += p.feed(stream, i, n)
                i += n
            }
            assertEquals("step $step", 3, frames.size)
            assertEquals(listOf(2 to 0x0B, 2 to 0x09, 2 to 0x0A), frames.map { it.chan to it.flags })
            assertEquals("abc", String(frames[0].body))
            assertEquals(null, frames[0].total)
            assertEquals(12, frames[1].total)
            assertEquals("XXXXX", String(frames[1].body))
            assertEquals(null, frames[2].total)
            assertEquals("YYYYYYY", String(frames[2].body))
            assertEquals(0, p.pending)
        }
    }

    @Test
    fun buildFrameFirstNeedsTotal() {
        try {
            F.buildFrame(2, 0x09, byteArrayOf(1))
            error("должно упасть")
        } catch (_: NullPointerException) {
        } catch (_: IllegalArgumentException) {
        }
        // FIRST|LAST (0x0b) - total не пишется
        assertEquals(4 + 1, F.buildFrame(2, 0x0B, byteArrayOf(1)).size)
    }

    @Test
    fun flagsControlOnNonControlChannel() {
        assertEquals(0x0B, F.messageFlags(0, M.SERVICE_DISCOVERY_RESPONSE))
        assertEquals(0x0F, F.messageFlags(2, M.CHANNEL_OPEN_RESPONSE))        // control-тип 8 на ch2
        assertEquals(0x0B, F.messageFlags(2, M.MEDIA_CONFIG))                 // 0x8003 - не control-тип
        assertEquals(0x0B, F.messageFlags(1, M.SENSOR_EVENT))
    }

    @Test
    fun splitMessageFragmentFlagsAndReassembly() {
        val plaintext = F.typed(M.MEDIA_DATA, ByteArray(256 * 200) { (it % 256).toByte() })          // 51202 Б
        val parts = F.splitMessage(plaintext, M.CH_VIDEO, M.MEDIA_DATA, 0x4000)
        assertEquals(listOf(0x09, 0x08, 0x08, 0x0A), parts.map { it.flags })
        assertEquals(plaintext.size, parts[0].total)
        assertTrue(parts.drop(1).all { it.total == null })
        val joined = parts.fold(ByteArray(0)) { a, p -> a + p.chunk }
        assertArrayEquals(plaintext, joined)
        val warnings = ArrayList<String>()
        val r = F.Reassembler { warnings.add(it) }
        var out: ByteArray? = null
        for (p in parts) out = r.push(M.CH_VIDEO, p.flags, p.chunk, p.total)
        assertArrayEquals(plaintext, out)
        assertTrue(warnings.isEmpty() && r.openRuns().isEmpty())
        val one = F.splitMessage(byteArrayOf(0, 0, 'h'.code.toByte(), 'i'.code.toByte()), 2, 0)
        assertEquals(1, one.size)
        assertEquals(0x0B, one[0].flags)
        assertNull(one[0].total)
    }

    @Test
    fun splitMessageExactMultipleOfFragment() {
        val plaintext = ByteArray(0x8000) { 1 }
        val parts = F.splitMessage(plaintext, 2, 0)
        assertEquals(listOf(0x09, 0x0A), parts.map { it.flags })
    }

    @Test
    fun reassemblerInterleavedChannelsAndAnomalies() {
        val w = ArrayList<String>()
        val r = F.Reassembler { w.add(it) }
        fun b(s: String) = s.toByteArray()
        assertNull(r.push(2, 0x09, b("aa"), 6))
        assertArrayEquals(b("bulk"), r.push(5, 0x0B, b("bulk")))           // другой канал не мешает
        assertNull(r.push(2, 0x08, b("bb")))
        assertArrayEquals(b("aabbcc"), r.push(2, 0x0A, b("cc")))
        assertTrue(w.isEmpty())
        assertNull(r.push(2, 0x0A, b("zz")))
        assertTrue(w.last().contains("без FIRST"))
        assertNull(r.push(2, 0x09, b("aa"), 4))
        assertNull(r.push(2, 0x09, b("dd"), 4))
        assertTrue(w.last().contains("старая сброшена"))
        assertArrayEquals(b("ddee"), r.push(2, 0x0A, b("ee"), null))
        assertNull(r.push(2, 0x09, b("a"), 99))
        assertArrayEquals(b("ab"), r.push(2, 0x0A, b("b")))
        assertTrue(w.last().contains("99"))
    }
}

class AapMessagesTest {
    private fun eq(name: String, actual: ByteArray) = assertArrayEquals("эталон $name", Golden[name], actual)

    @Test
    fun versionRequestFrame() {
        eq("version_request", F.buildFrame(0, F.FLAGS_PLAIN_BULK, M.versionRequestBody()))
        assertArrayEquals(Golden.hex("00 03 00 06 00 01 00 01 00 02"), F.buildFrame(0, F.FLAGS_PLAIN_BULK, M.versionRequestBody()))
    }

    @Test
    fun authCompleteFrame() {
        eq("auth_complete", F.buildPlain(0, M.AUTH_COMPLETE, M.AUTH_COMPLETE_DATA))
        assertArrayEquals(Golden.hex("00 03 00 04 00 04 08 00"), F.buildPlain(0, M.AUTH_COMPLETE, M.AUTH_COMPLETE_DATA))
    }

    @Test
    fun serviceDiscoveryResponseDefault() {
        val o = M.serviceDiscoveryResponse()
        assertEquals(0, o.chan)
        assertEquals(6, o.type)
        eq("sdr_default", o.payload)
        // VideoConfiguration, закодированный руками: res=2 fps=2 margins 0 0 density 160 par 10000 codec 3
        val hex = o.payload.joinToString("") { "%02x".format(it) }
        assertTrue(hex.contains("08 02 10 02 18 00 20 00 28 a0 01 40 90 4e 50 03".replace(" ", "")))
    }

    @Test
    fun serviceDiscoveryResponseVariant() {
        eq("sdr_800", M.serviceDiscoveryResponse(AapSdOptions(resolution = "800x480", fps = 60, density = 140)).payload)
    }

    @Test
    fun builders() {
        eq("channel_open_response", M.channelOpenResponse(2).payload)
        assertEquals(8, M.channelOpenResponse(2).type)
        eq("ping_response", M.pingResponse(123456789).payload)
        eq("nav_focus", M.navFocusNotification().payload)
        eq("audio_focus_gain", M.audioFocusResponse(1)!!.payload)
        eq("audio_focus_transient", M.audioFocusResponse(2)!!.payload)
        eq("audio_focus_duck", M.audioFocusResponse(3)!!.payload)
        eq("audio_focus_release", M.audioFocusResponse(4)!!.payload)
        assertNull(M.audioFocusResponse(0))
        eq("audio_focus_unsolicited", M.audioFocusGainUnsolicited().payload)
        eq("byebye_request", M.byebyeRequest().payload)
        eq("byebye_response", M.byebyeResponse().payload)
        eq("media_config_video", M.mediaConfig(2, 12).payload)
        eq("media_config_audio", M.mediaConfig(5, 30).payload)
        eq("video_focus_unsol", M.videoFocusNotification(true, true).payload)
        eq("video_focus_sol", M.videoFocusNotification(true, false).payload)
        eq("media_ack", M.mediaAck(2, 7).payload)
        eq("media_ack_neg", M.mediaAck(2, 0).payload)
        eq("mic_refuse", M.micResponse(false).payload)
        eq("mic_ok", M.micResponse(true).payload)
        eq("binding_response", M.bindingResponse().payload)
        eq("sensor_start_response", M.sensorStartResponse().payload)
        eq("driving_status", M.drivingStatusEvent(0).payload)
        eq("night", M.nightModeEvent(false).payload)
        eq("touch_down", M.touchEvent(M.TOUCH_DOWN, 640, 360, 123456789012L).payload)
        eq("touch_up", M.touchEvent(M.TOUCH_UP, 1279, 719, 5).payload)
        eq("touch_move", M.touchEvent(M.TOUCH_MOVE, 0, 0, 1).payload)
    }

    @Test
    fun keyEventBytes() {
        // InputReport{1: ts=5, 4: KeyEvent{1: Key{1: 87, 2: true, 3: 0, 4: false}}}
        val down = M.keyEvent(5, 87, true)
        assertEquals(M.CH_INPUT, down.chan)
        assertEquals(M.INPUT_EVENT, down.type)
        assertArrayEquals(Golden.hex("08 05 22 0a 0a 08 08 57 10 01 18 00 20 00"), down.payload)
        val up = M.keyEvent(300, 88, false)
        assertArrayEquals(Golden.hex("08 ac 02 22 0a 0a 08 08 58 10 00 18 00 20 00"), up.payload)
    }

    @Test
    fun versionResponseParse() {
        val v = M.parseVersionResponse(Golden.hex("00 01 00 07 00 00"))!!
        assertEquals(listOf(1, 7, 0), listOf(v.major, v.minor, v.status))
        val neg = M.parseVersionResponse(Golden.hex("00 01 00 07 ff ff 0a 00"))!!
        assertEquals(-1, neg.status)
        assertArrayEquals(Golden.hex("0a 00"), neg.options)
        assertNull(M.parseVersionResponse(byteArrayOf(0)))
    }

    @Test
    fun describeDecodesNames() {
        val d = M.describe(0, M.PING_REQUEST, Golden.hex("08 95 9a ef 3a"))
        assertTrue(d, d.contains("timestamp=123456789"))
        assertNotNull(M.messageName(2, 1))
        assertEquals("MEDIA_CODEC_CONFIG", M.messageName(2, 1))
        assertEquals("VERSION_REQUEST", M.messageName(0, 1))
        assertEquals("INPUT_EVENT", M.messageName(3, 0x8001))
        assertEquals("MEDIA_START", M.messageName(2, 0x8001))
    }
}
