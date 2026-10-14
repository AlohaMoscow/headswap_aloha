"""Offline tests for the CarLife HU emulator: no phone, local loopback only.

    python -m pytest pc-headunit/tests/test_carlife_hu.py
"""
from __future__ import annotations

import socket
import sys
import tempfile
import threading
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

import carlife_hu as C                                           # noqa: E402


def test_outer_inner_roundtrip():
    inner = C.cmd_inner(0x00010004, b"\x08\x01")
    assert inner == bytes.fromhex("0002 0000 00010004 0801".replace(" ", ""))
    frame = C.outer(C.CH_CMD, inner)
    assert frame[:8] == (1).to_bytes(4, "big") + len(inner).to_bytes(4, "big")
    # разбор кусками по 1 байту
    p = C.FrameParser()
    got = []
    for b in frame + frame:
        got += p.feed(bytes([b]))
    assert got == [(C.CH_CMD, inner), (C.CH_CMD, inner)]
    assert C.parse_inner(C.CH_CMD, inner) == (0x00010004, b"\x08\x01", False)

    d = C.data_inner(C.VIDEO_DATA, b"abc", 7)
    assert d == bytes.fromhex("00000003 00000007 00020001".replace(" ", "")) + b"abc"
    assert C.parse_inner(C.CH_VIDEO, d) == (C.VIDEO_DATA, b"abc", False)
    assert C.build_heartbeat() == bytes.fromhex("000000000000000000020002")
    assert C.build_protocol_version() == bytes.fromhex("000400000001800108041002")


def test_auth_request_bytes_match_log():
    inner = C.build_auth_request("2.0;2026-10-14-12-01-33")
    want = bytes.fromhex("00 19 00 00 00 01 80 48 0A 17 32 2E 30 3B 32 30 32 36 2D 31 30 2D 31 34 2D 31 32 2D 30 31 2D 33 33")
    assert inner == want
    r = C.make_random_value()
    assert r.startswith("2.0;") and len(r) == len("2.0;2026-10-14-12-01-33")


def test_md5_expected():
    assert C.auth_expected("HUAWEI", "ADY-LX9", "31", "2026-10-14-12-01-33") == "2fce147802726d1aeae532116434339d"


def test_pb_roundtrip():
    body = C.pb_str(4, "HUAWEI") + C.pb_int(21, 31)
    f = C.pb_parse(body)
    assert C.pb_get_str(f, 4) == "HUAWEI" and C.pb_get_int(f, 21) == 31
    assert C.pb_parse(b"\x0a\x05ab") is None


# ---- локальная петля: сервер в потоке, тест играет мост ----

class FakeBridge:
    def __init__(self, port: int):
        self.s = socket.create_connection(("127.0.0.1", port), timeout=5)
        self.parser = C.FrameParser()
        self.msgs: list[tuple[int, int, bytes]] = []        # (chan, type, body)

    def send_cmd(self, mtype: int, body: bytes = b""):
        self.s.sendall(C.outer(C.CH_CMD, C.cmd_inner(mtype, body)))

    def pump(self, until, timeout: float = 5.0):
        """Читать кадры, пока until(msgs) не станет истиной."""
        self.s.settimeout(timeout)
        while not until(self.msgs):
            data = self.s.recv(65536)
            if not data:
                break
            for chan, inner in self.parser.feed(data):
                mtype, body, _ = C.parse_inner(chan, inner)
                self.msgs.append((chan, mtype, body))

    def types(self, chan: int = C.CH_CMD):
        return [t for c, t, _ in self.msgs if c == chan]


def start_hu(duration: float, **kw):
    out = Path(tempfile.mkdtemp(prefix="carlife_hu_test_"))
    hu = C.HeadUnit(C.Options(port=0, duration=duration, out=out, quiet=True, **kw))
    hu.listen()
    res: dict = {}
    t = threading.Thread(target=lambda: res.update(hu.run()), daemon=True)
    t.start()
    return hu, t, res, out


MD_INFO_BODY = C.pb_str(1, "Android") + C.pb_str(4, "HUAWEI") + C.pb_str(14, "ADY-LX9") + C.pb_str(20, "31") + C.pb_int(21, 31)
BURST = [C.STATISTIC_INFO, C.HU_INFO, C.HU_AUTHEN_REQUEST, C.HU_UNKNOWN_1804C, C.VIDEO_ENCODER_INIT,
         C.HU_FEATURE_CONFIG_RESPONSE, C.VIDEO_ENCODER_START]


def test_loopback_auth_ok():
    hu, t, res, out = start_hu(2.5)
    b = FakeBridge(hu.port)
    b.pump(lambda m: len(m) >= 1)
    assert b.types() == [C.HU_PROTOCOL_VERSION]
    b.send_cmd(C.MD_INFO, MD_INFO_BODY)
    b.pump(lambda m: C.HU_AUTHEN_REQUEST in [x[1] for x in m])
    assert b.types()[:4] == [C.HU_PROTOCOL_VERSION] + BURST[:3]
    rnd = C.pb_get_str(C.pb_parse([x[2] for x in b.msgs if x[1] == C.HU_AUTHEN_REQUEST][0]), 1)
    assert rnd.startswith("2.0;")
    enc = C.auth_expected("HUAWEI", "ADY-LX9", "31", rnd.split(";", 1)[1])
    b.send_cmd(C.MD_AUTHEN_RESPONSE, C.pb_str(1, enc))
    b.pump(lambda m: C.HU_AUTHEN_RESULT in [x[1] for x in m])
    result = [x[2] for x in b.msgs if x[1] == C.HU_AUTHEN_RESULT][0]
    assert result == C.pb_int(1, 1)
    # видео: два кадра
    b.s.sendall(C.outer(C.CH_VIDEO, C.data_inner(C.VIDEO_DATA, b"\x00\x00\x00\x01\x67" + b"x" * 10)))
    b.s.sendall(C.outer(C.CH_VIDEO, C.data_inner(C.VIDEO_DATA, b"\x00\x00\x00\x01\x41" + b"y" * 5)))
    b.pump(lambda m: C.VIDEO_HEARTBEAT in [x[1] for x in m if x[0] == C.CH_VIDEO])
    t.join(10)
    b.s.close()
    assert not t.is_alive()
    assert b.types() == [C.HU_PROTOCOL_VERSION] + BURST + [C.HU_AUTHEN_RESULT]
    assert res["auth"] == "AUTH OK"
    assert res["frames"] == 2 and res["video_bytes"] == 15 + 10
    assert res["counts"][(C.CH_CMD, C.MD_INFO)] == 1
    assert (out / "carlife_video.h264").read_bytes().startswith(b"\x00\x00\x00\x01\x67")
    assert "AUTH OK" in (out / "carlife_hu.log").read_text(encoding="utf-8")


def test_loopback_auth_fail():
    hu, t, res, _ = start_hu(2.0)
    b = FakeBridge(hu.port)
    b.send_cmd(C.MD_INFO, MD_INFO_BODY)
    b.pump(lambda m: C.HU_AUTHEN_REQUEST in [x[1] for x in m])
    b.send_cmd(C.MD_AUTHEN_RESPONSE, C.pb_str(1, "0" * 32))
    b.pump(lambda m: C.HU_AUTHEN_RESULT in [x[1] for x in m])
    assert [x[2] for x in b.msgs if x[1] == C.HU_AUTHEN_RESULT][0] == C.pb_int(1, 0)
    t.join(10)
    b.s.close()
    assert res["auth"] == "AUTH FAIL"


def test_loopback_no_auth_flag():
    hu, t, res, _ = start_hu(1.5, no_auth=True)
    b = FakeBridge(hu.port)
    b.send_cmd(C.MD_INFO, MD_INFO_BODY)
    b.pump(lambda m: C.VIDEO_ENCODER_START in [x[1] for x in m])
    t.join(10)
    b.s.close()
    assert C.HU_AUTHEN_REQUEST not in b.types()
    assert res["auth"] == "NOT SENT"
