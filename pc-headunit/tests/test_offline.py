"""Offline tests for the PC head unit: no phone, no port 5277.

    python tests/test_offline.py        (plain runner, prints PASS/FAIL per test)
    python -m pytest tests -q            (same functions)

The end-to-end tests run the real Session against a scripted "phone" (an AAP server written here,
independently of aap/session.py) over socket.socketpair(): version exchange, TLS server side with
client-certificate request, service discovery, channel opens, sensors, input binding, video and
audio setup, fragmented video DATA, ping, focus requests, ByeBye in both directions.
"""
from __future__ import annotations

import re
import shutil
import socket
import subprocess
import ssl
import struct
import sys
import tempfile
import threading
import time
import traceback
from collections import deque
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from aap import framing as F                                   # noqa: E402
from aap import messages as M                                  # noqa: E402
from aap.log import SessionLog                                 # noqa: E402
from aap.session import Options, Session                       # noqa: E402
from aap.tls import CERT_SETS, SNI_NAME, TlsClient, make_context               # noqa: E402
from aap.tls import cert_paths as _cert_paths                                   # noqa: E402
from proto import common_pb2, control_pb2, input_pb2, media_pb2, sensors_pb2    # noqa: E402


_PLAIN_RUNNER = False


class _Skip(Exception):
    """Raised instead of pytest.skip when running through the plain runner."""


def cert_paths(base, which):
    """cert_paths() that skips the test when the AA certificates are not there (public clone)."""
    cert, key = _cert_paths(base, which)
    if not (cert.is_file() and key.is_file()):
        msg = "AA certs missing, see README"
        if _PLAIN_RUNNER:
            raise _Skip(msg)
        import pytest
        pytest.skip(msg)
    return cert, key


# =================================================================================================
# framing
# =================================================================================================
def test_version_request_bytes():
    frame = F.build_frame(0, F.FLAGS_PLAIN_BULK, M.version_request_body())
    assert frame == bytes.fromhex("00 03 00 06 00 01 00 01 00 02"), frame.hex(" ")


def test_auth_complete_bytes():
    frame = F.build_plain(0, M.AUTH_COMPLETE, M.AUTH_COMPLETE_DATA)
    assert frame == bytes.fromhex("00 03 00 04 00 04 08 00"), frame.hex(" ")


def test_flags_control_on_non_control_channel():
    assert F.message_flags(0, M.SERVICE_DISCOVERY_RESPONSE) == 0x0B
    assert F.message_flags(2, M.CHANNEL_OPEN_RESPONSE) == 0x0F      # control type 8 on ch2
    assert F.message_flags(2, M.MEDIA_CONFIG) == 0x0B               # 0x8003 is not a control type
    assert F.message_flags(1, M.SENSOR_EVENT) == 0x0B


def test_parser_bulk_and_extended_header_any_slicing():
    bulk = F.build_frame(2, 0x0B, b"abc")
    first = F.build_frame(2, 0x09, b"X" * 5, total=12)
    last = F.build_frame(2, 0x0A, b"Y" * 7)
    assert first[:8] == bytes([2, 0x09, 0, 5]) + struct.pack(">I", 12)      # 4-byte total size
    stream = bulk + first + last
    for step in (1, 2, 3, 7, len(stream)):
        p = F.FrameParser()
        frames = []
        for i in range(0, len(stream), step):
            frames += p.feed(stream[i:i + step])
        assert [(f.chan, f.flags, f.body, f.total) for f in frames] == [
            (2, 0x0B, b"abc", None), (2, 0x09, b"X" * 5, 12), (2, 0x0A, b"Y" * 7, None)], step
        assert p.pending == 0


def test_split_message_fragment_flags_and_reassembly():
    plaintext = struct.pack(">H", M.MEDIA_DATA) + bytes(range(256)) * 200      # 51202 B
    parts = list(F.split_message(plaintext, M.CH_VIDEO, M.MEDIA_DATA, max_fragment=0x4000))
    assert [p[0] for p in parts] == [0x09, 0x08, 0x08, 0x0A], [hex(p[0]) for p in parts]
    assert parts[0][2] == len(plaintext) and all(p[2] is None for p in parts[1:])
    assert b"".join(p[1] for p in parts) == plaintext
    warnings = []
    r = F.Reassembler(warn=warnings.append)
    out = None
    for flags, chunk, total in parts:
        out = r.push(M.CH_VIDEO, flags, chunk, total)
    assert out == plaintext and not warnings and r.open_runs() == []
    one = list(F.split_message(b"\x00\x00hi", 2, 0))
    assert one == [(0x0B, b"\x00\x00hi", None)]


def test_reassembler_interleaved_channels_and_anomalies():
    warnings = []
    r = F.Reassembler(warn=warnings.append)
    assert r.push(2, 0x09, b"aa", 6) is None
    assert r.push(5, 0x0B, b"bulk") == b"bulk"                 # other channel is not disturbed
    assert r.push(2, 0x08, b"bb") is None
    assert r.push(2, 0x0A, b"cc") == b"aabbcc" and not warnings
    assert r.push(2, 0x0A, b"zz") is None and "without FIRST" in warnings[-1]
    assert r.push(2, 0x09, b"aa", 4) is None
    assert r.push(2, 0x09, b"dd", 4) is None and "old run dropped" in warnings[-1]
    assert r.push(2, 0x0A, b"ee", 4) == b"ddee"
    assert r.push(2, 0x09, b"a", 99) is None
    assert r.push(2, 0x0A, b"b") == b"ab" and "declared 99" in warnings[-1]


# =================================================================================================
# messages
# =================================================================================================
def _service_by_id(resp, sid):
    return next(s for s in resp.services if s.id == sid)


def test_service_discovery_response_serialisation():
    out = M.service_discovery_response(fps=30, density=160)
    chan, typ, raw = out
    assert (chan, typ) == (0, 6)
    resp = control_pb2.ServiceDiscoveryResponse()
    resp.ParseFromString(raw)                                    # strict: required fields all set
    assert sorted(s.id for s in resp.services) == [1, 2, 3, 5, 7, 9, 10]

    sensors = [s.type for s in _service_by_id(resp, 1).sensor_source_service.sensors]
    assert sensors == [sensors_pb2.DRIVING_STATUS, sensors_pb2.NIGHT]

    video = _service_by_id(resp, 2).media_sink_service
    vc = video.video_configs[0]
    assert video.available_type == media_pb2.MEDIA_CODEC_VIDEO_H264_BP
    assert vc.codec_resolution == control_pb2.Service.MediaSinkService.VideoConfiguration._1280x720
    assert vc.frame_rate == control_pb2.Service.MediaSinkService.VideoConfiguration._30
    assert (vc.margin_width, vc.margin_height, vc.density, vc.pixel_aspect_ratio_e4) == (0, 0, 160, 10000)
    # hand-encoded VideoConfiguration: res=2 fps=2 margins 0 0 density 160 par 10000 codec 3
    assert bytes.fromhex("08 02 10 02 18 00 20 00 28 a0 01 40 90 4e 50 03") in raw

    touch = _service_by_id(resp, 3).input_source_service
    assert (touch.touchscreen.width, touch.touchscreen.height) == (1280, 720)
    assert list(touch.keycodes_supported) == sorted(set(touch.keycodes_supported))

    au = _service_by_id(resp, 5).media_sink_service
    assert au.audio_type == media_pb2.SYSTEM and au.available_type == media_pb2.MEDIA_CODEC_AUDIO_PCM
    cfg = au.audio_configs[0]
    assert (cfg.sample_rate, cfg.number_of_bits, cfg.number_of_channels) == (16000, 16, 1)

    mic = _service_by_id(resp, 7).media_source_service
    assert mic.audio_config.sample_rate == 16000
    assert resp.headunit_info.vehicle_type == 1 and resp.vehicle_id == "headlessunit-001"
    assert resp.make == "Google" and resp.model == "Desktop Head Unit"


def test_service_discovery_variants():
    moto = control_pb2.ServiceDiscoveryResponse()
    moto.ParseFromString(M.service_discovery_response(mic=False, extra_services=False)[2])
    assert sorted(s.id for s in moto.services) == [1, 2, 3, 5]
    assert moto.headunit_info.vehicle_type == 3 and moto.vehicle_id.endswith("-moto")
    assert moto.headunit_info.vehicle_id == moto.vehicle_id
    full = control_pb2.ServiceDiscoveryResponse()
    full.ParseFromString(M.service_discovery_response(full_audio=True)[2])
    assert {4, 5, 6} <= {s.id for s in full.services}
    media = _service_by_id(full, 6).media_sink_service
    assert media.audio_type == media_pb2.MEDIA
    assert (media.audio_configs[0].sample_rate, media.audio_configs[0].number_of_channels) == (48000, 2)


def test_builders_wire_bytes():
    assert M.channel_open_response(2) == (2, 8, bytes.fromhex("08 00"))
    assert M.media_ack(2, 7) == (2, 0x8004, bytes.fromhex("08 07 10 01"))
    assert M.media_config(2, 12) == (2, 0x8003, bytes.fromhex("08 02 10 0c 18 00"))
    assert M.video_focus_notification(True, True) == (2, 0x8008, bytes.fromhex("08 01 10 01"))
    assert M.driving_status_event(0) == (1, 0x8003, bytes.fromhex("6a 02 08 00"))   # field 13
    assert M.night_mode_event(False) == (1, 0x8003, bytes.fromhex("52 02 08 00"))   # field 10
    assert M.ping_response(5) == (0, 12, bytes.fromhex("08 05"))
    assert M.byebye_request() == (0, 15, bytes.fromhex("08 01"))
    chan, typ, raw = M.touch_event(input_pb2.TouchEvent.TOUCH_ACTION_DOWN, 640, 360, 123)
    rep = input_pb2.InputReport()
    rep.ParseFromString(raw)
    assert (chan, typ) == (3, 0x8001) and rep.timestamp == 123
    assert [(p.x, p.y, p.pointer_id) for p in rep.touch_event.pointer_data] == [(640, 360, 0)]
    assert rep.touch_event.action == 0 and rep.touch_event.action_index == 0


def test_audio_focus_mapping():
    A = control_pb2.AudioFocusRequestNotification
    N = control_pb2.AudioFocusNotification
    for req, state in ((A.GAIN, N.STATE_GAIN), (A.GAIN_TRANSIENT, N.STATE_GAIN_TRANSIENT),
                       (A.GAIN_TRANSIENT_MAY_DUCK, N.STATE_GAIN_TRANSIENT_GUIDANCE_ONLY),
                       (A.RELEASE, N.STATE_LOSS)):
        _, typ, raw = M.audio_focus_response(req)
        n = N()
        n.ParseFromString(raw)
        assert typ == 19 and n.focus_state == state and not n.HasField("unsolicited")
    assert M.audio_focus_response(A.NONE) is None


def test_version_response_parse():
    assert M.parse_version_response(struct.pack(">HHh", 1, 7, 0)) == (1, 7, 0, b"")
    assert M.parse_version_response(struct.pack(">HHH", 1, 7, 0xFFFF) + b"\x0a\x00")[2:] == (-1, b"\x0a\x00")
    assert M.parse_version_response(b"\x00") is None


# =================================================================================================
# TLS
# =================================================================================================
def test_certificates_load_into_sslcontext():
    for which in CERT_SETS:
        cert, key = cert_paths(ROOT, which)
        assert cert.is_file() and key.is_file()
        for tls in ("auto", "1.2"):
            ctx = make_context(cert, key, tls)
            assert ctx.verify_mode == ssl.CERT_NONE and ctx.check_hostname is False
            assert ctx.get_ca_certs() == []
        # the key really belongs to the certificate: load_cert_chain would have raised otherwise


def test_client_hello_generation_without_network():
    for which in CERT_SETS:
        for tls in ("auto", "1.2"):
            cert, key = cert_paths(ROOT, which)
            hello = TlsClient(make_context(cert, key, tls)).handshake_step()
            assert hello[0] == 0x16 and hello[1] == 0x03, hello[:5].hex()      # TLS handshake record
            assert hello[5] == 0x01                                              # ClientHello
            record_len = struct.unpack_from(">H", hello, 3)[0]
            assert len(hello) == 5 + record_len
            assert SNI_NAME.encode() in hello
            if tls == "auto":
                assert b"\x00\x2b" in hello        # supported_versions extension: TLS 1.3 is offered


def _can_verify(client_set):
    """OpenSSL 1.1.1 (the one bundled with Python 3.11.0) cannot verify the JVC certificate: its
    validity dates are UTCTime with a zone offset ("140704000000-0700"), which it reports as
    "format error in certificate's notBefore field". The Android side evidently accepts it (aasdk
    ships it). So the scripted phone only requests and checks the client certificate for hur."""
    return client_set != "jvc"


def _server_ctx(client_set, request_cert=None):
    if request_cert is None:
        request_cert = _can_verify(client_set)
    server_set = "hur" if client_set == "jvc" else "jvc"
    cert, key = cert_paths(ROOT, server_set)
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.set_ciphers("DEFAULT:@SECLEVEL=0")
    ctx.load_cert_chain(cert, key)
    if request_cert:
        ctx.verify_mode = ssl.CERT_OPTIONAL
        ctx.load_verify_locations(cafile=str(cert_paths(ROOT, client_set)[0]))
        ctx.verify_flags |= ssl.VERIFY_X509_PARTIAL_CHAIN
    return ctx


def _in_memory_handshake(which, tls, request_cert):
    cert, key = cert_paths(ROOT, which)
    client = TlsClient(make_context(cert, key, tls))
    inc, out = ssl.MemoryBIO(), ssl.MemoryBIO()
    server = _server_ctx(which, request_cert).wrap_bio(inc, out, server_side=True)
    data = client.handshake_step()
    for _ in range(6):
        inc.write(data)
        try:
            server.do_handshake()
        except ssl.SSLWantReadError:
            pass
        data = client.handshake_step(out.read())
        if client.handshake_done and not data:
            break
    return cert, client, inc, server


def test_tls_handshake_in_memory_client_certificate_reaches_server():
    for tls in ("auto", "1.2"):
        cert, client, inc, server = _in_memory_handshake("hur", tls, True)
        assert client.handshake_done, tls
        assert server.getpeercert(binary_form=True) == ssl.PEM_cert_to_DER_cert(cert.read_text()), tls
        inc.write(client.encrypt(b"\x00\x0b\x08\x05"))
        assert server.read(100) == b"\x00\x0b\x08\x05"


def test_tls_handshake_in_memory_jvc_certificate_is_sent():
    for tls in ("auto", "1.2"):
        # server that does not ask for a certificate: the handshake itself works with this context
        _, client, inc, server = _in_memory_handshake("jvc", tls, False)
        assert client.handshake_done, tls
        inc.write(client.encrypt(b"\x00\x0b\x08\x05"))
        assert server.read(100) == b"\x00\x0b\x08\x05"
    # server that asks: it either accepts the certificate, or fails while parsing exactly that
    # certificate, which proves the client sent it
    try:
        _, client, _, server = _in_memory_handshake("jvc", "1.2", True)
        assert server.getpeercert(binary_form=True)
    except ssl.SSLCertVerificationError as exc:
        assert "notBefore" in str(exc) or "certificate" in str(exc), exc


# =================================================================================================
# end to end against a scripted phone
# =================================================================================================
class FakePhone(threading.Thread):
    """AAP server side, written separately from aap/session.py."""

    def __init__(self, sock, client_set, tickets, end, touch, sample=None):
        super().__init__(daemon=True)
        self.sock, self.tickets, self.end, self.touch = sock, tickets, end, touch
        self.sample = sample                  # real Annex-B stream to push instead of the synthetic one
        self.sock.settimeout(8)
        self.parser = F.FrameParser()
        self.queue = deque()
        self.inc, self.outb = ssl.MemoryBIO(), ssl.MemoryBIO()
        self.tls = _server_ctx(client_set).wrap_bio(self.inc, self.outb, server_side=True)
        self.error = None
        self.rx_flags = {}
        self.saw_eof = False
        self.reset = False
        self.touches = []
        self.video_expected = b""
        self.peer_cert = None

    # -- plumbing ----------------------------------------------------------------------------------
    def recv_frame(self):
        while not self.queue:
            data = self.sock.recv(65536)
            if not data:
                raise EOFError("HU closed the connection")
            self.queue.extend(self.parser.feed(data))
        return self.queue.popleft()

    def _note_touch(self, payload):
        rep = M.parse(input_pb2.InputReport, payload)
        te = rep.touch_event
        self.touches.append((te.action, te.pointer_data[0].x, te.pointer_data[0].y, rep.timestamp))

    def recv_msg(self):
        """Next message that is not a touch event (those arrive on the HU's own timer)."""
        while True:
            c, t, p = self._read_one()
            if (c, t) == (3, M.INPUT_EVENT):
                self._note_touch(p)
                continue
            return c, t, p

    def wait_touches(self, n):
        while len(self.touches) < n:
            c, t, p = self._read_one()
            assert (c, t) == (3, M.INPUT_EVENT), f"unexpected ch{c} 0x{t:04x} while waiting for the tap"
            self._note_touch(p)

    def _read_one(self):
        while True:
            f = self.recv_frame()
            assert f.encrypted, f"plain frame after handshake: {f}"
            self.inc.write(f.body)
            data = b""
            while True:
                try:
                    chunk = self.tls.read(65536)
                except ssl.SSLWantReadError:
                    break
                data += chunk
            if not data:
                continue
            assert f.flags & 3 == 3, f"HU sent a fragmented message: 0x{f.flags:02x}"
            typ = struct.unpack_from(">H", data)[0]
            self.rx_flags[(f.chan, typ)] = f.flags
            return f.chan, typ, data[2:]

    def expect(self, chan, typ, cls=None):
        c, t, payload = self.recv_msg()
        assert (c, t) == (chan, typ), f"expected ch{chan} 0x{typ:04x}, got ch{c} 0x{t:04x}"
        return M.parse(cls, payload) if cls else payload

    def send_plain(self, typ, data):
        self.sock.sendall(F.build_plain(0, typ, data))

    def send_msg(self, chan, typ, proto_or_bytes, fragment=None):
        payload = proto_or_bytes if isinstance(proto_or_bytes, bytes) else proto_or_bytes.SerializeToString()
        plaintext = struct.pack(">H", typ) + payload
        flags = 0x0F if chan != 0 and 1 <= typ <= 26 else 0x0B
        if fragment is None or len(plaintext) <= fragment:
            self.tls.write(plaintext)
            self.sock.sendall(F.build_frame(chan, flags, self.outb.read()))
            return
        # independent manual fragmentation: FIRST 0x09 + 4-byte total, MIDDLE 0x08, LAST 0x0a
        chunks = [plaintext[i:i + fragment] for i in range(0, len(plaintext), fragment)]
        for i, chunk in enumerate(chunks):
            self.tls.write(chunk)
            rec = self.outb.read()
            if i == 0:
                self.sock.sendall(bytes([chan, 0x09]) + struct.pack(">HI", len(rec), len(plaintext)) + rec)
            else:
                fl = 0x0A if i == len(chunks) - 1 else 0x08
                self.sock.sendall(bytes([chan, fl]) + struct.pack(">H", len(rec)) + rec)

    # -- script ------------------------------------------------------------------------------------
    def run(self):
        try:
            self.script()
        except Exception:                                       # noqa: BLE001
            self.error = traceback.format_exc()
        finally:
            try:
                self.sock.shutdown(socket.SHUT_WR)
            except OSError:
                pass
            try:
                while True:                  # an RST from the HU side would raise here
                    if not self.sock.recv(65536):
                        self.saw_eof = True
                        break
            except ConnectionResetError:
                self.reset = True
            except OSError:
                pass
            self.sock.close()

    def script(self):
        # version
        f = self.recv_frame()
        assert (f.chan, f.flags, f.body) == (0, 0x03, bytes.fromhex("0001 0001 0002")), f
        self.send_plain(M.VERSION_RESPONSE, struct.pack(">HHh", 1, 7, 0))
        # TLS inside plain frames
        while True:
            f = self.recv_frame()
            assert f.chan == 0 and f.flags == 0x03 and f.body[:2] == b"\x00\x03", f
            self.inc.write(f.body[2:])
            try:
                self.tls.do_handshake()
                done = True
            except ssl.SSLWantReadError:
                done = False
            out = self.outb.read()
            if done:
                break
            self.send_plain(M.ENCAPSULATED_SSL, out)
        self.peer_cert = self.tls.getpeercert(binary_form=True)
        # What the TLS layer still wants to send. TLS 1.2: the server's final flight, which the HU
        # needs to finish its handshake, so plain. TLS 1.3: session tickets, after the HU is
        # done; unknown how the real phone frames them, so both ways are exercised.
        if out:
            if self.tls.version() == "TLSv1.3" and self.tickets == "enc":
                self.sock.sendall(F.build_frame(0, 0x0B, out))
            else:
                self.send_plain(M.ENCAPSULATED_SSL, out)
        f = self.recv_frame()
        assert (f.chan, f.flags, f.body) == (0, 0x03, bytes.fromhex("0004 0800")), f

        # service discovery
        self.send_msg(0, M.SERVICE_DISCOVERY_REQUEST,
                      control_pb2.ServiceDiscoveryRequest(phone_name="Pixel", phone_brand="Google"))
        sd = self.expect(0, M.SERVICE_DISCOVERY_RESPONSE, control_pb2.ServiceDiscoveryResponse)
        assert {s.id for s in sd.services} == {1, 2, 3, 5, 7, 9, 10}
        assert self.rx_flags[(0, 6)] == 0x0B

        # channel opens
        for chan in (1, 2, 3, 5, 7, 9, 10):
            self.send_msg(chan, M.CHANNEL_OPEN_REQUEST, control_pb2.ChannelOpenRequest(priority=0, service_id=chan))
            r = self.expect(chan, M.CHANNEL_OPEN_RESPONSE, control_pb2.ChannelOpenResponse)
            assert r.status == common_pb2.STATUS_SUCCESS
            assert self.rx_flags[(chan, 8)] == (0x0F if chan else 0x0B)
            if chan == 1:
                ds = self.expect(1, M.SENSOR_EVENT, sensors_pb2.SensorBatch)
                assert [d.status for d in ds.driving_status] == [0]

        # sensors
        self.send_msg(1, M.SENSOR_START_REQUEST, sensors_pb2.SensorRequest(type=sensors_pb2.DRIVING_STATUS, min_update_period=0))
        assert self.expect(1, M.SENSOR_START_RESPONSE, sensors_pb2.SensorResponse).status == 0
        self.send_msg(1, M.SENSOR_START_REQUEST, sensors_pb2.SensorRequest(type=sensors_pb2.NIGHT, min_update_period=0))
        assert self.expect(1, M.SENSOR_START_RESPONSE, sensors_pb2.SensorResponse).status == 0
        ev = self.expect(1, M.SENSOR_EVENT, sensors_pb2.SensorBatch)
        assert [n.is_night_mode for n in ev.night_mode] == [False]

        # input
        self.send_msg(3, M.INPUT_BINDING_REQUEST, input_pb2.KeyBindingRequest(keycodes=[3, 4, 84]))
        assert self.expect(3, M.INPUT_BINDING_RESPONSE, input_pb2.BindingResponse).status == 0

        # video
        self.send_msg(2, M.MEDIA_SETUP, media_pb2.MediaSetupRequest(type=3))
        cfg = self.expect(2, M.MEDIA_CONFIG, media_pb2.Config)
        assert (cfg.status, cfg.max_unacked, list(cfg.configuration_indices)) == (media_pb2.Config.STATUS_READY, 12, [0])
        vf = self.expect(2, M.MEDIA_VIDEO_FOCUS_NOTIFICATION, media_pb2.VideoFocusNotification)
        assert vf.mode == media_pb2.VIDEO_FOCUS_PROJECTED and vf.unsolicited
        self.send_msg(2, M.MEDIA_START, media_pb2.Start(session_id=7, configuration_index=0))
        if self.sample:
            sps_pps, rest = split_sps_pps(self.sample)
            units = [rest[i:i + 20000] for i in range(0, len(rest), 20000)]
        else:
            sps_pps = b"\x00\x00\x00\x01\x67\x42\xc0\x1f\xda\x01\x40\x16\xe8\x40\x00\x00\x00\x01\x68\xce\x3c\x80"
            idr = b"\x00\x00\x00\x01\x65" + bytes((i * 7) % 251 for i in range(40000))      # 3 fragments
            small = b"\x00\x00\x00\x01\x41" + b"\x11" * 300
            units = [idr, small]
        self.send_msg(2, M.MEDIA_CODEC_CONFIG, sps_pps)
        ack = self.expect(2, M.MEDIA_ACK, media_pb2.Ack)
        assert (ack.session_id, ack.ack) == (7, 1)
        for n, unit in enumerate(units):
            self.send_msg(2, M.MEDIA_DATA, struct.pack(">Q", 1_000_000 + n * 33_000) + unit, fragment=16384)
            ack = self.expect(2, M.MEDIA_ACK, media_pb2.Ack)
            assert (ack.session_id, ack.ack) == (7, 1)
        self.video_expected = sps_pps + b"".join(units)
        self.video_units = len(units)
        self.send_msg(2, M.MEDIA_VIDEO_FOCUS_REQUEST,
                      media_pb2.VideoFocusRequestNotification(mode=media_pb2.VIDEO_FOCUS_PROJECTED))
        vf = self.expect(2, M.MEDIA_VIDEO_FOCUS_NOTIFICATION, media_pb2.VideoFocusNotification)
        assert vf.mode == media_pb2.VIDEO_FOCUS_PROJECTED and not vf.unsolicited

        # audio system channel
        self.send_msg(5, M.MEDIA_SETUP, media_pb2.MediaSetupRequest(type=1))
        cfg = self.expect(5, M.MEDIA_CONFIG, media_pb2.Config)
        assert cfg.max_unacked == 30
        af = self.expect(0, M.AUDIO_FOCUS_NOTIFICATION, control_pb2.AudioFocusNotification)
        assert af.focus_state == control_pb2.AudioFocusNotification.STATE_GAIN and af.unsolicited
        self.send_msg(5, M.MEDIA_START, media_pb2.Start(session_id=3, configuration_index=0))
        self.send_msg(5, M.MEDIA_DATA, struct.pack(">Q", 5) + b"\x01\x02" * 320)
        ack = self.expect(5, M.MEDIA_ACK, media_pb2.Ack)
        assert (ack.session_id, ack.ack) == (3, 1)

        # mic: announced, but this HU cannot capture, so an open request is refused
        self.send_msg(7, M.MEDIA_MIC_REQUEST, media_pb2.MicrophoneRequest(open=True))
        assert self.expect(7, M.MEDIA_MIC_RESPONSE, media_pb2.MicrophoneResponse).status != 0

        # control channel
        self.send_msg(0, M.PING_REQUEST, control_pb2.PingRequest(timestamp=123456789))
        assert self.expect(0, M.PING_RESPONSE, control_pb2.PingResponse).timestamp == 123456789
        self.send_msg(0, M.NAV_FOCUS_REQUEST, control_pb2.NavFocusRequestNotification(focus_type=control_pb2.NAV_FOCUS_PROJECTED))
        assert self.expect(0, M.NAV_FOCUS_NOTIFICATION, control_pb2.NavFocusNotification).focus_type == control_pb2.NAV_FOCUS_PROJECTED
        self.send_msg(0, M.AUDIO_FOCUS_REQUEST, control_pb2.AudioFocusRequestNotification(
            request=control_pb2.AudioFocusRequestNotification.GAIN_TRANSIENT_MAY_DUCK))
        af = self.expect(0, M.AUDIO_FOCUS_NOTIFICATION, control_pb2.AudioFocusNotification)
        assert af.focus_state == control_pb2.AudioFocusNotification.STATE_GAIN_TRANSIENT_GUIDANCE_ONLY
        self.send_msg(0, M.VOICE_SESSION_NOTIFICATION,
                      control_pb2.VoiceSessionNotification(status=control_pb2.VoiceSessionNotification.VOICE_STATUS_START))

        if self.touch:
            self.wait_touches(2)
            assert [t[0] for t in self.touches] == [input_pb2.TouchEvent.TOUCH_ACTION_DOWN,
                                                    input_pb2.TouchEvent.TOUCH_ACTION_UP]
            assert [t[1:3] for t in self.touches] == [(640, 360), (640, 360)]
            assert self.touches[1][3] > self.touches[0][3]

        if self.end == "phone_bye":
            self.send_msg(0, M.BYEBYE_REQUEST, control_pb2.ByeByeRequest(reason=control_pb2.USER_SELECTION))
            c, t, _ = self.recv_msg()
            assert (c, t) == (0, M.BYEBYE_RESPONSE), (c, t)
        else:
            c, t, p = self.recv_msg()                      # HU-initiated end: --duration elapsed
            assert (c, t) == (0, M.BYEBYE_REQUEST), (c, t)
            assert M.parse(control_pb2.ByeByeRequest, p).reason == control_pb2.USER_SELECTION
            self.send_msg(0, M.BYEBYE_RESPONSE, control_pb2.ByeByeResponse())


_START_CODE = re.compile(b"\x00\x00\x01")


def split_sps_pps(stream):
    """(everything before the third NAL, the rest) of an Annex-B stream that opens with SPS, PPS."""
    starts = [m.start() for m in _START_CODE.finditer(stream)][:3]
    cut = starts[2] - 1 if stream[starts[2] - 1] == 0 else starts[2]
    return stream[:cut], stream[cut:]


def run_scenario(tls, client_set, tickets, end, touch, sample=None, check=None):
    out_dir = Path(tempfile.mkdtemp(prefix="hu_test_"))
    try:
        hu_sock, phone_sock = socket.socketpair()
        phone = FakePhone(phone_sock, client_set, tickets, end, touch, sample)
        cert, key = cert_paths(ROOT, client_set)
        opts = Options(duration=2.5 if end == "duration" else None, touch_test=touch, touch_delay_s=0.3,
                       idle_timeout_s=8.0, sd=dict(mic=True, extra_services=True))
        log = SessionLog(out_dir / "session.log", console=False)
        session = Session(hu_sock, TlsClient(make_context(cert, key, tls)), opts, log, out_dir)
        phone.start()
        t0 = time.monotonic()
        session.run()
        elapsed = time.monotonic() - t0
        log.close()
        phone.join(10)
        assert not phone.is_alive(), "phone script hung"
        assert phone.error is None, phone.error
        assert session.exit_ok, f"session ended abnormally: {session.stop_reason}"
        assert phone.saw_eof and not phone.reset, "HU did not close the socket in an orderly way"
        if _can_verify(client_set):
            assert phone.peer_cert == ssl.PEM_cert_to_DER_cert(cert.read_text()), "client certificate missing"
        video = (out_dir / "video.h264").read_bytes()
        assert video == phone.video_expected, (len(video), len(phone.video_expected))
        assert (out_dir / "audio_5.pcm").read_bytes() == b"\x01\x02" * 320
        assert session.stats.video_data == phone.video_units and session.stats.video_codec_config == 1
        if check:
            check(out_dir)
        text = (out_dir / "session.log").read_text(encoding="utf-8")
        for needle in ("TX CONTROL(0) SERVICE_DISCOVERY_RESPONSE", "RX VIDEO(2) MEDIA_DATA",
                       "TX VIDEO(2) MEDIA_ACK", "RX CONTROL(0) PING_REQUEST", "summary:"):
            assert needle in text, needle
        if end == "duration":
            assert elapsed >= 2.4, elapsed
            assert "--duration" in (session.stop_reason or "")
        if touch:
            assert len(phone.touches) == 2
        return session, phone
    finally:
        shutil.rmtree(out_dir, ignore_errors=True)


def test_e2e_tls13_jvc_phone_ends_session():
    session, phone = run_scenario("auto", "jvc", "plain", "phone_bye", False)
    assert "TLSv1.3" in session.tls.describe(), session.tls.describe()


def test_e2e_tls13_hur_hu_ends_session_with_touch_test():
    run_scenario("auto", "hur", "enc", "duration", True)


def test_e2e_tls12_jvc_hu_ends_session():
    session, _ = run_scenario("1.2", "jvc", "plain", "duration", False)
    assert "TLSv1.2" in session.tls.describe()


def test_e2e_tls12_hur_phone_ends_session():
    run_scenario("1.2", "hur", "enc", "phone_bye", False)


def _probe(ffprobe, path):
    r = subprocess.run([ffprobe, "-v", "error", "-count_frames", "-select_streams", "v:0",
                        "-show_entries", "stream=codec_name,profile,width,height,nb_read_frames",
                        "-of", "csv=p=0", str(path)], capture_output=True, text=True, timeout=60)
    assert r.returncode == 0 and not r.stderr.strip(), (r.returncode, r.stderr)
    return r.stdout.strip()


def test_captured_stream_is_valid_h264_per_ffprobe():
    """Real 1280x720 baseline H.264 from ffmpeg goes through the whole Session path, fragmented
    into DATA messages; the saved video.h264 must be byte-identical and decodable. Skipped when
    ffmpeg/ffprobe are not in PATH (they are not needed by the head unit itself)."""
    ffmpeg, ffprobe = shutil.which("ffmpeg"), shutil.which("ffprobe")
    if not (ffmpeg and ffprobe):
        print("skipped: no ffmpeg/ffprobe in PATH", end=" ")
        return
    tmp = Path(tempfile.mkdtemp(prefix="hu_sample_"))
    try:
        sample = tmp / "sample.h264"
        r = subprocess.run([ffmpeg, "-v", "error", "-f", "lavfi", "-i", "testsrc=size=1280x720:rate=30",
                            "-t", "1", "-c:v", "libx264", "-profile:v", "baseline", "-pix_fmt", "yuv420p",
                            "-f", "h264", str(sample)], capture_output=True, text=True, timeout=60)
        if r.returncode != 0:
            print(f"skipped: ffmpeg cannot encode h264 here ({r.stderr.strip()[:80]})", end=" ")
            return
        data = sample.read_bytes()
        expected = _probe(ffprobe, sample)
        assert expected.startswith("h264,Constrained Baseline,1280,720,30"), expected
        seen = {}

        def check(out_dir):
            seen["probe"] = _probe(ffprobe, out_dir / "video.h264")
            assert (out_dir / "video.h264").read_bytes() == data

        run_scenario("auto", "hur", "plain", "phone_bye", False, sample=data, check=check)
        assert seen["probe"] == expected, (seen["probe"], expected)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def test_ffmpeg_presence_noted():
    # informational only
    print(f"ffmpeg={shutil.which('ffmpeg')} ffprobe={shutil.which('ffprobe')}", end=" ")


def _main() -> int:
    global _PLAIN_RUNNER
    _PLAIN_RUNNER = True
    names = sorted(n for n in globals() if n.startswith("test_"))
    failed = skipped = 0
    for name in names:
        t0 = time.monotonic()
        try:
            globals()[name]()
            print(f"PASS {name} ({time.monotonic() - t0:.2f}s)")
        except _Skip as exc:
            skipped += 1
            print(f"SKIP {name} ({exc})")
        except Exception:                                       # noqa: BLE001
            failed += 1
            print(f"FAIL {name}")
            traceback.print_exc()
    print(f"{len(names) - failed - skipped}/{len(names)} passed, {skipped} skipped")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(_main())
