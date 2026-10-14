"""Baidu CarLife head unit emulator (Honda CR-V 2026, China) for testing the Headswap bridge.

    adb reverse tcp:7240 tcp:7240
    python carlife_hu.py --duration 120

The bridge (Android app) plays the phone (MD) and connects to this TCP server over `adb reverse`;
this script plays the head unit (HU). One connection per start. After connect it sends
HU_PROTOCOL_VERSION, after the bridge's MD_INFO the HU init burst (STATISTIC_INFO, HU_INFO,
HU_AUTHEN_REQUEST "2.0;<date>", video init/start, feature config), then a VIDEO_HEARTBEAT every second.
MD_AUTHEN_RESPONSE is checked as md5("CARLIFE" + brand + model + sdk + <random after ';'>).
Video goes to out/carlife_video.h264, the whole log to out/carlife_hu.log. Standard library only.
"""
from __future__ import annotations

import argparse
import hashlib
import select
import socket
import struct
import sys
import threading
import time
from collections import Counter
from datetime import datetime
from pathlib import Path

HERE = Path(__file__).resolve().parent

# каналы внешнего заголовка (Protocol.kt, object Ch)
CH_CMD, CH_VIDEO, CH_MEDIA, CH_TTS, CH_VR, CH_TOUCH = 1, 2, 3, 4, 5, 6
CH_NAMES = {CH_CMD: "CMD", CH_VIDEO: "VIDEO", CH_MEDIA: "MEDIA", CH_TTS: "TTS", CH_VR: "VR", CH_TOUCH: "TOUCH"}
MAX_OUTER_LEN = 64 * 1024 * 1024

# коды сообщений (Protocol.kt, object Msg)
HU_PROTOCOL_VERSION = 0x00018001
MD_INFO = 0x00010004
HU_INFO = 0x00018003
VIDEO_ENCODER_INIT = 0x00018007
VIDEO_ENCODER_INIT_DONE = 0x00010008
VIDEO_ENCODER_START = 0x00018009
STATISTIC_INFO = 0x00018027
HU_AUTHEN_REQUEST = 0x00018048
MD_AUTHEN_RESPONSE = 0x00010049
HU_AUTHEN_RESULT = 0x0001804A
HU_UNKNOWN_1804C = 0x0001804C
HU_FEATURE_CONFIG_RESPONSE = 0x00018052
VIDEO_DATA = 0x00020001
VIDEO_HEARTBEAT = 0x00020002
TOUCH_ACTION = 0x00068001

NAMES = {
    0x00010002: "PROTOCOL_VERSION_MATCH_STATUS", MD_INFO: "MD_INFO", 0x00010006: "MD_BT_OOB_INFO",
    VIDEO_ENCODER_INIT_DONE: "VIDEO_ENCODER_INIT_DONE", 0x0001000D: "VIDEO_ENCODER_FRAME_RATE_CHANGE_DONE",
    0x00010018: "SCREEN_ON", 0x0001001B: "FOREGROUND", 0x0001001C: "BACKGROUND",
    0x0001801D: "LAUNCH_MODE_NORMAL", 0x00010021: "GO_TO_DESKTOP", 0x00010026: "MODULE_STATUS",
    STATISTIC_INFO: "STATISTIC_INFO", 0x00018028: "MODULE_CONTROL", 0x00010029: "GEAR_INFO",
    0x00010030: "NAVI_NEXT_TURN_INFO", 0x00010031: "CAR_DATA_SUBSCRIBE", 0x00010035: "MEDIA_INFO",
    0x00010036: "MEDIA_PROGRESS_BAR", 0x00010037: "CONNECT_EXCEPTION", 0x00018042: "BT_HFP_CONNECTION",
    0x00018043: "CARLIFE_DATA_SUBSCRIBE", 0x00010044: "CARLIFE_DATA_SUBSCRIBE_DONE",
    0x00018045: "CARLIFE_DATA_SUBSCRIBE_START", 0x00018046: "CARLIFE_DATA_SUBSCRIBE_STOP",
    MD_AUTHEN_RESPONSE: "MD_AUTHEN_RESPONSE", 0x0001004B: "MD_AUTHEN_RESULT",
    0x00010051: "MD_FEATURE_CONFIG_REQUEST", 0x00018055: "ERROR_CODE", 0x00010059: "MD_EXIT",
    0x00018078: "CHANGE_MD_THEME", 0x00018079: "REQUEST_MD_THEME_STATE", 0x00010080: "RESPONSE_MD_THEME_STATE",
    VIDEO_DATA: "VIDEO_DATA", VIDEO_HEARTBEAT: "VIDEO_HEARTBEAT",
    0x00030001: "MEDIA_INIT", 0x00030006: "MEDIA_DATA", 0x00040001: "TTS_INIT", 0x00040003: "TTS_DATA",
    0x00050002: "VR_INIT", 0x00050003: "VR_DATA",
    HU_PROTOCOL_VERSION: "HU_PROTOCOL_VERSION", HU_INFO: "HU_INFO", VIDEO_ENCODER_INIT: "VIDEO_ENCODER_INIT",
    VIDEO_ENCODER_START: "VIDEO_ENCODER_START", HU_AUTHEN_REQUEST: "HU_AUTHEN_REQUEST",
    HU_AUTHEN_RESULT: "HU_AUTHEN_RESULT", HU_UNKNOWN_1804C: "HU_1804C",
    HU_FEATURE_CONFIG_RESPONSE: "HU_FEATURE_CONFIG_RESPONSE", TOUCH_ACTION: "TOUCH_ACTION",
}


def msg_name(t: int) -> str:
    return NAMES.get(t, "UNKNOWN")


# ---- protobuf руками (varint и length-delimited) ----

def varint(v: int) -> bytes:
    v &= 0xFFFFFFFFFFFFFFFF          # отрицательный int32 = 10 байт, как в protoc
    out = bytearray()
    while True:
        b = v & 0x7F
        v >>= 7
        if v:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)


def pb_int(field: int, v: int) -> bytes:
    return varint(field << 3) + varint(v)


def pb_bytes(field: int, b: bytes) -> bytes:
    return varint(field << 3 | 2) + varint(len(b)) + b


def pb_str(field: int, s: str) -> bytes:
    return pb_bytes(field, s.encode("utf-8"))


def pb_parse(b: bytes) -> list[tuple[int, int, object]] | None:
    """[(поле, wire, значение)]; None, если байты не похожи на protobuf. Строки/вложенные: bytes."""
    res = []
    p = 0

    def rd_varint():
        nonlocal p
        r = 0
        shift = 0
        while p < len(b) and shift < 70:
            x = b[p]
            p += 1
            r |= (x & 0x7F) << shift
            if not x & 0x80:
                return r
            shift += 7
        return None

    while p < len(b):
        t = rd_varint()
        if t is None:
            return None
        wire, field = t & 7, t >> 3
        if field == 0:
            return None
        if wire == 0:
            v = rd_varint()
            if v is None:
                return None
            res.append((field, 0, v))
        elif wire == 2:
            n = rd_varint()
            if n is None or p + n > len(b):
                return None
            res.append((field, 2, b[p:p + n]))
            p += n
        elif wire == 1 and p + 8 <= len(b):
            res.append((field, 1, b[p:p + 8]))
            p += 8
        elif wire == 5 and p + 4 <= len(b):
            res.append((field, 5, b[p:p + 4]))
            p += 4
        else:
            return None
    return res


def pb_get_str(fields, number: int) -> str | None:
    for f, w, v in fields or []:
        if f == number and w == 2:
            return bytes(v).decode("utf-8", "replace")
    return None


def pb_get_int(fields, number: int) -> int | None:
    for f, w, v in fields or []:
        if f == number and w == 0:
            return int(v)
    return None


def pb_render(body: bytes, limit: int = 200) -> str:
    """Короткий текст protobuf-тела для лога: поле=значение."""
    if not body:
        return "{}"
    fields = pb_parse(body)
    if fields is None:
        return "<not protobuf: " + body[:32].hex(" ") + ">"
    parts = []
    for f, w, v in fields:
        if w == 2:
            try:
                s = bytes(v).decode("utf-8")
                ok = all(c.isprintable() for c in s)
            except UnicodeDecodeError:
                ok = False
            parts.append(f"f{f}={s!r}" if ok else f"f{f}=<{len(v)}B {bytes(v)[:16].hex()}>")
        else:
            parts.append(f"f{f}={v if w == 0 else bytes(v).hex()}")
    s = " ".join(parts)
    return "{" + (s if len(s) <= limit else s[:limit] + "...") + "}"


# ---- кадры ----

def outer(chan: int, inner: bytes) -> bytes:
    """Внешний кадр: chan u32 BE, len u32 BE, затем inner."""
    return struct.pack(">II", chan, len(inner)) + inner


def cmd_inner(mtype: int, body: bytes = b"") -> bytes:
    """Inner для CMD/TOUCH: len u16 BE, reserved u16 = 0, type u32 BE."""
    if len(body) > 0xFFFF:
        raise ValueError(f"CMD body too long: {len(body)}")
    return struct.pack(">HHI", len(body), 0, mtype) + body


def data_inner(mtype: int, body: bytes = b"", time_: int = 0) -> bytes:
    """Inner для VIDEO/MEDIA/TTS/VR: len u32 BE, time u32 BE, type u32 BE."""
    return struct.pack(">III", len(body), time_, mtype) + body


def is_cmd_style(chan: int) -> bool:
    return chan in (CH_CMD, CH_TOUCH)


def parse_inner(chan: int, inner: bytes):
    """-> (type, body, truncated) или None, если заголовок неполный."""
    if is_cmd_style(chan):
        if len(inner) < 8:
            return None
        n, _resv, mtype = struct.unpack(">HHI", inner[:8])
        avail = len(inner) - 8
        return mtype, inner[8:8 + min(n, avail)], n > avail
    if len(inner) < 12:
        return None
    n, _t, mtype = struct.unpack(">III", inner[:12])
    avail = len(inner) - 12
    return mtype, inner[12:12 + min(n, avail)], n > avail


class FrameParser:
    """Накопительный разбор внешних кадров из потока TCP."""

    def __init__(self):
        self.buf = bytearray()

    def feed(self, data: bytes):
        """-> список (chan, inner) по всем целым кадрам. ValueError на битом заголовке."""
        self.buf += data
        out = []
        while len(self.buf) >= 8:
            chan, n = struct.unpack(">II", self.buf[:8])
            if chan > 0xFF or n > MAX_OUTER_LEN:
                raise ValueError(f"bad outer header: chan={chan} len={n} {bytes(self.buf[:16]).hex(' ')}")
            if len(self.buf) < 8 + n:
                break
            out.append((chan, bytes(self.buf[8:8 + n])))
            del self.buf[:8 + n]
        return out


# ---- сообщения ГУ ----

def build_protocol_version() -> bytes:
    return cmd_inner(HU_PROTOCOL_VERSION, pb_int(1, 4) + pb_int(2, 2))


def build_statistic_info() -> bytes:
    body = (pb_str(1, "304AC406514A") + pb_str(2, "v2.3.4") + pb_int(3, 2055) + pb_str(4, "21580005")
            + pb_int(5, 0) + pb_int(6, 0) + pb_int(7, 0))
    return cmd_inner(STATISTIC_INFO, body)


HU_INFO_FIELDS = [
    (1, "Android"), (2, "honda_3ya"), (3, "unknown"), (4, "X9"), (5, "arm64-v8a"), (6, ""),
    (7, "honda_3ya"), (8, "01.603.002"),
    (9, "X9/honda_3ya/honda_3ya:12/01.603.002_CXIC_20251205_D/gerritad.20251205.174324:user/dev-keys"),
    (10, "honda_3ya"), (11, "d1ec549ff954"), (12, "01.603.002_CXIC_20251205_D"), (13, "SEMIDRIVE"),
    (14, "x9 for arm64"), (15, "honda_3ya"), (16, "unknown"), (17, "REL"), (18, "gerritad.20251205.174324"),
    (19, "12"), (20, "31"),
]


def build_hu_info() -> bytes:
    body = b"".join(pb_str(f, v) for f, v in HU_INFO_FIELDS) + pb_int(21, 31) + pb_str(23, "yyy-zzz-xxx") + pb_str(24, "v2.3.4")
    return cmd_inner(HU_INFO, body)


def build_auth_request(random_value: str) -> bytes:
    return cmd_inner(HU_AUTHEN_REQUEST, pb_str(1, random_value))


def make_random_value(now: datetime | None = None) -> str:
    return "2.0;" + (now or datetime.now()).strftime("%Y-%m-%d-%H-%M-%S")


def build_video_encoder_init(w: int = 1920, h: int = 720, fps: int = 30) -> bytes:
    # поле 4 в Schemas.videoEncoderInfo нет, ГУ шлёт 2
    return cmd_inner(VIDEO_ENCODER_INIT, pb_int(1, w) + pb_int(2, h) + pb_int(3, fps) + pb_int(4, 2))


FEATURES = [("I_FRAME_INTERVAL", 300), ("USB_MTU", 16384), ("MUSIC_HUD", 1), ("BLUETOOTH_AUTO_CONNECT", 0),
            ("FOCUS_UI", 0), ("VOICE_WAKEUP", 1), ("VOICE_MIC", 0), ("CONNECT_TYPE", 2),
            ("BLUETOOTH_INTERNAL_UI", 0)]


def build_feature_config() -> bytes:
    body = pb_int(1, len(FEATURES))
    for k, v in FEATURES:
        body += pb_bytes(2, pb_str(1, k) + pb_int(2, v))
    return cmd_inner(HU_FEATURE_CONFIG_RESPONSE, body)


def build_auth_result(ok: bool) -> bytes:
    return cmd_inner(HU_AUTHEN_RESULT, pb_int(1, 1 if ok else 0))


def build_touch(action: int, x: int, y: int) -> bytes:
    return cmd_inner(TOUCH_ACTION, pb_int(1, action) + pb_int(2, x) + pb_int(3, y))


def build_heartbeat() -> bytes:
    return data_inner(VIDEO_HEARTBEAT)


def auth_expected(brand: str, model: str, sdk: str, rnd: str) -> str:
    """md5hex("CARLIFE" + brand + model + sdk + rnd), строчный hex (HuAuth.verifyCode)."""
    return hashlib.md5(("CARLIFE" + brand + model + sdk + rnd).encode("utf-8")).hexdigest()


# ---- сессия ----

class Options:
    def __init__(self, host="127.0.0.1", port=7240, duration=120.0, no_auth=False, touch_test=False,
                 out: Path | None = None, quiet=False):
        self.host, self.port, self.duration = host, port, duration
        self.no_auth, self.touch_test = no_auth, touch_test
        self.out = out or HERE / "out"
        self.quiet = quiet


class HeadUnit:
    AUTH_TIMEOUT = 10.0
    TOUCH_DELAY = 15.0

    def __init__(self, opts: Options):
        self.o = opts
        self.srv: socket.socket | None = None
        self.conn: socket.socket | None = None
        self.port = opts.port
        self.wlock = threading.Lock()
        self.logf = None
        self.stop = threading.Event()
        self.stop_reason = ""
        self.counts: Counter = Counter()
        self.brand = self.model = self.sdk = ""
        self.got_md_info = False
        self.auth_random: str | None = None
        self.auth_sent_at: float | None = None
        self.auth_status = "NOT SENT" if opts.no_auth else "NO REQUEST YET"
        self.frames = 0
        self.vbytes = 0
        self.first_frame_at: float | None = None
        self.last_frame_at: float | None = None
        self.vid_size = (1280, 720)
        self.vfile = None
        self.t0 = 0.0

    def log(self, msg: str):
        line = f"{time.strftime('%H:%M:%S')}.{int(time.time() * 1000) % 1000:03d} {msg}"
        if not self.o.quiet:
            print(line, flush=True)
        if self.logf:
            self.logf.write(line + "\n")
            self.logf.flush()

    def listen(self):
        """Открыть порт заранее (port=0 даёт свободный; self.port хранит настоящий)."""
        self.srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.srv.bind((self.o.host, self.o.port))
        self.srv.listen(1)
        self.port = self.srv.getsockname()[1]

    def send(self, chan: int, inner: bytes, label: str = ""):
        assert self.conn
        with self.wlock:
            self.conn.sendall(outer(chan, inner))
        if label:
            self.log(f"HU -> {CH_NAMES.get(chan, chan)} {label}")

    def send_cmd(self, mtype: int, inner: bytes, extra: str = ""):
        body = inner[8:]
        self.send(CH_CMD, inner, f"{msg_name(mtype)} 0x{mtype:08X} len={len(body)} {extra or pb_render(body)}")

    # --- приём от моста ---

    def on_message(self, chan: int, inner: bytes):
        p = parse_inner(chan, inner)
        if p is None:
            self.log(f"MD -> {CH_NAMES.get(chan, chan)} short inner len={len(inner)} {inner.hex(' ')}")
            self.counts[(chan, -1)] += 1
            return
        mtype, body, trunc = p
        if chan == CH_VIDEO and mtype == VIDEO_DATA:
            self.on_video(body)
            return
        self.counts[(chan, mtype)] += 1
        extra = " TRUNCATED" if trunc else ""
        name = msg_name(mtype)
        if chan in (CH_CMD, CH_TOUCH):
            self.log(f"MD -> {CH_NAMES.get(chan, chan)} {name} 0x{mtype:08X} len={len(body)}{extra} {pb_render(body)}")
        else:
            self.log(f"MD -> {CH_NAMES.get(chan, chan)} {name} 0x{mtype:08X} len={len(body)}{extra}")
        if chan != CH_CMD:
            return
        if mtype == MD_INFO:
            self.on_md_info(body)
        elif mtype == MD_AUTHEN_RESPONSE:
            self.on_auth_response(body)
        elif mtype == VIDEO_ENCODER_INIT_DONE:
            f = pb_parse(body)
            w, h = pb_get_int(f, 1), pb_get_int(f, 2)
            if w and h:
                self.vid_size = (w, h)
                self.log(f"video size from INIT_DONE: {w}x{h}")

    def on_md_info(self, body: bytes):
        f = pb_parse(body)
        self.brand = pb_get_str(f, 4) or ""
        self.model = pb_get_str(f, 14) or ""
        self.sdk = pb_get_str(f, 20) or ""
        self.log(f"MD_INFO: brand={self.brand!r} model={self.model!r} sdk={self.sdk!r}")
        if self.got_md_info:
            return
        self.got_md_info = True
        self.send_init_burst()

    def send_init_burst(self):
        self.send_cmd(STATISTIC_INFO, build_statistic_info())
        self.send_cmd(HU_INFO, build_hu_info())
        if not self.o.no_auth:
            self.auth_random = make_random_value()
            self.send_cmd(HU_AUTHEN_REQUEST, build_auth_request(self.auth_random))
            self.auth_sent_at = time.monotonic()
            self.auth_status = "PENDING"
        self.send_cmd(HU_UNKNOWN_1804C, cmd_inner(HU_UNKNOWN_1804C))
        self.send_cmd(VIDEO_ENCODER_INIT, build_video_encoder_init())
        self.send_cmd(HU_FEATURE_CONFIG_RESPONSE, build_feature_config())
        self.send_cmd(VIDEO_ENCODER_START, cmd_inner(VIDEO_ENCODER_START))

    def on_auth_response(self, body: bytes):
        got = pb_get_str(pb_parse(body), 1) or ""
        if self.auth_random is None:
            self.log("MD_AUTHEN_RESPONSE without our request, ignored")
            return
        rnd = self.auth_random.split(";", 1)[1] if ";" in self.auth_random else self.auth_random
        expected = auth_expected(self.brand, self.model, self.sdk, rnd)
        ok = got == expected
        if ok:
            self.log("AUTH OK")
        else:
            self.log(f"AUTH FAIL expected={expected} got={got}")
        self.auth_status = "AUTH OK" if ok else "AUTH FAIL"
        self.auth_random = None
        self.auth_sent_at = None
        self.send_cmd(HU_AUTHEN_RESULT, build_auth_result(ok))

    def on_video(self, body: bytes):
        now = time.monotonic()
        if self.first_frame_at is None:
            self.first_frame_at = now
            self.log(f"first video frame, {len(body)} B, head {body[:16].hex(' ')}")
        self.last_frame_at = now
        self.frames += 1
        self.vbytes += len(body)
        if self.vfile:
            self.vfile.write(body)

    # --- потоки ---

    def reader(self):
        parser = FrameParser()
        conn = self.conn
        try:
            while not self.stop.is_set():
                r, _, _ = select.select([conn], [], [], 0.2)
                if not r:
                    continue
                data = conn.recv(65536)
                if not data:
                    self.finish("bridge closed the connection")
                    return
                for chan, inner in parser.feed(data):
                    self.on_message(chan, inner)
        except (OSError, ValueError) as e:
            if not self.stop.is_set():
                self.finish(f"read error: {e}")

    def finish(self, reason: str):
        if not self.stop.is_set():
            self.stop_reason = reason
            self.stop.set()

    def run(self) -> dict:
        """Принять одно соединение, отработать сессию, вернуть итог (dict)."""
        self.o.out.mkdir(parents=True, exist_ok=True)
        self.logf = open(self.o.out / "carlife_hu.log", "w", encoding="utf-8")
        self.vfile = open(self.o.out / "carlife_video.h264", "wb")
        try:
            if self.srv is None:
                self.listen()
            self.log(f"listening on {self.o.host}:{self.port}")
            self.conn, addr = self.srv.accept()
            self.conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            self.log(f"bridge connected from {addr[0]}:{addr[1]}")
            self.t0 = time.monotonic()
            self.send_cmd(HU_PROTOCOL_VERSION, build_protocol_version())
            rt = threading.Thread(target=self.reader, daemon=True)
            rt.start()
            try:
                self.loop()
            except KeyboardInterrupt:
                self.finish("Ctrl+C")
            except OSError as e:
                self.finish(f"write error: {e}")
            self.stop.set()
            try:
                self.conn.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            self.conn.close()
            rt.join(2)
        finally:
            self.vfile.close()
            if self.srv:
                self.srv.close()
        res = self.summary()
        self.logf.close()
        return res

    def loop(self):
        next_hb = time.monotonic()
        next_fps = time.monotonic() + 5
        fps_frames = 0
        touched = False
        while not self.stop.is_set():
            now = time.monotonic()
            if now - self.t0 >= self.o.duration:
                self.finish("duration elapsed")
                break
            if now >= next_hb:
                self.send(CH_VIDEO, build_heartbeat())
                next_hb += 1.0
            if self.auth_sent_at is not None and now - self.auth_sent_at >= self.AUTH_TIMEOUT:
                self.auth_status = "AUTH NO RESPONSE"
                self.auth_sent_at = None
                self.log("AUTH NO RESPONSE")
            if now >= next_fps:
                n = self.frames - fps_frames
                fps_frames = self.frames
                next_fps += 5
                self.log(f"video: {self.frames} frames, {self.vbytes} B total, {n / 5:.1f} fps (last 5 s)")
            if (self.o.touch_test and not touched and self.first_frame_at is not None
                    and now - self.first_frame_at >= self.TOUCH_DELAY):
                touched = True
                self.send_tap()
            self.stop.wait(0.05)

    def send_tap(self):
        x, y = self.vid_size[0] // 2, self.vid_size[1] // 2
        self.log(f"touch test: tap at {x},{y} ({self.vid_size[0]}x{self.vid_size[1]})")
        self.send(CH_TOUCH, build_touch(0, x, y), f"TOUCH_ACTION DOWN {x},{y}")
        time.sleep(0.1)
        self.send(CH_TOUCH, build_touch(1, x, y), f"TOUCH_ACTION UP {x},{y}")

    def summary(self) -> dict:
        dur = time.monotonic() - self.t0 if self.t0 else 0.0
        span = (self.last_frame_at - self.first_frame_at) if self.first_frame_at is not None else 0.0
        avg = self.frames / span if span > 0 else 0.0
        if self.auth_status == "PENDING":
            self.auth_status = "AUTH NO RESPONSE"
        self.log("==== SUMMARY ====")
        self.log(f"stop reason: {self.stop_reason}")
        self.log(f"duration: {dur:.1f} s")
        self.log(f"auth: {self.auth_status}")
        self.log("received messages:")
        for (chan, mtype), n in sorted(self.counts.items()):
            tag = f"0x{mtype:08X} {msg_name(mtype)}" if mtype >= 0 else "short"
            self.log(f"  {CH_NAMES.get(chan, chan)} {tag} x{n}")
        self.log(f"video: {self.frames} frames, {self.vbytes} B, avg {avg:.1f} fps")
        return {"duration": dur, "auth": self.auth_status, "counts": dict(self.counts), "frames": self.frames,
                "video_bytes": self.vbytes, "avg_fps": avg, "stop_reason": self.stop_reason}


def parse_args(argv=None) -> argparse.Namespace:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--host", default="127.0.0.1")
    p.add_argument("--port", type=int, default=7240)
    p.add_argument("--duration", type=float, default=120.0, metavar="N",
                   help="stop N seconds after the bridge connects (default 120)")
    p.add_argument("--no-auth", action="store_true", help="do not send HU_AUTHEN_REQUEST")
    p.add_argument("--touch-test", action="store_true",
                   help="15 s after the first video frame send a tap (DOWN+UP) at the centre")
    p.add_argument("--out", type=Path, default=HERE / "out", help="output directory (default pc-headunit/out)")
    return p.parse_args(argv)


def main(argv=None) -> int:
    a = parse_args(argv)
    hu = HeadUnit(Options(a.host, a.port, a.duration, a.no_auth, a.touch_test, a.out))
    hu.run()
    return 0


if __name__ == "__main__":
    sys.exit(main())
