package com.yegorkapomidorka.headswap

/** Исходящее CMD-сообщение: тип и тело (null = пустое). */
class OutCmd(val type: Int, val body: ByteArray?)

/**
 * Последовательности MD -> HU для CarLife 2.0, повторяющие MD Baidu CarLife 8.6.7.
 * Чистая логика без android.*, чтобы проверять порядок и байты офлайн.
 */
object Handshake {
    /** MODULE_STATUS уходит через 500 мс после стартовой пачки (CarlifeConnectHsgHandler.boxDelayProtocolMatchStatus, :752-758). */
    const val MODULE_STATUS_DELAY_MS = 500L

    const val MD_VERSION = "8.6.7"

    /** carlifeType: 1 = приложение CarLife (MixConfig.isCarLifeApp, CarlifeDeviceInfoManager :109-113). */
    const val MD_TYPE_CARLIFE_APP = 1

    /**
     * Стартовая пачка сразу после ответа на HU_PROTOCOL_VERSION, без ожидания чего-либо от ГУ:
     * MATCH_STATUS, FOREGROUND, SCREEN_ON (если экран включён), MD_INFO
     * (CarlifeConnectHsgHandler.boxDelayProtocolMatchStatus :743-751 + sendPhoneStatusMsg :995-1021).
     */
    fun startBatch(screenOn: Boolean, info: DeviceInfo): List<OutCmd> {
        val out = ArrayList<OutCmd>()
        out.add(OutCmd(Msg.PROTOCOL_VERSION_MATCH_STATUS, Msgs.matchStatus(1)))
        out.add(OutCmd(Msg.FOREGROUND, null))
        if (screenOn) out.add(OutCmd(Msg.SCREEN_ON, null))
        out.add(OutCmd(Msg.MD_INFO, Msgs.deviceInfo(info)))
        return out
    }

    fun moduleStatus(huMajor: Int) = OutCmd(Msg.MODULE_STATUS, Msgs.moduleStatusList(huMajor))

    /**
     * Ответ на STATISTIC_INFO (CarlifeConnectHsgHandler :434-499): MD_AUTHEN_RESULT{true}, затем запрос
     * FEATURE_CONFIG (0x10051, пусто), затем CAR_DATA_SUBSCRIBE (0x10031, пусто).
     */
    fun onStatisticInfo(sendFeatureConfigRequest: Boolean): List<OutCmd> {
        val out = ArrayList<OutCmd>()
        out.add(OutCmd(Msg.MD_AUTHEN_RESULT, Msgs.authenResult(true)))
        if (sendFeatureConfigRequest) out.add(OutCmd(Msg.MD_FEATURE_CONFIG_REQUEST, null))
        out.add(OutCmd(Msg.CAR_DATA_SUBSCRIBE, null))
        return out
    }

    /** randomValue из HU_AUTHEN_REQUEST вида "2.0;<rnd>" (v2-вариант запроса). */
    fun isAuthV2(random: String?) = random != null && random.startsWith("2.0;")

    /** Допустимый ответ на VIDEO_ENCODER_FRAME_RATE_CHANGE: 3..30 (VideoMsgHandler.changeFrameRate). */
    fun frameRateChangeAllowed(fps: Int) = fps in 3..30
}
