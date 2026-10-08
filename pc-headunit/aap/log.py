"""Console + session.log sink. The file gets every line; the console throttles bulk media traffic."""
from __future__ import annotations

import sys
import time
from pathlib import Path
from typing import Dict, Optional, TextIO, Tuple

from . import messages as M


class SessionLog:
    CONSOLE_FIRST = 3          # media lines shown in full per (direction, channel, type) ...
    CONSOLE_EVERY = 100        # ... then one per this many

    def __init__(self, path: Optional[Path], console: bool = True, verbose_media: bool = False,
                 stream: Optional[TextIO] = None) -> None:
        self._file: Optional[TextIO] = open(path, "w", encoding="utf-8", buffering=1) if path else None
        self._console = console
        self._verbose_media = verbose_media
        self._stream = stream or sys.stdout
        self._counts: Dict[Tuple[str, int, int], int] = {}
        self._t0 = time.time()

    def close(self) -> None:
        if self._file:
            self._file.close()
            self._file = None

    def _stamp(self) -> str:
        now = time.time()
        return time.strftime("%H:%M:%S", time.localtime(now)) + f".{int((now % 1) * 1000):03d}"

    def _emit(self, line: str, console: bool = True) -> None:
        full = f"{self._stamp()} {line}"
        if self._file:
            self._file.write(full + "\n")
        if self._console and console:
            try:
                self._stream.write(full + "\n")
                self._stream.flush()
            except UnicodeEncodeError:
                self._stream.write(full.encode("ascii", "replace").decode("ascii") + "\n")

    def info(self, text: str) -> None:
        self._emit(f"-- {text}")

    def warn(self, text: str) -> None:
        self._emit(f"!! {text}")

    def msg(self, direction: str, chan: int, msg_type: int, length: int, text: str = "",
            media: bool = False, note: str = "") -> None:
        """direction: "TX" or "RX". length = message body bytes (after the 2-byte type)."""
        name = M.message_name(chan, msg_type)
        line = (f"{direction} {M.channel_name(chan)}({chan}) {name} 0x{msg_type:04x} len={length}"
                + (f" {note}" if note else "") + (f" | {text}" if text else ""))
        show = True
        if media and not self._verbose_media:
            key = (direction, chan, msg_type)
            n = self._counts.get(key, 0) + 1
            self._counts[key] = n
            show = n <= self.CONSOLE_FIRST or n % self.CONSOLE_EVERY == 0
            if show and n > self.CONSOLE_FIRST:
                line += f"  [#{n}, console shows every {self.CONSOLE_EVERY}th, all in session.log]"
        self._emit(line, console=show)
