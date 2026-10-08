"""Minimal Android Auto head unit for the PC: TCP client to the AA "head unit server".

    adb forward tcp:5277 tcp:5277
    python hu.py --duration 30 --cert jvc

Connects to localhost:5277, runs Version -> TLS -> Auth -> service discovery, receives H.264 video
into out/video.h264 and logs every message to the console and out/session.log. The server takes ONE
connection per start and wedges after a dirty drop, so the script ends with ByeBye and an orderly
close (Ctrl+C or --duration), and everything that can fail locally is checked BEFORE connecting.
"""
from __future__ import annotations

import argparse
import signal
import socket
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

from aap import messages as M                                  # noqa: E402
from aap.log import SessionLog                                  # noqa: E402
from aap.session import Options, Session                        # noqa: E402
from aap.tls import CERT_SETS, TlsClient, cert_paths, make_context   # noqa: E402


def parse_args(argv=None) -> argparse.Namespace:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--host", default="127.0.0.1")
    p.add_argument("--port", type=int, default=5277)
    p.add_argument("--cert", choices=CERT_SETS, default="jvc",
                   help="HU certificate: jvc = JVC Kenwood from aasdk, hur = headunit-revived's (default jvc)")
    p.add_argument("--tls", choices=("auto", "1.2"), default="auto",
                   help="auto = TLS 1.2/1.3 as offered by OpenSSL (headunit-revived default); 1.2 caps it")
    p.add_argument("--duration", type=float, default=None, metavar="N",
                   help="stop after N seconds from Auth Complete (default: until Ctrl+C)")
    p.add_argument("--touch-test", action="store_true",
                   help="10 s after the first video frame send a tap (DOWN+UP) at the centre")
    p.add_argument("--out", type=Path, default=HERE / "out", help="output directory (default pc-headunit/out)")
    p.add_argument("--resolution", choices=("800x480", "1280x720", "1920x1080"), default="1280x720")
    p.add_argument("--fps", type=int, choices=(30, 60), default=30)
    p.add_argument("--density", type=int, default=160, help="video density announced to the phone")
    p.add_argument("--moto", action="store_true",
                   help="no MIC service, claim vehicle_type MOTORCYCLE (default: announce MIC, car)")
    p.add_argument("--full-audio", action="store_true",
                   help="also announce SPEECH and MEDIA audio sinks (default: SYSTEM only)")
    p.add_argument("--minimal", action="store_true",
                   help="do not announce MEDIA_PLAYBACK and NAVIGATION services")
    p.add_argument("--max-unacked-video", type=int, default=12)
    p.add_argument("--max-unacked-audio", type=int, default=30)
    p.add_argument("--version-attempts", type=int, default=1,
                   help="how many times to send the Version Request (headunit-revived: up to 3)")
    p.add_argument("--idle-timeout", type=float, default=20.0,
                   help="end the session when the phone is silent this long (it pings ~1/s)")
    p.add_argument("--check", action="store_true",
                   help="run the local preflight only (certificate, TLS ClientHello, service discovery) "
                        "and exit WITHOUT connecting")
    p.add_argument("--verbose-media", action="store_true",
                   help="print every media DATA/Ack line to the console (session.log always has them)")
    return p.parse_args(argv)


def build_options(args: argparse.Namespace) -> Options:
    return Options(
        duration=args.duration,
        touch_test=args.touch_test,
        idle_timeout_s=args.idle_timeout,
        version_attempts=max(1, args.version_attempts),
        max_unacked_video=args.max_unacked_video,
        max_unacked_audio=args.max_unacked_audio,
        sd=dict(resolution=args.resolution, fps=args.fps, density=args.density, mic=not args.moto,
                full_audio=args.full_audio, extra_services=not args.minimal),
    )


def preflight(args: argparse.Namespace, opts: Options):
    """Everything that can fail without the phone: certificate/key, TLS context, ClientHello,
    service discovery serialisation. Returns the SSLContext."""
    cert, key = cert_paths(HERE, args.cert)
    ctx = make_context(cert, key, args.tls)
    hello = TlsClient(ctx).handshake_step()
    if not hello:
        raise RuntimeError("TLS client produced no ClientHello")
    M.service_discovery_response(**opts.sd)
    return ctx, len(hello)


def main(argv=None) -> int:
    args = parse_args(argv)
    opts = build_options(args)
    args.out.mkdir(parents=True, exist_ok=True)
    log = SessionLog(args.out / "session.log", verbose_media=args.verbose_media)
    try:
        ctx, hello_len = preflight(args, opts)
    except Exception as exc:                                    # noqa: BLE001
        log.warn(f"preflight failed, not connecting: {exc!r}")
        log.close()
        return 1
    log.info(f"preflight ok: cert={args.cert} tls={args.tls} clienthello={hello_len}B out={args.out}")
    if args.check:
        log.info("--check: not connecting")
        log.close()
        return 0

    try:
        sock = socket.create_connection((args.host, args.port), timeout=5)
    except OSError as exc:
        log.warn(f"cannot connect to {args.host}:{args.port}: {exc!r} (adb forward tcp:{args.port} tcp:{args.port}?)")
        log.close()
        return 1
    sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_KEEPALIVE, 1)
    log.info(f"connected to {args.host}:{args.port}")

    session = Session(sock, TlsClient(ctx), opts, log, args.out)

    def on_sigint(signum, frame):
        # First Ctrl+C: orderly stop (ByeBye, drain, close). Second: default behaviour.
        session.request_stop("Ctrl+C")
        session.exit_ok = True
        signal.signal(signal.SIGINT, signal.default_int_handler)
        log.info("Ctrl+C: finishing the session cleanly (press again to abort)")

    signal.signal(signal.SIGINT, on_sigint)
    try:
        session.run()
    finally:
        log.close()
    return 0 if session.exit_ok else 2


if __name__ == "__main__":
    sys.exit(main())
