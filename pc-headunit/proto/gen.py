"""Regenerates *_pb2.py from the .proto files next to this script.

    pip install protobuf grpcio-tools==1.67.1
    python proto/gen.py

protoc emits flat imports (`import common_pb2`); they are rewritten to package-relative ones so the
modules import as proto.common_pb2 etc. The .proto files are copies of
headunit-revived (https://github.com/andreknieriem/headunit-revived, app/src/main/proto; input.proto: MsgType renamed to InputMsgType).
"""
import re
import sys
from pathlib import Path

from grpc_tools import protoc

HERE = Path(__file__).resolve().parent
NAMES = ["common", "control", "media", "input", "sensors"]


def main() -> int:
    args = ["protoc", f"-I{HERE}", f"--python_out={HERE}"] + [str(HERE / f"{n}.proto") for n in NAMES]
    rc = protoc.main(args)
    if rc != 0:
        return rc
    for n in NAMES:
        path = HERE / f"{n}_pb2.py"
        text = path.read_text(encoding="utf-8")
        text = re.sub(r"^import (\w+_pb2) as", r"from . import \1 as", text, flags=re.M)
        path.write_text(text, encoding="utf-8")
    print("generated:", ", ".join(f"{n}_pb2.py" for n in NAMES))
    return 0


if __name__ == "__main__":
    sys.exit(main())
