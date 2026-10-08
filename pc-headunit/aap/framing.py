"""AAP frame layer (docs/aa-headunit.md, section 2.1).

    byte 0     channel id
    byte 1     flags: 0x01 FIRST, 0x02 LAST, 0x04 CONTROL, 0x08 ENCRYPTED
    bytes 2-3  body length, big-endian uint16
    [bytes 4-7 total message size, big-endian uint32: only on a FIRST frame that is not also LAST]
    body       after the handshake one TLS record; before it, [type BE16][data]

Reference: headunit-revived AapMessageIncoming.kt / AapReadSingleMessage.kt, aasdk FrameSize.cpp.
"""
from __future__ import annotations

import struct
from dataclasses import dataclass
from typing import Callable, Dict, List, Optional

FLAG_FIRST = 0x01
FLAG_LAST = 0x02
FLAG_CONTROL = 0x04
FLAG_ENCRYPTED = 0x08

FLAGS_PLAIN_BULK = FLAG_FIRST | FLAG_LAST                    # 0x03: version, ssl handshake, auth
FLAGS_ENC_BULK = FLAG_ENCRYPTED | FLAG_FIRST | FLAG_LAST     # 0x0b: ordinary message
FLAGS_ENC_BULK_CONTROL = FLAGS_ENC_BULK | FLAG_CONTROL       # 0x0f: control type on a non-control channel
FLAGS_ENC_FIRST = FLAG_ENCRYPTED | FLAG_FIRST                # 0x09
FLAGS_ENC_MIDDLE = FLAG_ENCRYPTED                            # 0x08
FLAGS_ENC_LAST = FLAG_ENCRYPTED | FLAG_LAST                  # 0x0a

CHANNEL_CONTROL = 0

# One TLS record carries at most 16384 bytes of plaintext; larger messages are split in frames of
# that size (headunit-revived never has to, its outgoing messages are small).
MAX_FRAGMENT = 0x4000

MAX_BODY = 0xFFFF


def has_total_size(flags: int) -> bool:
    """True for a FIRST frame that is not LAST: it carries the 4-byte total message size."""
    return (flags & (FLAG_FIRST | FLAG_LAST)) == FLAG_FIRST


def build_frame(chan: int, flags: int, body: bytes, total: Optional[int] = None) -> bytes:
    """Frame header + body. `total` is written only when the flags say this is a FIRST fragment."""
    if len(body) > MAX_BODY:
        raise ValueError(f"frame body {len(body)} B does not fit the 16-bit length")
    head = struct.pack(">BBH", chan, flags, len(body))
    if has_total_size(flags):
        if total is None:
            raise ValueError("FIRST fragment needs the total message size")
        head += struct.pack(">I", total)
    return head + body


def build_plain(chan: int, msg_type: int, data: bytes = b"", flags: int = FLAGS_PLAIN_BULK) -> bytes:
    """Unencrypted message: [chan][flags][len = 2 + len(data)][type BE16][data]."""
    return build_frame(chan, flags, struct.pack(">H", msg_type) + data)


def control_flag_needed(chan: int, msg_type: int) -> bool:
    """headunit-revived AapMessage.flags(): control types 1..26 on a non-control channel get 0x04."""
    return chan != CHANNEL_CONTROL and 1 <= msg_type <= 26


def message_flags(chan: int, msg_type: int) -> int:
    return FLAGS_ENC_BULK_CONTROL if control_flag_needed(chan, msg_type) else FLAGS_ENC_BULK


def split_message(plaintext: bytes, chan: int, msg_type: int, max_fragment: int = MAX_FRAGMENT):
    """Yield (flags, chunk, total) for the frames of one message.

    A message that fits is one bulk frame (total None). A larger one becomes FIRST (0x09, carries
    the total size), MIDDLE (0x08)* and LAST (0x0a). Every chunk is encrypted on its own by the
    caller, in order: the TLS record sequence must match the frame sequence.
    """
    if len(plaintext) <= max_fragment:
        yield message_flags(chan, msg_type), plaintext, None
        return
    total = len(plaintext)
    pos = 0
    while pos < total:
        chunk = plaintext[pos:pos + max_fragment]
        pos += len(chunk)
        if pos == len(chunk):
            yield FLAGS_ENC_FIRST, chunk, total
        elif pos >= total:
            yield FLAGS_ENC_LAST, chunk, None
        else:
            yield FLAGS_ENC_MIDDLE, chunk, None


@dataclass
class Frame:
    chan: int
    flags: int
    body: bytes
    total: Optional[int] = None      # only on FIRST (not LAST) frames

    @property
    def encrypted(self) -> bool:
        return bool(self.flags & FLAG_ENCRYPTED)


class FrameParser:
    """Incremental byte stream -> Frame list. TCP gives no frame boundaries, so feed() any slice."""

    def __init__(self) -> None:
        self._buf = bytearray()

    def feed(self, data: bytes) -> List[Frame]:
        self._buf += data
        frames: List[Frame] = []
        while True:
            buf = self._buf
            if len(buf) < 4:
                break
            chan, flags, length = struct.unpack_from(">BBH", buf, 0)
            head = 8 if has_total_size(flags) else 4
            if len(buf) < head + length:
                break
            total = struct.unpack_from(">I", buf, 4)[0] if head == 8 else None
            frames.append(Frame(chan, flags, bytes(buf[head:head + length]), total))
            del buf[:head + length]
        return frames

    @property
    def pending(self) -> int:
        return len(self._buf)


class Reassembler:
    """Joins the decrypted bodies of FIRST/MIDDLE/LAST frames into whole messages, per channel.

    push() returns the complete plaintext ([type BE16][payload]) or None while a run is open. A
    run that is out of order or whose size disagrees with the declared total is reported through
    `warn` and the data is still returned/dropped in the least surprising way: a stray MIDDLE/LAST
    is dropped, a FIRST that interrupts an open run replaces it, a size mismatch is returned as is.
    """

    def __init__(self, warn: Optional[Callable[[str], None]] = None) -> None:
        self._runs: Dict[int, bytearray] = {}
        self._totals: Dict[int, Optional[int]] = {}
        self._warn = warn or (lambda _msg: None)

    def push(self, chan: int, flags: int, plaintext: bytes, total: Optional[int] = None) -> Optional[bytes]:
        first = bool(flags & FLAG_FIRST)
        last = bool(flags & FLAG_LAST)
        if first and last:
            if chan in self._runs:
                self._warn(f"ch{chan}: bulk frame while a fragment run was open, run dropped")
                self._drop(chan)
            return plaintext
        if first:
            if chan in self._runs:
                self._warn(f"ch{chan}: FIRST while a fragment run was open, old run dropped")
            self._runs[chan] = bytearray(plaintext)
            self._totals[chan] = total
            return None
        run = self._runs.get(chan)
        if run is None:
            self._warn(f"ch{chan}: {'LAST' if last else 'MIDDLE'} fragment without FIRST, dropped")
            return None
        run += plaintext
        if not last:
            return None
        declared = self._totals.get(chan)
        self._drop(chan)
        if declared is not None and declared != len(run):
            self._warn(f"ch{chan}: reassembled {len(run)} B but FIRST declared {declared} B")
        return bytes(run)

    def _drop(self, chan: int) -> None:
        self._runs.pop(chan, None)
        self._totals.pop(chan, None)

    def open_runs(self) -> List[int]:
        return sorted(self._runs)
