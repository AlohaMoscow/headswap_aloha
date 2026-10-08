"""AAP message types, channel ids, builders for what a head unit sends, and decoders for the log.

Numbers and field values follow headunit-revived (aap/protocol/Channel.kt, messages/*.kt,
AapControl.kt); the protos in ../proto are its copies.
"""
from __future__ import annotations

import struct
from typing import Dict, Optional, Tuple

from google.protobuf import text_format
from google.protobuf.message import Message

from proto import common_pb2, control_pb2, input_pb2, media_pb2, sensors_pb2

# --- channels (headunit-revived Channel.kt; the HU chooses them in Service.id) -------------------
CH_CONTROL = 0
CH_SENSOR = 1
CH_VIDEO = 2
CH_INPUT = 3
CH_AUDIO_SPEECH = 4     # ID_AU1
CH_AUDIO_SYSTEM = 5     # ID_AU2
CH_AUDIO_MEDIA = 6      # ID_AUD
CH_MIC = 7
CH_BLUETOOTH = 8
CH_MEDIA_PLAYBACK = 9
CH_NAVIGATION = 10

CHANNEL_NAMES = {
    CH_CONTROL: "CONTROL", CH_SENSOR: "SENSOR", CH_VIDEO: "VIDEO", CH_INPUT: "INPUT",
    CH_AUDIO_SPEECH: "AUDIO_SPEECH", CH_AUDIO_SYSTEM: "AUDIO_SYSTEM", CH_AUDIO_MEDIA: "AUDIO_MEDIA",
    CH_MIC: "MIC", CH_BLUETOOTH: "BLUETOOTH", CH_MEDIA_PLAYBACK: "MEDIA_PLAYBACK",
    CH_NAVIGATION: "NAVIGATION",
}
AUDIO_CHANNELS = (CH_AUDIO_SPEECH, CH_AUDIO_SYSTEM, CH_AUDIO_MEDIA)


def channel_name(chan: int) -> str:
    return CHANNEL_NAMES.get(chan, f"CH{chan}")


# --- control message types (control.proto ControlMsgType) ----------------------------------------
VERSION_REQUEST = 1
VERSION_RESPONSE = 2
ENCAPSULATED_SSL = 3
AUTH_COMPLETE = 4
SERVICE_DISCOVERY_REQUEST = 5
SERVICE_DISCOVERY_RESPONSE = 6
CHANNEL_OPEN_REQUEST = 7
CHANNEL_OPEN_RESPONSE = 8
CHANNEL_CLOSE_NOTIFICATION = 9
PING_REQUEST = 11
PING_RESPONSE = 12
NAV_FOCUS_REQUEST = 13
NAV_FOCUS_NOTIFICATION = 14
BYEBYE_REQUEST = 15
BYEBYE_RESPONSE = 16
VOICE_SESSION_NOTIFICATION = 17
AUDIO_FOCUS_REQUEST = 18
AUDIO_FOCUS_NOTIFICATION = 19
UNEXPECTED_MESSAGE = 0x00FF
FRAMING_ERROR = 0xFFFF

CONTROL_NAMES = {
    1: "VERSION_REQUEST", 2: "VERSION_RESPONSE", 3: "ENCAPSULATED_SSL", 4: "AUTH_COMPLETE",
    5: "SERVICE_DISCOVERY_REQUEST", 6: "SERVICE_DISCOVERY_RESPONSE", 7: "CHANNEL_OPEN_REQUEST",
    8: "CHANNEL_OPEN_RESPONSE", 9: "CHANNEL_CLOSE_NOTIFICATION", 11: "PING_REQUEST",
    12: "PING_RESPONSE", 13: "NAV_FOCUS_REQUEST", 14: "NAV_FOCUS_NOTIFICATION",
    15: "BYEBYE_REQUEST", 16: "BYEBYE_RESPONSE", 17: "VOICE_SESSION_NOTIFICATION",
    18: "AUDIO_FOCUS_REQUEST", 19: "AUDIO_FOCUS_NOTIFICATION", 20: "CAR_CONNECTED_DEVICES_REQUEST",
    21: "CAR_CONNECTED_DEVICES_RESPONSE", 22: "USER_SWITCH_REQUEST", 23: "BATTERY_STATUS_NOTIFICATION",
    24: "CALL_AVAILABILITY_STATUS", 25: "USER_SWITCH_RESPONSE", 26: "SERVICE_DISCOVERY_UPDATE",
    0x00FF: "UNEXPECTED_MESSAGE", 0xFFFF: "FRAMING_ERROR",
}

# --- media (sink) types: video and audio channels ------------------------------------------------
MEDIA_DATA = 0x0000
MEDIA_CODEC_CONFIG = 0x0001
MEDIA_SETUP = 0x8000
MEDIA_START = 0x8001
MEDIA_STOP = 0x8002
MEDIA_CONFIG = 0x8003
MEDIA_ACK = 0x8004
MEDIA_MIC_REQUEST = 0x8005
MEDIA_MIC_RESPONSE = 0x8006
MEDIA_VIDEO_FOCUS_REQUEST = 0x8007
MEDIA_VIDEO_FOCUS_NOTIFICATION = 0x8008
MEDIA_UPDATE_UI_CONFIG_REQUEST = 0x8009
MEDIA_UPDATE_UI_CONFIG_REPLY = 0x800A
MEDIA_AUDIO_UNDERFLOW = 0x800B

MEDIA_NAMES = {
    0x0000: "MEDIA_DATA", 0x0001: "MEDIA_CODEC_CONFIG", 0x8000: "MEDIA_SETUP", 0x8001: "MEDIA_START",
    0x8002: "MEDIA_STOP", 0x8003: "MEDIA_CONFIG", 0x8004: "MEDIA_ACK",
    0x8005: "MIC_REQUEST", 0x8006: "MIC_RESPONSE", 0x8007: "VIDEO_FOCUS_REQUEST",
    0x8008: "VIDEO_FOCUS_NOTIFICATION", 0x8009: "UPDATE_UI_CONFIG_REQUEST",
    0x800A: "UPDATE_UI_CONFIG_REPLY", 0x800B: "AUDIO_UNDERFLOW",
}

# --- sensor / input (same 0x80xx numbers, meaning depends on the channel) ------------------------
SENSOR_START_REQUEST = 0x8001
SENSOR_START_RESPONSE = 0x8002
SENSOR_EVENT = 0x8003
SENSOR_NAMES = {0x8001: "SENSOR_START_REQUEST", 0x8002: "SENSOR_START_RESPONSE", 0x8003: "SENSOR_EVENT"}

INPUT_EVENT = 0x8001
INPUT_BINDING_REQUEST = 0x8002
INPUT_BINDING_RESPONSE = 0x8003
INPUT_NAMES = {0x8001: "INPUT_EVENT", 0x8002: "KEY_BINDING_REQUEST", 0x8003: "BINDING_RESPONSE"}


def message_name(chan: int, msg_type: int) -> str:
    """Name for the log. Types 1..26 and 0xFF/0xFFFF are control on any channel."""
    if msg_type in CONTROL_NAMES and (msg_type <= 26 or msg_type in (0x00FF, 0xFFFF)):
        # 0 and 1 are MEDIA_DATA / CODEC_CONFIG on media channels, so 1 needs the channel check.
        if not (msg_type == 1 and chan in (CH_VIDEO, CH_MIC) + AUDIO_CHANNELS):
            return CONTROL_NAMES[msg_type]
    if chan == CH_SENSOR and msg_type in SENSOR_NAMES:
        return SENSOR_NAMES[msg_type]
    if chan == CH_INPUT and msg_type in INPUT_NAMES:
        return INPUT_NAMES[msg_type]
    if msg_type in MEDIA_NAMES:
        return MEDIA_NAMES[msg_type]
    return f"UNKNOWN_0x{msg_type:04x}"


# --- raw (unencrypted) messages ------------------------------------------------------------------
VERSION_MAJOR = 1
VERSION_MINOR = 2


def version_request_body() -> bytes:
    """[type=1][major=1][minor=2]; framed as 00 03 00 06 00 01 00 01 00 02."""
    return struct.pack(">HHH", VERSION_REQUEST, VERSION_MAJOR, VERSION_MINOR)


AUTH_COMPLETE_DATA = b"\x08\x00"      # AuthResponse{status = 0 (OK)}; 00 03 00 04 00 04 08 00


def parse_version_response(body: bytes) -> Optional[Tuple[int, int, int, bytes]]:
    """Body after the type: major u16, minor u16, status i16, optional VersionResponseOptions."""
    if len(body) < 6:
        return None
    major, minor, status = struct.unpack_from(">HHh", body, 0)
    return major, minor, status, body[6:]


# --- helpers over protobuf -----------------------------------------------------------------------
def parse(proto_cls, data: bytes) -> Message:
    """Lenient parse: MergeFromString does not insist on `required` fields, ParseFromString does."""
    msg = proto_cls()
    msg.MergeFromString(data)
    return msg


def to_text(msg: Message) -> str:
    return text_format.MessageToString(msg, as_one_line=True)


# --- builders: what the head unit sends ----------------------------------------------------------
Out = Tuple[int, int, bytes]       # (channel, message type, protobuf bytes)


def channel_open_response(chan: int) -> Out:
    m = control_pb2.ChannelOpenResponse(status=common_pb2.STATUS_SUCCESS)
    return chan, CHANNEL_OPEN_RESPONSE, m.SerializeToString()


def ping_response(timestamp: int) -> Out:
    return CH_CONTROL, PING_RESPONSE, control_pb2.PingResponse(timestamp=timestamp).SerializeToString()


def nav_focus_notification(chan: int = CH_CONTROL) -> Out:
    m = control_pb2.NavFocusNotification(focus_type=control_pb2.NAV_FOCUS_PROJECTED)
    return chan, NAV_FOCUS_NOTIFICATION, m.SerializeToString()


_FOCUS_MAP = {
    control_pb2.AudioFocusRequestNotification.RELEASE: control_pb2.AudioFocusNotification.STATE_LOSS,
    control_pb2.AudioFocusRequestNotification.GAIN: control_pb2.AudioFocusNotification.STATE_GAIN,
    control_pb2.AudioFocusRequestNotification.GAIN_TRANSIENT:
        control_pb2.AudioFocusNotification.STATE_GAIN_TRANSIENT,
    control_pb2.AudioFocusRequestNotification.GAIN_TRANSIENT_MAY_DUCK:
        control_pb2.AudioFocusNotification.STATE_GAIN_TRANSIENT_GUIDANCE_ONLY,
}


def audio_focus_response(request: int, chan: int = CH_CONTROL) -> Optional[Out]:
    """Always-grant mapping of AapControl.kt:450-455. None for a request type it does not map."""
    state = _FOCUS_MAP.get(request)
    if state is None:
        return None
    m = control_pb2.AudioFocusNotification(focus_state=state)
    return chan, AUDIO_FOCUS_NOTIFICATION, m.SerializeToString()


def audio_focus_gain_unsolicited() -> Out:
    """Sent right after a Config on an audio channel (AapControl.kt:123-131)."""
    m = control_pb2.AudioFocusNotification(
        focus_state=control_pb2.AudioFocusNotification.STATE_GAIN, unsolicited=True)
    return CH_CONTROL, AUDIO_FOCUS_NOTIFICATION, m.SerializeToString()


def byebye_request(reason: int = control_pb2.USER_SELECTION) -> Out:
    return CH_CONTROL, BYEBYE_REQUEST, control_pb2.ByeByeRequest(reason=reason).SerializeToString()


def byebye_response() -> Out:
    return CH_CONTROL, BYEBYE_RESPONSE, control_pb2.ByeByeResponse().SerializeToString()


def media_config(chan: int, max_unacked: int) -> Out:
    m = media_pb2.Config(status=media_pb2.Config.STATUS_READY, max_unacked=max_unacked)
    m.configuration_indices.append(0)
    return chan, MEDIA_CONFIG, m.SerializeToString()


def video_focus_notification(projected: bool = True, unsolicited: bool = True) -> Out:
    mode = media_pb2.VIDEO_FOCUS_PROJECTED if projected else media_pb2.VIDEO_FOCUS_NATIVE
    m = media_pb2.VideoFocusNotification(mode=mode, unsolicited=unsolicited)
    return CH_VIDEO, MEDIA_VIDEO_FOCUS_NOTIFICATION, m.SerializeToString()


def media_ack(chan: int, session_id: int) -> Out:
    return chan, MEDIA_ACK, media_pb2.Ack(session_id=session_id, ack=1).SerializeToString()


def mic_response(success: bool, session_id: int = 0) -> Out:
    status = common_pb2.STATUS_SUCCESS if success else common_pb2.STATUS_INTERNAL_ERROR
    m = media_pb2.MicrophoneResponse(status=status, session_id=session_id)
    return CH_MIC, MEDIA_MIC_RESPONSE, m.SerializeToString()


def binding_response(chan: int = CH_INPUT) -> Out:
    m = input_pb2.BindingResponse(status=common_pb2.STATUS_SUCCESS)
    return chan, INPUT_BINDING_RESPONSE, m.SerializeToString()


def sensor_start_response(chan: int = CH_SENSOR) -> Out:
    m = sensors_pb2.SensorResponse(status=common_pb2.STATUS_SUCCESS)
    return chan, SENSOR_START_RESPONSE, m.SerializeToString()


def driving_status_event(status: int = 0) -> Out:
    """DrivingStatusEvent.kt: SensorBatch{driving_status=[{status}]}; 0 = UNRESTRICTED."""
    b = sensors_pb2.SensorBatch()
    b.driving_status.add(status=status)
    return CH_SENSOR, SENSOR_EVENT, b.SerializeToString()


def night_mode_event(night: bool = False) -> Out:
    """NightModeEvent.kt: SensorBatch{night_mode=[{is_night_mode}]}."""
    b = sensors_pb2.SensorBatch()
    b.night_mode.add(is_night_mode=night)
    return CH_SENSOR, SENSOR_EVENT, b.SerializeToString()


def touch_event(action: int, x: int, y: int, timestamp_ns: int, pointer_id: int = 0) -> Out:
    """TouchEvent.kt: InputReport{timestamp ns, touch_event{pointer, action_index 0, action}}."""
    t = input_pb2.TouchEvent(action_index=0, action=action)
    t.pointer_data.add(x=x, y=y, pointer_id=pointer_id)
    r = input_pb2.InputReport(timestamp=timestamp_ns, touch_event=t)
    return CH_INPUT, INPUT_EVENT, r.SerializeToString()


# --- ServiceDiscoveryResponse --------------------------------------------------------------------
# KeyCode.supported from headunit-revived (input/KeyCode.kt:9-61), as Android KeyEvent numbers.
KEYCODES_SUPPORTED = sorted(set([
    1, 2, 4, 19, 20, 21, 22, 23, 126, 127, 85, 87, 88, 84, 5, 209, 24, 25, 61, 62, 66, 3, 79, 86,
    6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18,
    81, 224, 264, 265, 267, 268, 269, 270, 271, 65536, 65537, 65538,
]))

VEHICLE_TYPE_CAR = 1
VEHICLE_TYPE_MOTORCYCLE = 3

_VCfg = control_pb2.Service.MediaSinkService.VideoConfiguration
_RESOLUTIONS = {
    "800x480": _VCfg._800x480, "1280x720": _VCfg._1280x720, "1920x1080": _VCfg._1920x1080,
}


def _audio_cfg(rate: int, channels: int) -> media_pb2.AudioConfiguration:
    return media_pb2.AudioConfiguration(sample_rate=rate, number_of_bits=16, number_of_channels=channels)


def build_service_discovery_response(
    *,
    resolution: str = "1280x720",
    fps: int = 30,
    density: int = 160,
    mic: bool = True,
    vehicle_type: Optional[int] = None,
    full_audio: bool = False,
    extra_services: bool = True,
    vehicle_id: str = "headlessunit-001",
) -> control_pb2.ServiceDiscoveryResponse:
    """The announcement of ServiceDiscoveryResponse.kt:28-338 with headunit-revived defaults.

    mic=True announces the MIC service and claims a car; mic=False omits it and claims a
    motorcycle (the only vehicle type for which the phone accepts a head unit without a mic,
    VehicleTypePolicy.kt). full_audio adds SPEECH and MEDIA sinks, which headunit-revived drops in
    self mode (AudioSinkAnnouncementPolicy). extra_services adds MEDIA_PLAYBACK and NAVIGATION,
    which it announces always.
    """
    if vehicle_type is None:
        vehicle_type = VEHICLE_TYPE_CAR if mic else VEHICLE_TYPE_MOTORCYCLE
    if vehicle_type == VEHICLE_TYPE_MOTORCYCLE:
        vehicle_id = vehicle_id + "-moto"
    width, height = (int(v) for v in resolution.split("x"))

    r = control_pb2.ServiceDiscoveryResponse()

    s = r.services.add(id=CH_SENSOR)
    s.sensor_source_service.sensors.add(type=sensors_pb2.DRIVING_STATUS)
    s.sensor_source_service.sensors.add(type=sensors_pb2.NIGHT)

    s = r.services.add(id=CH_VIDEO)
    sink = s.media_sink_service
    sink.available_type = media_pb2.MEDIA_CODEC_VIDEO_H264_BP
    sink.audio_type = media_pb2.NONE
    sink.available_while_in_call = True
    vc = sink.video_configs.add()
    vc.codec_resolution = _RESOLUTIONS[resolution]
    vc.frame_rate = _VCfg._30 if fps == 30 else _VCfg._60
    vc.density = density
    vc.pixel_aspect_ratio_e4 = 10000
    vc.margin_width = 0
    vc.margin_height = 0
    vc.video_codec_type = media_pb2.MEDIA_CODEC_VIDEO_H264_BP

    s = r.services.add(id=CH_INPUT)
    s.input_source_service.touchscreen.width = width
    s.input_source_service.touchscreen.height = height
    s.input_source_service.keycodes_supported.extend(KEYCODES_SUPPORTED)

    def audio_sink(chan: int, stream: int, rate: int, channels: int) -> None:
        a = r.services.add(id=chan).media_sink_service
        a.available_type = media_pb2.MEDIA_CODEC_AUDIO_PCM
        a.audio_type = stream
        a.audio_configs.append(_audio_cfg(rate, channels))

    audio_sink(CH_AUDIO_SYSTEM, media_pb2.SYSTEM, 16000, 1)
    if full_audio:
        audio_sink(CH_AUDIO_SPEECH, media_pb2.SPEECH, 16000, 1)
        audio_sink(CH_AUDIO_MEDIA, media_pb2.MEDIA, 48000, 2)

    if mic:
        m = r.services.add(id=CH_MIC).media_source_service
        m.type = media_pb2.MEDIA_CODEC_AUDIO_PCM
        m.audio_config.CopyFrom(_audio_cfg(16000, 1))

    if extra_services:
        r.services.add(id=CH_MEDIA_PLAYBACK).media_playback_service.SetInParent()
        nav = r.services.add(id=CH_NAVIGATION).navigation_status_service
        nav.minimum_interval_ms = 1000
        nav.type = control_pb2.Service.NavigationStatusService.ImageCodesOnly

    r.make = "Google"
    r.model = "Desktop Head Unit"
    r.year = "2025"
    r.vehicle_id = vehicle_id
    r.driver_position = control_pb2.DRIVER_POSITION_LEFT
    r.head_unit_make = "Google"
    r.head_unit_model = "Desktop Head Unit"
    r.head_unit_software_build = "1"
    r.head_unit_software_version = "0.1.0"
    r.can_play_native_media_during_vr = False
    r.hide_projected_clock = False
    r.session_configuration = 0
    r.display_name = "Open Headunit"
    hi = r.headunit_info
    hi.make = "Google"
    hi.model = "Desktop Head Unit"
    hi.year = "2025"
    hi.vehicle_id = vehicle_id
    hi.head_unit_make = "Google"
    hi.head_unit_model = "Desktop Head Unit"
    hi.head_unit_software_build = "1"
    hi.head_unit_software_version = "0.1.0"
    hi.vehicle_type = vehicle_type
    return r


def service_discovery_response(**kwargs) -> Out:
    return CH_CONTROL, SERVICE_DISCOVERY_RESPONSE, build_service_discovery_response(**kwargs).SerializeToString()


# --- decoders for the log ------------------------------------------------------------------------
# (channel predicate, type) -> proto class. Control types live on every channel.
_CONTROL_PROTOS: Dict[int, type] = {
    SERVICE_DISCOVERY_REQUEST: control_pb2.ServiceDiscoveryRequest,
    SERVICE_DISCOVERY_RESPONSE: control_pb2.ServiceDiscoveryResponse,
    CHANNEL_OPEN_REQUEST: control_pb2.ChannelOpenRequest,
    CHANNEL_OPEN_RESPONSE: control_pb2.ChannelOpenResponse,
    PING_REQUEST: control_pb2.PingRequest,
    PING_RESPONSE: control_pb2.PingResponse,
    NAV_FOCUS_REQUEST: control_pb2.NavFocusRequestNotification,
    NAV_FOCUS_NOTIFICATION: control_pb2.NavFocusNotification,
    BYEBYE_REQUEST: control_pb2.ByeByeRequest,
    BYEBYE_RESPONSE: control_pb2.ByeByeResponse,
    VOICE_SESSION_NOTIFICATION: control_pb2.VoiceSessionNotification,
    AUDIO_FOCUS_REQUEST: control_pb2.AudioFocusRequestNotification,
    AUDIO_FOCUS_NOTIFICATION: control_pb2.AudioFocusNotification,
    23: control_pb2.BatteryStatusNotification,
    24: control_pb2.CallAvailabilityStatus,
}
_MEDIA_PROTOS: Dict[int, type] = {
    MEDIA_SETUP: media_pb2.MediaSetupRequest,
    MEDIA_START: media_pb2.Start,
    MEDIA_CONFIG: media_pb2.Config,
    MEDIA_ACK: media_pb2.Ack,
    MEDIA_MIC_REQUEST: media_pb2.MicrophoneRequest,
    MEDIA_MIC_RESPONSE: media_pb2.MicrophoneResponse,
    MEDIA_VIDEO_FOCUS_REQUEST: media_pb2.VideoFocusRequestNotification,
    MEDIA_VIDEO_FOCUS_NOTIFICATION: media_pb2.VideoFocusNotification,
}


def proto_class_for(chan: int, msg_type: int) -> Optional[type]:
    if msg_type in _CONTROL_PROTOS and msg_type <= 26 and not (
            msg_type == 1 and chan in (CH_VIDEO, CH_MIC) + AUDIO_CHANNELS):
        return _CONTROL_PROTOS[msg_type]
    if chan == CH_SENSOR:
        return {SENSOR_START_REQUEST: sensors_pb2.SensorRequest,
                SENSOR_START_RESPONSE: sensors_pb2.SensorResponse,
                SENSOR_EVENT: sensors_pb2.SensorBatch}.get(msg_type)
    if chan == CH_INPUT:
        return {INPUT_EVENT: input_pb2.InputReport,
                INPUT_BINDING_REQUEST: input_pb2.KeyBindingRequest,
                INPUT_BINDING_RESPONSE: input_pb2.BindingResponse}.get(msg_type)
    return _MEDIA_PROTOS.get(msg_type)


def describe(chan: int, msg_type: int, payload: bytes, max_len: int = 600) -> str:
    """One-line decoded form of a message body, or a hex prefix when no proto is known."""
    cls = proto_class_for(chan, msg_type)
    if cls is not None:
        try:
            text = to_text(parse(cls, payload))
        except Exception as exc:                       # noqa: BLE001 - log helper must not raise
            text = f"<undecodable: {exc}> {payload[:32].hex(' ')}"
    else:
        text = payload[:32].hex(" ") + (" ..." if len(payload) > 32 else "")
    return text if len(text) <= max_len else text[:max_len] + "..."
