"""TLS client over memory BIOs: the TLS records travel inside AAP frames, not on the socket.

headunit-revived (AapSslContext.kt): SSLEngine in client mode, peer certificate not checked, the
HU's own certificate sent when asked, engine created with host "android-auto" (it ends up as SNI).
Here: SSLObject over two MemoryBIO. wrap -> bytes for one AAP frame body, unwrap <- one frame body.
"""
from __future__ import annotations

import ssl
from pathlib import Path
from typing import List, Tuple

CERT_SETS = ("jvc", "hur")
SNI_NAME = "android-auto"


def make_context(cert_pem: Path, key_pem: Path, tls: str = "auto") -> ssl.SSLContext:
    """tls: "auto" = whatever OpenSSL offers (1.2 and 1.3, like JSSE/Conscrypt "TLS"), "1.2" caps it."""
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    # Python 3.10+ runs OpenSSL at security level 2 by default; the Android side has no such floor,
    # and the peer certificate is not checked anyway.
    ctx.set_ciphers("DEFAULT:@SECLEVEL=0")
    if tls == "1.2":
        ctx.minimum_version = ssl.TLSVersion.TLSv1_2
        ctx.maximum_version = ssl.TLSVersion.TLSv1_2
    elif tls != "auto":
        raise ValueError(f"unknown tls mode {tls!r}")
    ctx.load_cert_chain(str(cert_pem), str(key_pem))
    return ctx


def cert_paths(base: Path, which: str) -> Tuple[Path, Path]:
    if which not in CERT_SETS:
        raise ValueError(f"unknown certificate set {which!r}, expected one of {CERT_SETS}")
    d = base / "certs" / which
    return d / "cert.pem", d / "key.pem"


class TlsClient:
    """One TLS session. Feed it frame bodies, collect frame bodies to send."""

    def __init__(self, ctx: ssl.SSLContext) -> None:
        self._incoming = ssl.MemoryBIO()
        self._outgoing = ssl.MemoryBIO()
        self._obj = ctx.wrap_bio(self._incoming, self._outgoing, server_side=False,
                                 server_hostname=SNI_NAME)
        self.handshake_done = False
        self.closed = False

    # -- handshake ---------------------------------------------------------------------------------
    def handshake_step(self, data: bytes = b"") -> bytes:
        """Feed bytes from the peer (none for the first call), return bytes to send to it.

        After the call `handshake_done` tells whether the handshake finished.
        """
        if data:
            self._incoming.write(data)
        if not self.handshake_done:
            try:
                self._obj.do_handshake()
                self.handshake_done = True
            except ssl.SSLWantReadError:
                pass
        return self._drain()

    # -- application data --------------------------------------------------------------------------
    def encrypt(self, plaintext: bytes) -> bytes:
        """One TLS record (a write up to 16 KiB is one record) for the body of one AAP frame."""
        self._obj.write(plaintext)
        return self._drain()

    def decrypt(self, record: bytes) -> Tuple[bytes, bytes]:
        """Feed one frame body. Returns (application plaintext, bytes the TLS layer wants to send).

        The plaintext may be empty: a record can carry only handshake bytes (TLS 1.3 session
        tickets), which headunit-revived logs as "unwrap produced no application data".
        """
        self._incoming.write(record)
        chunks: List[bytes] = []
        while True:
            try:
                data = self._obj.read(65536)
            except ssl.SSLWantReadError:
                break
            except ssl.SSLZeroReturnError:
                self.closed = True
                break
            if not data:
                break
            chunks.append(data)
        return b"".join(chunks), self._drain()

    # -- info --------------------------------------------------------------------------------------
    def describe(self) -> str:
        cipher = self._obj.cipher()
        return f"{self._obj.version()} {cipher[0] if cipher else '?'} reused={self._obj.session_reused}"

    def _drain(self) -> bytes:
        return self._outgoing.read()
