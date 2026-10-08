package com.yegorkapomidorka.headswap

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Байтовые примеры взяты из docs/carlife-protocol.md §9.2. */
class ProtocolTest {

    private fun hex(s: String): ByteArray {
        val t = s.replace(" ", "")
        return ByteArray(t.length / 2) { t.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private fun frame(chan: Int, inner: ByteArray) = Wire.outerHeader(chan, inner.size) + inner

    @Test
    fun huProtocolVersion() {
        val body = Msgs.protocolVersion(1, 0)
        assertArrayEquals(hex("08 01 10 00"), body)
        val inner = Wire.cmdInner(Msg.HU_PROTOCOL_VERSION, body)
        assertArrayEquals(hex("00 04 00 00 00 01 80 01 08 01 10 00"), inner)
        assertArrayEquals(hex("00 00 00 01 00 00 00 0C"), Wire.outerHeader(Ch.CMD, inner.size))
    }

    @Test
    fun matchStatus() {
        val inner = Wire.cmdInner(Msg.PROTOCOL_VERSION_MATCH_STATUS, Msgs.matchStatus(1))
        assertArrayEquals(hex("00 02 00 00 00 01 00 02 08 01"), inner)
        assertArrayEquals(hex("00 00 00 01 00 00 00 0A"), Wire.outerHeader(Ch.CMD, inner.size))
    }

    @Test
    fun videoEncoderInit() {
        val inner = Wire.cmdInner(Msg.VIDEO_ENCODER_INIT, Msgs.videoEncoderInfo(1280, 720, 0))
        assertArrayEquals(hex("00 08 00 00 00 01 80 07 08 80 0A 10 D0 05 18 00"), inner)
        assertArrayEquals(hex("00 00 00 01 00 00 00 10"), Wire.outerHeader(Ch.CMD, inner.size))
    }

    @Test
    fun videoEncoderInitDone() {
        // спека §9.1 шаг 8: 08 80 0A 10 D0 05 18 1E (1280x720, 30)
        assertArrayEquals(hex("08 80 0A 10 D0 05 18 1E"), Msgs.videoEncoderInfo(1280, 720, 30))
        val inner = Wire.cmdInner(Msg.VIDEO_ENCODER_INIT_DONE, Msgs.videoEncoderInfo(1280, 720, 30))
        assertArrayEquals(hex("00 08 00 00 00 01 00 08 08 80 0A 10 D0 05 18 1E"), inner)
    }

    @Test
    fun videoEncoderStartEmptyBody() {
        val inner = Wire.cmdInner(Msg.VIDEO_ENCODER_START, null)
        assertArrayEquals(hex("00 00 00 00 00 01 80 09"), inner)
        assertArrayEquals(hex("00 00 00 01 00 00 00 08"), Wire.outerHeader(Ch.CMD, inner.size))
    }

    @Test
    fun foregroundIsEightBytes() {
        assertArrayEquals(hex("00 00 00 00 00 01 00 1B"), Wire.cmdInner(Msg.FOREGROUND, null))
    }

    @Test
    fun authenMessages() {
        assertArrayEquals(hex("08 01"), Msgs.authenResult(true))
        assertArrayEquals(hex("08 00"), Msgs.authenResult(false))
        assertArrayEquals(hex("00 02 00 00 00 01 00 4B 08 01"), Wire.cmdInner(Msg.MD_AUTHEN_RESULT, Msgs.authenResult(true)))
        // пустой encryptValue: тег 0x0A, длина 0
        assertArrayEquals(hex("0A 00"), Msgs.authenResponse(""))
        assertArrayEquals(hex("0A 03 61 62 63"), Msgs.authenResponse("abc"))
        val req = PbReader.parse(Msgs.authenRequest("CarLife_1.0.0"))!!
        assertEquals("CarLife_1.0.0", PbReader.str(req, 1))
        assertEquals(
            "{randomValue=\"CarLife_1.0.0\"}",
            PbText.render(Msgs.authenRequest("CarLife_1.0.0"), Schemas.authenRequest)
        )
    }

    @Test
    fun touchActionDown() {
        val body = hex("08 00 10 80 05 18 E8 02")
        assertArrayEquals(body, Msgs.touchAction(0, 640, 360))
        val inner = Wire.cmdInner(Msg.TOUCH_ACTION, body)
        assertArrayEquals(hex("00 08 00 00 00 06 80 01 08 00 10 80 05 18 E8 02"), inner)
        assertArrayEquals(hex("00 00 00 06 00 00 00 10"), Wire.outerHeader(Ch.TOUCH, inner.size))

        val m = Wire.parseCmd(inner)!!
        assertEquals(Msg.TOUCH_ACTION, m.type)
        val f = PbReader.parse(m.body)!!
        assertEquals(0, PbReader.int(f, 1))
        assertEquals(640, PbReader.int(f, 2))
        assertEquals(360, PbReader.int(f, 3))
        assertEquals("{action=0 x=640 y=360}", PbText.render(m.body, Schemas.touchAction))
    }

    @Test
    fun videoDataHeader() {
        val h264 = hex("00 00 00 01 67 AA 00 00 00 01 68 BB 00 00 00 01 65 CC DD")
        val inner = Wire.dataInner(Msg.VIDEO_DATA, 0x11223344, h264)
        assertArrayEquals(hex("00 00 00 13  11 22 33 44  00 02 00 01") + h264, inner)
        val m = Wire.parseData(inner)!!
        assertEquals(Msg.VIDEO_DATA, m.type)
        assertEquals(0x11223344, m.time)
        assertEquals(h264.size, m.declaredLen)
        assertArrayEquals(h264, m.data)
        assertFalse(m.truncated)
        // heartbeat: len 0, тип 0x00020002
        assertArrayEquals(hex("00 00 00 00  00 00 00 01  00 02 00 02"), Wire.dataInner(Msg.VIDEO_HEARTBEAT, 1, null))
    }

    @Test
    fun parserHandlesAnySplit() {
        val a = frame(Ch.CMD, Wire.cmdInner(Msg.HU_PROTOCOL_VERSION, Msgs.protocolVersion(1, 0)))
        val b = frame(Ch.TOUCH, Wire.cmdInner(Msg.TOUCH_ACTION, Msgs.touchAction(0, 640, 360)))
        val c = frame(Ch.CMD, Wire.cmdInner(Msg.VIDEO_ENCODER_START, null))
        val all = a + b + c

        for (step in listOf(1, 2, 3, 7, 8, 9, 16, 1000)) {
            val p = FrameParser()
            val got = ArrayList<Pair<Int, ByteArray>>()
            var o = 0
            while (o < all.size) {
                val n = minOf(step, all.size - o)
                p.feed(all, o, n) { ch, inner -> got.add(ch to inner) }
                o += n
            }
            assertEquals("step=$step", 3, got.size)
            assertEquals(Ch.CMD, got[0].first)
            assertEquals(Ch.TOUCH, got[1].first)
            assertEquals(Msg.TOUCH_ACTION, Wire.parseCmd(got[1].second)!!.type)
            assertEquals(Msg.VIDEO_ENCODER_START, Wire.parseCmd(got[2].second)!!.type)
            assertEquals(0, p.pending())
        }
    }

    @Test
    fun parserDetectsDesync() {
        val p = FrameParser()
        var thrown = false
        try {
            // chan = 0xFFFFFFFF
            p.feed(hex("FF FF FF FF 00 00 00 04 00 00 00 00"), 0, 12) { _, _ -> }
        } catch (e: ProtocolDesync) {
            thrown = true
        }
        assertTrue(thrown)
    }

    @Test
    fun writeSequenceSplitsHeaderAndChunks() {
        val small = Wire.cmdInner(Msg.VIDEO_ENCODER_START, null)
        val s = Wire.writeSequence(Ch.CMD, small)
        assertEquals(2, s.size)
        assertEquals(8, s[0].size)
        assertArrayEquals(small, s[1])

        val big = Wire.dataInner(Msg.VIDEO_DATA, 0, ByteArray(40000) { 1 })
        val seq = Wire.writeSequence(Ch.VIDEO, big)
        assertEquals(8, seq[0].size)
        assertEquals(big.size, Wire.u32(seq[0], 4))
        assertEquals(Ch.VIDEO, Wire.u32(seq[0], 0))
        assertTrue(seq.drop(1).all { it.size <= 16384 })
        assertEquals(4, seq.size) // 8 + 3 куска: 16384 + 16384 + 7256
        val joined = seq.drop(1).fold(ByteArray(0)) { acc, x -> acc + x }
        assertArrayEquals(big, joined)
    }

    @Test
    fun cmdTruncationAndShort() {
        assertNull(Wire.parseCmd(hex("00 00 00")))
        val m = Wire.parseCmd(hex("00 05 00 00 00 01 80 01 08 01"))!!
        assertTrue(m.truncated)
        assertEquals(2, m.body.size)
        assertEquals(5, m.declaredLen)
    }

    @Test
    fun deviceInfoRoundTrip() {
        val info = Msgs.deviceInfo(DeviceInfo(os = "Android", brand = "Xiaomi", sdk = "36", sdkInt = 36, serial = "unknown"))
        val f = PbReader.parse(info)!!
        assertEquals("Android", PbReader.str(f, 1))
        assertEquals("Xiaomi", PbReader.str(f, 4))
        assertEquals("unknown", PbReader.str(f, 16))
        assertEquals("36", PbReader.str(f, 20))
        assertEquals(36, PbReader.int(f, 21))
        assertNull(PbReader.str(f, 2)) // пустые поля не пишутся
    }

    @Test
    fun negativeInt32IsTenBytes() {
        val b = PbWriter().int32(1, -1).toByteArray()
        assertArrayEquals(hex("08 FF FF FF FF FF FF FF FF FF 01"), b)
        assertEquals(-1, PbReader.int(PbReader.parse(b)!!, 1))
    }

    @Test
    fun featureConfigNestedRender() {
        // cnt=1, featureConfig{key="VOICE_MIC", value=1}
        val inner = PbWriter().string(1, "VOICE_MIC").int32(2, 1).toByteArray()
        val body = PbWriter().int32(1, 1).bytes(2, inner).toByteArray()
        assertEquals(
            "{cnt=1 featureConfig={key=\"VOICE_MIC\" value=1}}",
            PbText.render(body, Schemas.featureConfigList)
        )
    }

    @Test
    fun annexBNormalizeAndGlue() {
        val three = hex("00 00 01 67 AA 00 00 01 68 BB 00 00 00 01 65 CC")
        val norm = AnnexB.normalize(three)
        assertArrayEquals(hex("00 00 00 01 67 AA 00 00 00 01 68 BB 00 00 00 01 65 CC"), norm)
        assertEquals(listOf(7, 8, 5), AnnexB.nalTypes(norm))
        assertTrue(AnnexB.startsWithSps(norm))
        // уже 4-байтные не трогаем
        val four = hex("00 00 00 01 41 11 22")
        assertTrue(AnnexB.normalize(four) === four)
        assertFalse(AnnexB.startsWithSps(four))

        val csd = hex("00 00 00 01 67 AA 00 00 00 01 68 BB")
        val idr = hex("00 00 00 01 65 CC DD")
        val glued = AnnexB.glue(csd, idr)
        assertEquals(listOf(7, 8, 5), AnnexB.nalTypes(glued))
        assertEquals(csd.size + idr.size, glued.size)
    }

    @Test
    fun mdInfoLiesSdk29OnAndroid13Plus() {
        assertEquals(Pair("29", 29), DeviceInfo.reportedSdk(36))
        assertEquals(Pair("29", 29), DeviceInfo.reportedSdk(33))
        assertEquals(Pair("32", 32), DeviceInfo.reportedSdk(32))
    }

    private fun mdInfo(): DeviceInfo {
        val (sdk, sdkInt) = DeviceInfo.reportedSdk(36)
        return DeviceInfo(
            os = "Android", brand = "Xiaomi", model = "TestPhone-1", manufacturer = "Xiaomi", release = "16",
            sdk = sdk, sdkInt = sdkInt, serial = "unknown", btaddress = "unknown",
            carlifeVersion = Handshake.MD_VERSION, carlifeType = Handshake.MD_TYPE_CARLIFE_APP,
        )
    }

    @Test
    fun mdInfoBody() {
        val body = Msgs.deviceInfo(mdInfo())
        assertTrue(body.isNotEmpty())
        val f = PbReader.parse(body)!!
        assertEquals("Android", PbReader.str(f, 1))
        assertEquals("29", PbReader.str(f, 20))
        assertEquals(29, PbReader.int(f, 21))
        assertEquals("unknown", PbReader.str(f, 23))
        assertEquals("8.6.7", PbReader.str(f, 24))
        assertEquals(1, PbReader.int(f, 25))
        assertEquals("Xiaomi", PbReader.str(f, 4))
        val txt = PbText.render(body, Schemas.deviceInfo)
        assertTrue(txt.contains("carlifeversion=\"8.6.7\""))
        assertTrue(txt.contains("carlifeType=1"))
    }

    @Test
    fun moduleStatusIs44BytesForHuV2() {
        val body = Msgs.moduleStatusList(2)
        assertEquals(44, body.size)
        val f = PbReader.parse(body)!!
        assertEquals(7, PbReader.int(f, 1))
        val pairs = PbReader.all(f, 2).map { PbReader.parse(it.data!!)!! }.map { PbReader.int(it, 1) to PbReader.int(it, 2) }
        assertEquals(listOf(1, 2, 3, 4, 6, 8, 9).map { it to 0 }, pairs)
        // первая запись целиком: moduleStatus{1,0}
        assertArrayEquals(hex("08 07 12 04 08 01 10 00"), body.copyOf(8))
        // ГУ major 1: без 8 и 9
        assertEquals(5, PbReader.int(PbReader.parse(Msgs.moduleStatusList(1))!!, 1))
        val inner = Wire.cmdInner(Msg.MODULE_STATUS, body)
        assertEquals(0x2C, Wire.u16(inner, 0))
        assertEquals(0x00010026, Wire.u32(inner, 4))
    }

    @Test
    fun subscribeDoneMirrorsRequest() {
        // запрос из живого лога: cnt=1 {moduleID=0}
        assertArrayEquals(hex("08 01 12 04 08 00 10 01"), Msgs.subscribeDone(hex("08 01 12 02 08 00")))
        // 0 -> true, 1 -> false, 2 -> true, 3 -> false
        val req = PbWriter().int32(1, 4)
            .bytes(2, PbWriter().int32(1, 0).toByteArray())
            .bytes(2, PbWriter().int32(1, 1).toByteArray())
            .bytes(2, PbWriter().int32(1, 2).toByteArray())
            .bytes(2, PbWriter().int32(1, 3).toByteArray())
            .toByteArray()
        val done = Msgs.subscribeDone(req)!!
        val f = PbReader.parse(done)!!
        assertEquals(4, PbReader.int(f, 1))
        val flags = PbReader.all(f, 2).map { PbReader.parse(it.data!!)!! }.map { PbReader.int(it, 1) to PbReader.int(it, 2) }
        assertEquals(listOf(0 to 1, 1 to 0, 2 to 1, 3 to 0), flags)
        // на мусор не отвечаем
        assertNull(Msgs.subscribeDone(hex("12 05 01")))
        assertEquals(listOf(0), Msgs.subscribeIds(hex("08 01 12 02 08 00")))
        val inner = Wire.cmdInner(Msg.CARLIFE_DATA_SUBSCRIBE_DONE, done)
        assertEquals(0x00010044, Wire.u32(inner, 4))
    }

    @Test
    fun startBatchOrder() {
        val batch = Handshake.startBatch(true, mdInfo())
        assertEquals(
            listOf(Msg.PROTOCOL_VERSION_MATCH_STATUS, Msg.FOREGROUND, Msg.SCREEN_ON, Msg.MD_INFO),
            batch.map { it.type }
        )
        assertEquals(listOf(0x10002, 0x1001B, 0x10018, 0x10004), batch.map { it.type })
        assertArrayEquals(hex("08 01"), batch[0].body)
        assertNull(batch[1].body)
        assertNull(batch[2].body)
        assertTrue(batch[3].body!!.isNotEmpty())
        // экран выключен: SCREEN_ON не шлём
        assertEquals(
            listOf(Msg.PROTOCOL_VERSION_MATCH_STATUS, Msg.FOREGROUND, Msg.MD_INFO),
            Handshake.startBatch(false, mdInfo()).map { it.type }
        )
        // пустые тела: 8 байт, len=0, resv=0
        assertArrayEquals(hex("00 00 00 00 00 01 00 18"), Wire.cmdInner(batch[2].type, batch[2].body))
        // MODULE_STATUS потом, через 500 мс
        assertEquals(500L, Handshake.MODULE_STATUS_DELAY_MS)
        assertEquals(Msg.MODULE_STATUS, Handshake.moduleStatus(2).type)
    }

    @Test
    fun statisticInfoReplyOrder() {
        val r = Handshake.onStatisticInfo(true)
        assertEquals(listOf(0x1004B, 0x10051, 0x10031), r.map { it.type })
        assertArrayEquals(hex("08 01"), r[0].body)
        assertNull(r[1].body)
        assertNull(r[2].body)
        assertEquals(listOf(0x1004B, 0x10031), Handshake.onStatisticInfo(false).map { it.type })
    }

    @Test
    fun authV2Marker() {
        assertTrue(Handshake.isAuthV2("2.0;abcdef"))
        assertFalse(Handshake.isAuthV2("CarLife_1.0.0"))
        assertFalse(Handshake.isAuthV2(null))
    }

    @Test
    fun featureConfigParse() {
        fun item(k: String, v: Int) = PbWriter().string(1, k).int32(2, v).toByteArray()
        val body = PbWriter().int32(1, 3)
            .bytes(2, item("CONTENT_ENCRYPTION", 1))
            .bytes(2, item("CONNECT_TYPE", 0))
            .bytes(2, item("BLUETOOTH_AUTO_PAIR", 1))
            .bool(3, true).string(4, "HU-BT").string(5, "AA:BB")
            .toByteArray()
        val fc = FeatureConfig.parse(body)!!
        assertEquals(3, fc.items.size)
        assertEquals(1, fc.value("CONTENT_ENCRYPTION"))
        assertEquals(0, fc.value("CONNECT_TYPE"))
        assertNull(fc.value("NOPE"))
        assertEquals(true, fc.huBtAudioSupport)
        assertEquals("HU-BT", fc.huBtName)
        assertEquals("AA:BB", fc.huBtMac)
    }

    @Test
    fun themeJpegAndVideoKeepalive() {
        assertArrayEquals(hex("08 00"), Msgs.themeState(0))
        assertEquals("RESPONSE_MD_THEME_STATE", Msg.name(0x10080))
        assertEquals("REQUEST_MD_THEME_STATE", Msg.name(0x18079))
        // keepalive после START: VIDEO_DATA len=0, time = unix-секунды
        assertArrayEquals(
            hex("00 00 00 00  65 00 00 00  00 02 00 01"),
            Wire.dataInner(Msg.VIDEO_DATA, 0x65000000, null)
        )
        assertTrue(Handshake.frameRateChangeAllowed(30))
        assertTrue(Handshake.frameRateChangeAllowed(3))
        assertFalse(Handshake.frameRateChangeAllowed(2))
        assertFalse(Handshake.frameRateChangeAllowed(31))
    }

    @Test
    fun hexDump() {
        val d = Hex.dump(ByteArray(300) { it.toByte() }, 256)
        assertTrue(d.startsWith("0000  00 01 02 03 04 05 06 07  08 09 0A 0B 0C 0D 0E 0F  |"))
        assertTrue(d.contains("еще 44 байт"))
        assertEquals("00 01 02 ...(+3)", Hex.compact(ByteArray(6) { it.toByte() }, 3))
    }
}
