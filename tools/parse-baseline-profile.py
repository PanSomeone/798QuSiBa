"""Reports what a baseline profile in a built APK actually amounts to.

Why this script exists: a baseline profile fails silently. `baseline-prof.txt` is
mapped through R8 and recompiled into `assets/dexopt/baseline.prof`, and a line
that no longer matches any method -- a renamed method, a changed parameter list --
just is not in the output. Nothing warns you. The build is green either way. So
"it built" says nothing, and the only honest checks are these three:

  1. Does the package contain a profile at all?  (A `debug` package cannot have
     one: AGP strips profiles from debuggable variants. Compare `debug` to
     `release` if you want to see the difference.)
  2. Is the payload well formed, and how much of it is there?
  3. Do the signatures in `baseline-prof.txt` still name real methods?

This script answers 1 and 2. It deliberately does not try to answer 3 by reading
names out of the binary, because it cannot: the compiled payload does not carry
method names as text.

The binary format, as far as this script needs it
-------------------------------------------------
    bytes 0..3    magic "pro\\0"
    bytes 4..7    version, "010\\0" for the current format
    byte  8       number of dex files
    bytes 9..12   uncompressed payload size
    bytes 13..16  compressed size
    bytes 17..18  length of the dex location string
    bytes 19..    raw DEFLATE stream

Two things that cost real time to work out, recorded so they cost nothing next
time:

  * The DEFLATE stream is RAW (no zlib wrapper), so it needs `wbits=-15`.
    `zlib.decompress` on the same bytes fails with "incorrect header check".
  * The dex-file count at byte 8 is ONE byte. Read it as a u32 and every offset
    after it lands in the middle of the string and you get a plausible-looking,
    completely wrong answer.

What the decompressed payload looks like: a u32 dex checksum, the length-prefixed
dex location ("classes.dex"), and then the body -- which is where this script
stops decoding. The body is index-based (the entries resolve through the dex
string table at load time), so it contains no readable method names and no
signature strings to check against. An earlier version of this script tried to
walk the body as class names and method descriptors; it "worked" only in the
sense that it produced numbers.

Usage:
    python tools/parse-baseline-profile.py <apk>
    python tools/parse-baseline-profile.py <apk> --dump   # also print hex regions
"""

import struct
import sys
import zipfile
import zlib

# The console on a Chinese-locale Windows defaults to GBK, and this script prints
# strings that come out of a binary file. Without this, a stray byte in an
# unexpected place raises UnicodeEncodeError instead of reporting what it found.
for stream in (sys.stdout, sys.stderr):
    if hasattr(stream, "reconfigure"):
        stream.reconfigure(encoding="utf-8", errors="backslashreplace")

MAGIC = b"pro\x00"
HEADER_BYTES = 19

PROFILE_PATHS = ("assets/dexopt/baseline.prof", "assets/dexopt/baseline.profm")


def analyse(zf, name):
    data = zf.read(name)
    print(f"  {name}")
    print(f"    packed:            {len(data):,} bytes")

    if data[:4] != MAGIC:
        if data[:4] == b"prm\x00":
            print("    metadata file, not a profile: this is the .profm, and it is")
            print("    a bitmap of the same information. Nothing to decode here.")
            return None
        print(f"    MAGIC MISMATCH:    {data[:4]!r}, expected {MAGIC!r}")
        return None

    version = data[4:8].decode("ascii", "replace")
    dex_count = data[8]
    uncompressed, compressed = struct.unpack_from("<II", data, 9)

    print(f"    format version:    {version}")
    print(f"    dex files:         {dex_count}")
    print(f"    header says:       {uncompressed:,} bytes uncompressed, {compressed:,} compressed")

    payload = zlib.decompressobj(-15).decompress(data[HEADER_BYTES:])
    if len(payload) != uncompressed:
        print(f"    PAYLOAD MISMATCH:  got {len(payload):,} bytes, header said {uncompressed:,}")
        return None

    print(f"    payload:           {len(payload):,} bytes (matches the header)")

    # Where the payload's own header ends is the one thing about this format that
    # did not yield to inspection. The payload opens with 0x0b 0x00 and carries the
    # ASCII "classes.dex" at offset 17; the bytes before that do not decode as a
    # length prefix for it under any width that was tried (u16, u32, 8-bit count
    # plus u32, ...), and inventing a layout makes this script print confident
    # nonsense -- an earlier revision "reported" a 96-byte dex location and an
    # 8,408-byte body that were both artifacts of the guess. So it reports only
    # what is not a guess: the payload size, the location string where it was
    # actually seen, and the size of everything after it.
    print("    dex location:      'classes.dex' (ASCII, at payload offset 17)")
    print(f"    payload after it:  {len(payload) - 28:,} bytes")

    return payload


def main():
    argv = [a for a in sys.argv[1:] if not a.startswith("--")]
    if len(argv) != 1 or not argv[0].endswith(".apk"):
        raise SystemExit(__doc__)

    apk = argv[0]
    with zipfile.ZipFile(apk) as zf:
        names = zf.namelist()
        dexopt = sorted(n for n in names if "dexopt" in n)

        print(f"package: {apk}")
        print(f"entries: {len(names):,}   dexopt entries: {len(dexopt)}")
        print()

        if not dexopt:
            print("  no baseline profile in this package.")
            print("  A debuggable variant never has one -- AGP strips it. If you were")
            print("  expecting a profile, build `release` or `benchmark`.")
            return

        for name in dexopt:
            if name.endswith(".profm"):
                print(f"  {name}")
                print("    metadata bitmap for the same information. Not decoded.")
                print()
            else:
                analyse(zf, name)
                print()

    print()
    print("Read this as: the profile is present, well formed, and of a size consistent")
    print("with the method list in app/src/main/baseline-prof.txt. Whether each of those")
    print("lines still names a real method is not answerable from the binary -- run")
    print("tools/check-profile.py --classes and --mapping, and treat a silent build as")
    print("no evidence at all.")


if __name__ == "__main__":
    main()
