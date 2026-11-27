#!/usr/bin/env python3
"""Strip the Wasm `name` custom section from a built client for deployment.

TeaVM emits a name section containing every Java method name. It is useful for
crash stacks and CPU profiles, but it is also roughly half of the 112 MB
module and is never executed. Chrome validates and compiles custom sections
away, so the stripped module is byte-for-byte equivalent at runtime.

Keep the unstripped module in CI artifacts; deploy the stripped one. Pass
--keep-debug to write both files (the original gets a .debug.wasm suffix).
"""
import argparse
import sys
from pathlib import Path


def leb128(value: int) -> bytes:
    out = bytearray()
    while True:
        byte = value & 0x7F
        value >>= 7
        out.append(byte | (0x80 if value else 0))
        if not value:
            return bytes(out)


def read_leb(data: bytes, pos: int) -> tuple[int, int]:
    value = 0
    shift = 0
    while True:
        if pos >= len(data):
            raise ValueError("truncated LEB128")
        byte = data[pos]
        pos += 1
        value |= (byte & 0x7F) << shift
        shift += 7
        if not byte & 0x80:
            return value, pos


def strip_name_section(data: bytes) -> tuple[bytes, int, int]:
    if data[:8] != b"\x00asm\x01\x00\x00\x00":
        raise ValueError("not a WebAssembly 1.0 module")
    out = bytearray(data[:8])
    pos = 8
    dropped = 0
    while pos < len(data):
        section_id = data[pos]
        pos += 1
        size, body_start = read_leb(data, pos)
        body_end = body_start + size
        if body_end > len(data):
            raise ValueError("truncated section at byte %d" % pos)
        body = data[body_start:body_end]
        if section_id == 0:
            name_len, name_start = read_leb(body, 0)
            name = body[name_start:name_start + name_len].decode("utf-8", "replace")
            if name == "name":
                dropped += size
                pos = body_end
                continue
        out.append(section_id)
        out += leb128(size)
        out += body
        pos = body_end
    return bytes(out), dropped, len(out)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--keep-debug", action="store_true",
                        help="also keep the unstripped module next to the output as .debug.wasm")
    args = parser.parse_args()

    original = args.input.read_bytes()
    stripped, dropped, final_size = strip_name_section(original)
    if args.keep_debug:
        debug = args.output.with_suffix(".debug.wasm")
        debug.write_bytes(original)
        print("kept debug module -> %s" % debug)
    args.output.write_bytes(stripped)
    print("stripped %s: %d -> %d bytes (removed %d bytes of name metadata)"
          % (args.input, len(original), final_size, dropped))
    return 0


if __name__ == "__main__":
    sys.exit(main())
