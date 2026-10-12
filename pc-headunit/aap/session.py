"""One Android Auto head-unit session over an already connected TCP socket.

Order (docs/aa-headunit.md section 8): Version Request/Response -> TLS handshake inside plain
ENCAPSULATED_SSL frames -> Auth Complete -> encrypted traffic: ServiceDiscovery, ChannelOpen per
service, sensors, key binding, media Setup/Config/Start, video focus, DATA + Ack, ping, bye.

All I/O is on the calling thread; request_stop() is the only thing meant to be called from a
signal handler.
"""
from __future__ import annotations

import re
import socket
import ssl
import struct
import time
from collections import deque
from dataclasses import dataclass, field
from pathlib import Path
from typing import BinaryIO, Callable, Deque, Dict, List, Optional, Tuple

from proto import control_pb2, input_pb2, media_pb2, sensors_pb2

from . import framing as F
from . import messages as M
from .log import SessionLog
from .tls import TlsClient

VERSION_TIMEOUT_S = 10.0          # AapTransport.kt HANDSHAKE_TIMEOUT_MS
TLS_TIMEOUT_S = 15.0              # AapSslContext.kt SSL_HANDSHAKE_TIMEOUT_MS
POLL_S = 0.1
BYE_WAIT_S = 2.0
DRAIN_WAIT_S = 1.5

_START_CODE = re.compile(b"\x00\x00\x01")


class SessionError(Exception):
    pass


class PeerClosed(SessionError):
    pass


@dataclass
class Options:
    duration: Optional[float] = None      # seconds after Auth Complete; None = until Ctrl+C
    touch_test: bool = False
    touch_delay_s: float = 10.0           # after the first video DATA
    touch_x: int = 640
    touch_y: int = 360
    cmd_file: Optional[Path] = None       # polled for "tap X Y" / "tap2 X Y" lines (tap2 = duplicated DOWN)
    idle_timeout_s: float = 20.0          # no frame from the phone at all (it pings ~1/s)
    version_attempts: int = 1
    max_unacked_video: int = 12           # AapControl.kt maxUnackedFor(): 12 wireless/socket
    max_unacked_audio: int = 30
    sd: dict = field(default_factory=dict)  # kwargs of messages.build_service_discovery_response


@dataclass
class Stats:
    rx_frames: int = 0
    tx_frames: int = 0
    video_codec_config: int = 0
    video_data: int = 0
    video_bytes: int = 0
    audio_data: Dict[int, int] = field(default_factory=dict)
    first_video_at: Optional[float] = None


def nal_types(data: bytes, limit: int = 512) -> List[int]:
    """NAL unit types of the start codes found in the first `limit` bytes (for the log)."""
    head = data[:limit]
    out = []
    for m in _START_CODE.finditer(head):
        i = m.end()
        if i < len(head):
            out.append(head[i] & 0x1F)
    return out


class Session:
    def __init__(self, sock: socket.socket, tls: TlsClient, opts: Options, log: SessionLog,
                 out_dir: Path) -> None:
        self.sock = sock
        self.tls = tls
        self.opts = opts
        self.log = log
        self.out_dir = out_dir
        self.parser = F.FrameParser()
        self.reasm = F.Reassembler(warn=lambda s: self.log.warn(f"reassembly: {s}"))
        self.stats = Stats()
        self.stop_reason: Optional[str] = None
        self.exit_ok = False
        self._frames: Deque[F.Frame] = deque()
        self._timers: List[Tuple[float, Callable[[], None]]] = []
        self._session_ids: Dict[int, int] = {}
        self._peer_closed = False
        self._bye_sent = False
        self._bye_acked = False
        self._established_at: Optional[float] = None
        self._last_rx = time.monotonic()
        self._touch_scheduled = False
        self._video_file: Optional[BinaryIO] = None
        self._audio_files: Dict[int, BinaryIO] = {}

    # ---------------------------------------------------------------------------------------------
    # public
    # ---------------------------------------------------------------------------------------------
    def request_stop(self, reason: str) -> None:
        if self.stop_reason is None:
            self.stop_reason = reason

    def run(self) -> None:
        """Handshake, serve until stopped, then shut down cleanly. Exceptions end up in the log."""
        self.out_dir.mkdir(parents=True, exist_ok=True)
        self._video_file = open(self.out_dir / "video.h264", "wb")
        try:
            self._version_exchange()
            self._tls_handshake()
            self._send_auth_complete()
            self._established_at = time.monotonic()
            self.log.info(f"session established ({self.tls.describe()}), waiting for the phone")
            self._serve()
        except PeerClosed as exc:
            self._peer_closed = True
            self.stop_reason = self.stop_reason or f"peer closed: {exc}"
            self.log.warn(str(exc))
        except SessionError as exc:
            self.stop_reason = self.stop_reason or str(exc)
            self.log.warn(str(exc))
        except ssl.SSLError as exc:
            self.stop_reason = self.stop_reason or f"TLS error: {exc}"
            self.log.warn(f"TLS error: {exc!r}")
        except OSError as exc:
            self._peer_closed = True
            self.stop_reason = self.stop_reason or f"socket error: {exc}"
            self.log.warn(f"socket error: {exc!r}")
        except KeyboardInterrupt:
            self.stop_reason = self.stop_reason or "KeyboardInterrupt"
            self.log.info("KeyboardInterrupt, shutting down")
        finally:
            self._shutdown()
            self._close_files()
            self._summary()

    # ---------------------------------------------------------------------------------------------
    # socket / framing
    # ---------------------------------------------------------------------------------------------
    def _send_raw(self, data: bytes) -> None:
        self.stats.tx_frames += 1
        self.sock.sendall(data)

    def _read_more(self, timeout: float) -> None:
        """Pull whatever the socket has into the parser/queue; returns after at most `timeout`."""
        self.sock.settimeout(max(timeout, 0.001))
        try:
            data = self.sock.recv(65536)
        except socket.timeout:
            return
        except ConnectionResetError as exc:
            raise PeerClosed(f"connection reset by the phone ({exc})") from exc
        if not data:
            raise PeerClosed("the phone closed the connection (EOF)")
        self._last_rx = time.monotonic()
        for fr in self.parser.feed(data):
            self.stats.rx_frames += 1
            self._frames.append(fr)

    def _next_frame(self, deadline: float) -> Optional[F.Frame]:
        while not self._frames:
            left = deadline - time.monotonic()
            if left <= 0:
                return None
            self._read_more(min(left, POLL_S))
            if self.stop_reason:
                raise SessionError(f"interrupted during the handshake: {self.stop_reason}")
        return self._frames.popleft()

    def _send(self, out: M.Out, media: bool = False, note: str = "") -> None:
        """Encrypt and send one message (chan, type, protobuf bytes)."""
        chan, msg_type, payload = out
        self.log.msg("TX", chan, msg_type, len(payload), M.describe(chan, msg_type, payload),
                     media=media, note=note)
        plaintext = struct.pack(">H", msg_type) + payload
        for flags, chunk, total in F.split_message(plaintext, chan, msg_type):
            record = self.tls.encrypt(chunk)
            self._send_raw(F.build_frame(chan, flags, record, total))

    def _send_ssl(self, data: bytes) -> None:
        if data:
            self.log.msg("TX", F.CHANNEL_CONTROL, M.ENCAPSULATED_SSL, len(data), "plain frame")
            self._send_raw(F.build_plain(F.CHANNEL_CONTROL, M.ENCAPSULATED_SSL, data))

    # ---------------------------------------------------------------------------------------------
    # handshake
    # ---------------------------------------------------------------------------------------------
    def _version_exchange(self) -> None:
        body = M.version_request_body()
        frame = F.build_frame(F.CHANNEL_CONTROL, F.FLAGS_PLAIN_BULK, body)
        for attempt in range(1, self.opts.version_attempts + 1):
            self.log.msg("TX", 0, M.VERSION_REQUEST, len(body) - 2, f"attempt {attempt}: {frame.hex(' ')}")
            self._send_raw(frame)
            deadline = time.monotonic() + VERSION_TIMEOUT_S / self.opts.version_attempts
            while True:
                fr = self._next_frame(deadline)
                if fr is None:
                    break
                if fr.chan == 0 and not fr.encrypted and len(fr.body) >= 2 \
                        and struct.unpack_from(">H", fr.body)[0] == M.VERSION_RESPONSE:
                    self._on_version_response(fr.body[2:])
                    return
                self.log.warn(f"ignoring frame before the version response: ch={fr.chan} "
                              f"flags=0x{fr.flags:02x} {fr.body[:16].hex(' ')}")
        raise SessionError(
            "no VERSION_RESPONSE. If the connection stayed open but silent, the head unit server is "
            "stuck on an earlier session: stop/start it in Android Auto developer settings, "
            "force-stop Android Auto if that does not help.")

    def _on_version_response(self, data: bytes) -> None:
        parsed = M.parse_version_response(data)
        if parsed is None:
            raise SessionError(f"VERSION_RESPONSE too short: {data.hex(' ')}")
        major, minor, status, options = parsed
        extra = f" options={options.hex(' ')}" if options else ""
        self.log.msg("RX", 0, M.VERSION_RESPONSE, len(data),
                     f"phone selected {major}.{minor} (asked {M.VERSION_MAJOR}.{M.VERSION_MINOR}) "
                     f"status={status}{extra}")
        if status != 0:
            raise SessionError(f"VERSION_RESPONSE status {status}, the phone refuses protocol {major}.{minor}")

    def _tls_handshake(self) -> None:
        deadline = time.monotonic() + TLS_TIMEOUT_S
        self._send_ssl(self.tls.handshake_step())
        while not self.tls.handshake_done:
            fr = self._next_frame(deadline)
            if fr is None:
                raise SessionError("TLS handshake timed out")
            if fr.encrypted or len(fr.body) < 2:
                self.log.warn(f"unexpected frame during TLS handshake: ch={fr.chan} flags=0x{fr.flags:02x}")
                continue
            msg_type = struct.unpack_from(">H", fr.body)[0]
            if msg_type == M.ENCAPSULATED_SSL:
                self.log.msg("RX", fr.chan, msg_type, len(fr.body) - 2, "plain frame")
                try:
                    out = self.tls.handshake_step(fr.body[2:])
                except ssl.SSLError as exc:
                    raise SessionError(f"TLS handshake failed: {exc}") from exc
                self._send_ssl(out)
            elif msg_type == M.VERSION_RESPONSE:
                self.log.info("discarded a late VERSION_RESPONSE")
            else:
                self.log.warn(f"unexpected plain message during TLS handshake: type=0x{msg_type:04x}")

    def _send_auth_complete(self) -> None:
        self.log.msg("TX", 0, M.AUTH_COMPLETE, len(M.AUTH_COMPLETE_DATA), "AuthResponse{status: 0}")
        self._send_raw(F.build_plain(F.CHANNEL_CONTROL, M.AUTH_COMPLETE, M.AUTH_COMPLETE_DATA))

    # ---------------------------------------------------------------------------------------------
    # main loop
    # ---------------------------------------------------------------------------------------------
    def _serve(self) -> None:
        while not self.stop_reason:
            self._read_more(POLL_S)
            while self._frames and not self.stop_reason:
                self._on_frame(self._frames.popleft())
            self._run_timers()
            self._poll_cmd_file()
            now = time.monotonic()
            if self.opts.duration is not None and self._established_at is not None \
                    and now - self._established_at >= self.opts.duration:
                self.request_stop(f"--duration {self.opts.duration:g}s elapsed")
                self.exit_ok = True
            elif now - self._last_rx > self.opts.idle_timeout_s:
                self.request_stop(f"nothing from the phone for {self.opts.idle_timeout_s:g}s")

    def _run_timers(self) -> None:
        now = time.monotonic()
        due = [t for t in self._timers if t[0] <= now]
        if not due:
            return
        self._timers = [t for t in self._timers if t[0] > now]
        for _, fn in sorted(due, key=lambda t: t[0]):
            fn()

    def _on_frame(self, fr: F.Frame) -> None:
        if not fr.encrypted:
            self._on_plain_frame(fr)
            return
        try:
            plaintext, out = self.tls.decrypt(fr.body)
        except ssl.SSLError as exc:
            raise SessionError(f"TLS alert/error from the phone: {exc} "
                               f"(on ch{fr.chan} flags=0x{fr.flags:02x})") from exc
        self._send_ssl(out)
        if self.tls.closed:
            raise PeerClosed("the phone sent TLS close_notify")
        if not plaintext:
            self.log.msg("RX", fr.chan, 0, 0, "TLS record without application data", media=True,
                         note=f"flags=0x{fr.flags:02x}")
            return
        whole = self.reasm.push(fr.chan, fr.flags, plaintext, fr.total)
        if whole is None:
            return
        if len(whole) < 2:
            self.log.warn(f"ch{fr.chan}: message shorter than its type field: {whole.hex()}")
            return
        self._dispatch(fr.chan, struct.unpack_from(">H", whole)[0], whole[2:])

    def _on_plain_frame(self, fr: F.Frame) -> None:
        if len(fr.body) < 2:
            self.log.warn(f"plain frame without a type: ch={fr.chan} flags=0x{fr.flags:02x}")
            return
        msg_type = struct.unpack_from(">H", fr.body)[0]
        if msg_type == M.ENCAPSULATED_SSL:
            # Post-handshake TLS records (TLS 1.3 session tickets) may come as plain SSL frames.
            # They must go through the TLS layer: it counts records, and skipping one would break
            # the decryption of every application record after it.
            _, out = self.tls.decrypt(fr.body[2:])
            self.log.msg("RX", fr.chan, msg_type, len(fr.body) - 2, "post-handshake TLS data (plain frame)")
            self._send_ssl(out)
            return
        self.log.msg("RX", fr.chan, msg_type, len(fr.body) - 2,
                     f"unexpected plain frame after the handshake, flags=0x{fr.flags:02x} "
                     f"{fr.body[2:18].hex(' ')}")

    # ---------------------------------------------------------------------------------------------
    # message handlers
    # ---------------------------------------------------------------------------------------------
    def _dispatch(self, chan: int, msg_type: int, payload: bytes) -> None:
        is_media_data = chan in (M.CH_VIDEO,) + M.AUDIO_CHANNELS and msg_type in (
            M.MEDIA_DATA, M.MEDIA_CODEC_CONFIG)
        if is_media_data:
            self._on_media_data(chan, msg_type, payload)
            return
        self.log.msg("RX", chan, msg_type, len(payload), M.describe(chan, msg_type, payload))

        if msg_type == M.CHANNEL_OPEN_REQUEST:
            self._send(M.channel_open_response(chan))
            if chan == M.CH_SENSOR:
                self._send(M.driving_status_event(sensors_pb2.SensorBatch.DrivingStatusData.UNRESTRICTED))
            return

        if chan == M.CH_CONTROL:
            self._on_control(msg_type, payload)
        elif chan == M.CH_SENSOR:
            self._on_sensor(msg_type, payload)
        elif chan == M.CH_INPUT:
            if msg_type == M.INPUT_BINDING_REQUEST:
                self._send(M.binding_response(chan))
        elif chan == M.CH_VIDEO or chan in M.AUDIO_CHANNELS or chan == M.CH_MIC:
            self._on_media_control(chan, msg_type, payload)
        # MEDIA_PLAYBACK / NAVIGATION: the phone only pushes status here, nothing to answer.

    def _on_control(self, msg_type: int, payload: bytes) -> None:
        if msg_type == M.SERVICE_DISCOVERY_REQUEST:
            self._send(M.service_discovery_response(**self.opts.sd))
        elif msg_type == M.PING_REQUEST:
            req = M.parse(control_pb2.PingRequest, payload)
            self._send(M.ping_response(req.timestamp))
        elif msg_type == M.NAV_FOCUS_REQUEST:
            self._send(M.nav_focus_notification())
        elif msg_type == M.AUDIO_FOCUS_REQUEST:
            req = M.parse(control_pb2.AudioFocusRequestNotification, payload)
            out = M.audio_focus_response(req.request)
            if out is not None:
                self._send(out)
        elif msg_type == M.BYEBYE_REQUEST:
            self._send(M.byebye_response())
            time.sleep(0.5)                       # AapControl.kt:378
            self._bye_acked = True
            self.request_stop("the phone sent ByeByeRequest")
            self.exit_ok = True
        elif msg_type == M.BYEBYE_RESPONSE:
            self._bye_acked = True
            self.request_stop("ByeByeResponse received")
            self.exit_ok = True
        # VOICE_SESSION_NOTIFICATION, CHANNEL_CLOSE_NOTIFICATION and the rest: logged only.

    def _on_sensor(self, msg_type: int, payload: bytes) -> None:
        if msg_type != M.SENSOR_START_REQUEST:
            return
        req = M.parse(sensors_pb2.SensorRequest, payload)
        self._send(M.sensor_start_response())
        if req.type == sensors_pb2.NIGHT:
            self._send(M.night_mode_event(False))

    def _on_media_control(self, chan: int, msg_type: int, payload: bytes) -> None:
        if msg_type == M.MEDIA_SETUP:
            unacked = self.opts.max_unacked_video if chan == M.CH_VIDEO else self.opts.max_unacked_audio
            self._send(M.media_config(chan, unacked))
            if chan == M.CH_VIDEO:
                self._send(M.video_focus_notification(projected=True, unsolicited=True))
            elif chan in M.AUDIO_CHANNELS:
                self._send(M.audio_focus_gain_unsolicited())
        elif msg_type == M.MEDIA_START:
            start = M.parse(media_pb2.Start, payload)
            self._session_ids[chan] = start.session_id
        elif msg_type == M.MEDIA_VIDEO_FOCUS_REQUEST and chan == M.CH_VIDEO:
            req = M.parse(media_pb2.VideoFocusRequestNotification, payload)
            if req.mode == media_pb2.VIDEO_FOCUS_PROJECTED:
                self._send(M.video_focus_notification(projected=True, unsolicited=False))
        elif msg_type == M.MEDIA_MIC_REQUEST and chan == M.CH_MIC:
            req = M.parse(media_pb2.MicrophoneRequest, payload)
            self._send(M.mic_response(success=not req.open))   # no capture here: refuse an open
        # MEDIA_STOP, MEDIA_ACK, UPDATE_UI_CONFIG_*: logged only.

    def _on_media_data(self, chan: int, msg_type: int, payload: bytes) -> None:
        is_data = msg_type == M.MEDIA_DATA
        if is_data:
            if len(payload) < 8:
                self.log.warn(f"ch{chan}: DATA shorter than its timestamp ({len(payload)} B)")
                return
            ts_us = struct.unpack_from(">Q", payload)[0]
            body = payload[8:]
            note = f"ts={ts_us}us"
        else:
            body, note = payload, "codec config"

        if chan == M.CH_VIDEO:
            nals = nal_types(body)
            note += f" nal={nals}" if nals else ""
            if is_data:
                self.stats.video_data += 1
                if self.stats.first_video_at is None:
                    self.stats.first_video_at = time.monotonic()
                    self._schedule_touch()
            else:
                self.stats.video_codec_config += 1
            self.stats.video_bytes += len(body)
            if self._video_file:
                self._video_file.write(body)
        else:
            if is_data:
                self.stats.audio_data[chan] = self.stats.audio_data.get(chan, 0) + 1
                f = self._audio_files.get(chan)
                if f is None:
                    f = self._audio_files[chan] = open(self.out_dir / f"audio_{chan}.pcm", "wb")
                f.write(body)

        self.log.msg("RX", chan, msg_type, len(body), "", media=True, note=note)
        # Video acks DATA and CODEC_CONFIG (AapTransport.kt dispatchVideo), audio only DATA
        # (AudioMediaPayload.requiresAck). After the payload is stored, one ack per whole message.
        if chan == M.CH_VIDEO or is_data:
            self._send(M.media_ack(chan, self._session_ids.get(chan, 0)), media=True)

    # ---------------------------------------------------------------------------------------------
    # touch test
    # ---------------------------------------------------------------------------------------------
    def _schedule_touch(self) -> None:
        if not self.opts.touch_test or self._touch_scheduled:
            return
        self._touch_scheduled = True
        now = time.monotonic()
        x, y = self.opts.touch_x, self.opts.touch_y
        self.log.info(f"touch test: tap at ({x},{y}) in {self.opts.touch_delay_s:g}s")
        down = input_pb2.TouchEvent.TOUCH_ACTION_DOWN
        up = input_pb2.TouchEvent.TOUCH_ACTION_UP
        self._timers.append((now + self.opts.touch_delay_s,
                             lambda: self._send(M.touch_event(down, x, y, time.monotonic_ns()))))
        self._timers.append((now + self.opts.touch_delay_s + 0.1,
                             lambda: self._send(M.touch_event(up, x, y, time.monotonic_ns()))))

    def _poll_cmd_file(self) -> None:
        path = self.opts.cmd_file
        if path is None or not path.exists():
            return
        try:
            lines = path.read_text(encoding="utf-8").splitlines()
            path.unlink()
        except OSError:
            return
        down = input_pb2.TouchEvent.TOUCH_ACTION_DOWN
        up = input_pb2.TouchEvent.TOUCH_ACTION_UP
        now = time.monotonic()
        for line in lines:
            parts = line.split()
            if len(parts) != 3 or parts[0] not in ("tap", "tap2"):
                self.log.warn(f"cmd file: ignored {line!r}")
                continue
            x, y = int(parts[1]), int(parts[2])
            self.log.info(f"cmd file: {parts[0]} at ({x},{y})")
            self._send(M.touch_event(down, x, y, time.monotonic_ns()))
            if parts[0] == "tap2":
                self._timers.append((now + 0.01, lambda x=x, y=y: self._send(M.touch_event(down, x, y, time.monotonic_ns()))))
            self._timers.append((now + 0.1, lambda x=x, y=y: self._send(M.touch_event(up, x, y, time.monotonic_ns()))))

    # ---------------------------------------------------------------------------------------------
    # shutdown
    # ---------------------------------------------------------------------------------------------
    def _shutdown(self) -> None:
        """ByeBye, wait for the answer, then close so the server sees an orderly end.

        The head unit server accepts one connection and wedges after a dirty drop, so: send
        ByeByeRequest (unless the phone already ended it), wait for ByeByeResponse or EOF, half-close,
        read until the phone closes its side (closing with unread data in the receive queue makes
        Windows send RST), and only then close.
        """
        self.log.info(f"shutting down: {self.stop_reason or 'end of run'}")
        try:
            if self.tls.handshake_done and not self._peer_closed and not self._bye_acked \
                    and not self._bye_sent:
                self._bye_sent = True
                self._send(M.byebye_request())
                time.sleep(0.15)                  # AapTransport.kt:706
                self._wait_bye(BYE_WAIT_S)
        except (OSError, SessionError, ssl.SSLError) as exc:
            self.log.warn(f"while sending ByeBye: {exc!r}")
        try:
            try:
                self.sock.shutdown(socket.SHUT_WR)
            except OSError:
                pass
            if not self._peer_closed:
                self._drain_until_eof(DRAIN_WAIT_S)
        finally:
            self.sock.close()
            self.log.info("socket closed")

    def _wait_bye(self, timeout: float) -> None:
        """Keep reading (and answering the phone's own bye) until ByeByeResponse, EOF or timeout."""
        deadline = time.monotonic() + timeout
        try:
            while not self._bye_acked and time.monotonic() < deadline:
                self._read_more(min(POLL_S, max(deadline - time.monotonic(), 0.001)))
                while self._frames:
                    fr = self._frames.popleft()
                    if not fr.encrypted:
                        continue
                    plaintext, out = self.tls.decrypt(fr.body)
                    self._send_ssl(out)
                    whole = self.reasm.push(fr.chan, fr.flags, plaintext, fr.total) if plaintext else None
                    if whole is None or len(whole) < 2:
                        continue
                    t = struct.unpack_from(">H", whole)[0]
                    if fr.chan == M.CH_CONTROL and t in (M.BYEBYE_RESPONSE, M.BYEBYE_REQUEST):
                        self.log.msg("RX", fr.chan, t, len(whole) - 2, M.describe(fr.chan, t, whole[2:]))
                        if t == M.BYEBYE_REQUEST:
                            self._send(M.byebye_response())
                        self._bye_acked = True
        except PeerClosed:
            self._peer_closed = True
            self.log.info("the phone closed the connection after ByeBye")
        if not self._bye_acked and not self._peer_closed:
            self.log.warn(f"no ByeByeResponse within {timeout:g}s, closing anyway")

    def _drain_until_eof(self, timeout: float) -> None:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            self.sock.settimeout(max(deadline - time.monotonic(), 0.001))
            try:
                if not self.sock.recv(65536):
                    return
            except (socket.timeout, OSError):
                return

    def _close_files(self) -> None:
        for f in [self._video_file, *self._audio_files.values()]:
            if f:
                f.close()
        self._video_file = None
        self._audio_files.clear()

    def _summary(self) -> None:
        s = self.stats
        audio = ", ".join(f"ch{c}: {n}" for c, n in sorted(s.audio_data.items())) or "none"
        self.log.info(
            f"summary: frames rx={s.rx_frames} tx={s.tx_frames}; video codec_config={s.video_codec_config} "
            f"data={s.video_data} bytes={s.video_bytes}; audio data {audio}; "
            f"stop reason: {self.stop_reason}")
