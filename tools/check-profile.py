"""Check the baseline profile against something that proves its lines are real.

A profile line whose descriptor matches no method is not an error anybody sees:
dexopt drops it, and R8 drops it too, so a mistyped line costs nothing visible
and buys nothing. That makes "does this line point at a method that exists" the
only question worth asking, and there are two moments to ask it.

    py tools/check-profile.py --classes
        Against `app/build/intermediates/compile_app_classes_jar/debug/.../
        classes.jar` -- the Kotlin compiler's own output, original names, no R8
        pass over it. This is the check to run right after editing the profile,
        and it is fast.

    py tools/check-profile.py --mapping
        Against `app/build/outputs/mapping/benchmark/mapping.txt` -- R8's record
        of what it was handed and what it kept. This is the check that proves the
        lines survived shrinking and obfuscation on the way to the shipped APK,
        so it needs `:app:assembleBenchmark` first.

Both modes compare the member name and the parameter count, and both fail loudly
on a missing input rather than passing an unverified file.

Why the class files and not the source: a source-level check has to model
everything Kotlin and Compose add on the way to bytecode -- the `Composer`, the
changed-flags `int`s, a default mask, inline-value-class name mangling. Every one
of those is a guess, and a wrong guess is invisible here. The class files need no
model: they are the answer.
"""

import argparse
import os
import re
import struct
import sys
import zipfile

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_PROFILE = os.path.join(REPO_ROOT, "app", "src", "main", "baseline-prof.txt")
DEFAULT_JAR = os.path.join(
    REPO_ROOT,
    "app",
    "build",
    "intermediates",
    "compile_app_classes_jar",
    "debug",
    "bundleDebugClassesToCompileJar",
    "classes.jar",
)
DEFAULT_MAPPING = os.path.join(
    REPO_ROOT, "app", "build", "outputs", "mapping", "benchmark", "mapping.txt"
)

# `HSPLcom/example/Foo;->bar(Ljava/lang/String;I)V`: the leading letters are access
# flags in the dex generic-profile format, and `->` is fixed punctuation.
PROFILE_ENTRY = re.compile(r"^(?P<flags>[A-Z]*)(?P<owner>L[^;]+;)\.?->?(?P<member>.+)$")
MEMBER = re.compile(r"^(?P<name><[^>]+>|[\w$<>-]+)\((?P<params>.*)\)(?P<returns>.+)$")

PRIMITIVES = set("VZBCSIJFD")

# Constant-pool tags that are not Utf8 (1). Only the lengths matter: they are what
# lets the walk land on each entry's start. Tags 5 and 6 hold two slots.
CONSTANT_SIZES = {3: 4, 4: 4, 5: 8, 6: 8, 7: 2, 8: 2, 9: 4, 10: 4, 11: 4, 12: 4,
                  15: 3, 16: 2, 17: 4, 18: 4, 19: 2, 20: 2}

# `    12:34:void com.example.Foo.bar(int,java.lang.String):12:12 -> a`
#
# The owner is optional because constructors and static initialisers have none:
# a compiler-generated constructor arrives as `void <init>():62:62`, with no
# `com.example.Foo.` in front of the name. Demanding a dotted prefix is what made
# this regex match nothing but ordinary methods.
#
# The trailing line range is captured because it is the only thing that separates
# a declaration from an inline frame, and the two look identical otherwise:
#
#     void Foo.bar(int):12:12 -> bar     a declaration; 12:12 is its body
#     void Foo.bar(int):0:0   -> bar     an inline frame; 0:0 means "no body here"
#
# R8 emits both, and only the first says the method still exists as a method. An
# inline frame says a *call* to it was compiled somewhere else, so that class may
# have no such member at all -- `SharedPreferencesPreferencesStore
# .getSelectedDeviceId()` appears inside a coroutine state machine for exactly this
# reason, and the (int) in it is the call site's view of the callee, not a second
# overload.
MAPPING_MEMBER = re.compile(
    r"^\s+\d+:\d+:(?P<returns>\S+) (?:(?P<owner>[\w.$]+)\.)?(?P<name>[\w$<>]+)"
    r"\((?P<params>[^)]*)\):(?P<origin>\d+):(?P<target>\d+)"
)
MAPPING_CLASS = re.compile(r"^(?P<name>[\w.$]+) -> \S+:")


def arity(params):
    """Count the parameters in a descriptor's parameter list."""
    count, index = 0, 0
    while index < len(params):
        if params[index] == "[":
            while params[index] == "[":
                index += 1
        if params[index] == "L":
            end = params.find(";", index)
            if end == -1:
                return None
            index = end + 1
        elif params[index] in PRIMITIVES:
            index += 1
        else:
            return None
        count += 1
    return count


def parse_profile(path):
    entries = []
    with open(path, encoding="utf-8") as handle:
        for number, line in enumerate(handle, start=1):
            stripped = line.strip()
            if not stripped or stripped.startswith("#"):
                continue
            match = PROFILE_ENTRY.match(stripped)
            member = MEMBER.match(match.group("member")) if match else None
            if not member:
                entries.append((number, stripped, None, None, None, "unparseable"))
                continue
            count = arity(member.group("params").strip())
            owner = match.group("owner").strip("L;").replace("/", ".")
            entries.append((number, stripped, owner, member.group("name"), count, None))
    return entries


def class_methods(class_bytes):
    """{method name: {parameter counts}} read from a class file's method table.

    Read from the class's own `methods` table rather than from the constant pool:
    the pool holds a name/descriptor pair only when *something references it as a
    member*, so a private method called from nowhere else is in the class file and
    absent from the pool.
    """
    if class_bytes[:4] != b"\xca\xfe\xba\xbe":
        return {}

    pool = {}
    offset = 10
    index = 1
    count = struct.unpack_from(">H", class_bytes, 8)[0]
    while index < count:
        tag = class_bytes[offset]
        offset += 1
        if tag == 1:  # Utf8
            length = struct.unpack_from(">H", class_bytes, offset)[0]
            pool[index] = class_bytes[offset + 2 : offset + 2 + length].decode("utf-8", "replace")
            offset += 2 + length
        elif tag in CONSTANT_SIZES:
            offset += CONSTANT_SIZES[tag]
        else:
            return {}
        index += 2 if tag in (5, 6) else 1

    # access_flags, this_class, super_class, then a counted interfaces table, then
    # fields, then methods. A member is access_flags, name_index, descriptor_index,
    # then counted attributes of `name_index, length, bytes`.
    offset += 6
    interfaces = struct.unpack_from(">H", class_bytes, offset)[0]
    offset += 2 + 2 * interfaces

    methods = {}
    for table in range(2):
        members = struct.unpack_from(">H", class_bytes, offset)[0]
        offset += 2
        for _ in range(members):
            _, name_index, descriptor_index, attributes = struct.unpack_from(
                ">HHHH", class_bytes, offset
            )
            offset += 8
            name = pool.get(name_index)
            descriptor = pool.get(descriptor_index)
            if table == 1 and name and descriptor and descriptor.startswith("("):
                methods.setdefault(name, set()).add(
                    arity(descriptor[1 : descriptor.index(")")])
                )
            for _ in range(attributes):
                length = struct.unpack_from(">I", class_bytes, offset + 2)[0]
                offset += 6 + length
    return methods


def read_mapping(path):
    """{original class name: {method name: {"declared": counts, "inlined": counts}}}.

    Ownership comes from the member's own name when it has one, never from the class
    block it sits in: R8 writes a member into the block of whatever class the
    optimizer moved it into. Only constructors and static initialisers, which carry
    no owner prefix, are filed against the open block -- a `void <init>()` line
    cannot say whose it is any other way.

    `declared` and `inlined` are kept apart because they answer different questions.
    A declaration is the method still existing; an inline frame is a call to the
    method that was compiled into someone else, and its parameter list describes the
    call site, not a second overload. Falling back to an inline frame when there is
    no declaration is deliberate: a member that was inlined everywhere and then
    removed still tells the profile author that the entry does nothing, and
    reporting it as "no such method" sends them looking for a spelling mistake that
    is not there.
    """
    classes = {}
    owner = None

    def record(where, name, count, declared):
        entry = classes.setdefault(where, {}).setdefault(
            name, {"declared": set(), "inlined": set()}
        )
        entry["declared" if declared else "inlined"].add(count)

    with open(path, encoding="utf-8") as handle:
        for line in handle:
            if not line.startswith(" "):
                # A class header is the only unindented line that changes which
                # class the following members belong to. Comments between the
                # header and its members are unindented too, so clearing `owner`
                # on every unindented line would drop the first class of every
                # comment-separated run -- which is how the file starts.
                header = MAPPING_CLASS.match(line)
                if header:
                    owner = header.group("name").replace("$", ".")
                    classes.setdefault(owner, {})
                continue
            member = MAPPING_MEMBER.match(line)
            if not member:
                continue
            params = member.group("params").strip()
            count = 0 if not params else params.count(",") + 1
            # 0:0 on both sides is the one signature only a real method has.
            declared = member.group("origin") == "0" and member.group("target") == "0"
            named_owner = member.group("owner")
            name = member.group("name")
            if named_owner:
                key = named_owner.replace("$", ".")
                record(key, name, count, declared)
                if "." in key:
                    # Reachable by simple name too, so an entry that names only the
                    # class can still find it.
                    record(key.rsplit(".", 1)[-1], name, count, declared)
            elif owner is not None:
                record(owner, name, count, declared)
    return classes


def _owners_for(table, owner, name):
    """Every {method: counts} map that `owner` here could be standing for.

    Two spellings of the same class exist in R8's output. A Kotlin file-level
    function lives in `FooKt`, but the profile may name the file after the
    construct it declares, and R8 reports the *shorter* name for the class the
    compiler inlined into. `MainActivity` is the case in point: R8 keeps the
    activity, and folds the file's other top-level functions into it, so a
    `MainActivityKt` entry can only be satisfied there.
    """
    found = [
        members
        for candidate, members in table.items()
        if candidate == owner or candidate.startswith(owner + ".")
    ]
    if not found:
        found = [
            members
            for candidate, members in table.items()
            if candidate.rsplit(".", 1)[-1] == owner.rsplit(".", 1)[-1]
        ]
    return found


def lookup(table, owner, name):
    """Parameter counts for `owner.name`: declared if it exists, else inlined.

    A property whose type is an inline value class gets a mangled JVM name:
    `val Slate = Color(...)` compiles to `getSlate-0d7_KjU()J`, because the class's
    own type is erased into the descriptor. The profile names the readable half, so
    a dash-suffixed name counts as a match.
    """
    for methods in _owners_for(table, owner, name):
        counts = _counts(methods, name)
        if counts:
            return counts
    return None


def _counts(methods, name):
    entry = methods.get(name)
    if entry is None:
        for candidate, value in methods.items():
            if candidate.startswith(name + "-"):
                entry = value
                break
    if entry is None:
        return None
    if isinstance(entry, set):
        # The compiler's class files have no R8 renaming and no merged owners, so
        # there is only ever one spelling and nothing to disambiguate.
        return entry
    # A declaration beats an inline frame: it is the method itself. Where there is
    # none, the inline frame still proves the entry can never match anything, which
    # is the fact the caller needs -- so report it rather than returning nothing.
    declared = set(entry["declared"])
    return declared if declared else set(entry["inlined"])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--classes", action="store_true", help="check against the compiler's class files")
    parser.add_argument("--mapping", action="store_true", help="check against R8's mapping output")
    parser.add_argument("--profile", default=DEFAULT_PROFILE)
    parser.add_argument("--jar", default=DEFAULT_JAR)
    parser.add_argument("--mapping-file", default=DEFAULT_MAPPING)
    arguments = parser.parse_args()

    if not arguments.classes and not arguments.mapping:
        arguments.classes = True

    if arguments.classes:
        if not zipfile.is_zipfile(arguments.jar):
            print(f"no compiled classes at {arguments.jar}")
            print("build them first:  ./gradlew :app:compileDebugKotlin")
            return 2
        table = {}
        with zipfile.ZipFile(arguments.jar) as archive:
            for entry in archive.namelist():
                if entry.endswith(".class"):
                    table[entry[: -len(".class")].replace("/", ".")] = class_methods(
                        archive.read(entry)
                    )
        subject = os.path.relpath(arguments.jar, REPO_ROOT)
        noun = "the compiled classes"
    else:
        if not os.path.exists(arguments.mapping_file):
            print(f"no mapping at {arguments.mapping_file}")
            print("build it first:  ./gradlew :app:assembleBenchmark")
            return 2
        table = read_mapping(arguments.mapping_file)
        subject = os.path.relpath(arguments.mapping_file, REPO_ROOT)
        noun = "the built program"

    entries = parse_profile(arguments.profile)
    print(f"profile:  {os.path.relpath(arguments.profile, REPO_ROOT)}")
    print(f"against:  {subject}")
    print(f"entries:  {len(entries)}")
    print()

    failures = []
    checked = 0
    for number, raw, owner, name, count, note in entries:
        if note:
            print(f"  n/a   {raw}  ({note})")
            continue
        checked += 1
        counts = lookup(table, owner, name)
        if counts is None:
            reason = (
                f"class {owner} is not in {noun}"
                if not _owners_for(table, owner, name)
                else f"{owner} has no method named {name}"
            )
            failures.append((number, raw, reason))
            print(f"  FAIL  {raw}\n          {reason}")
        elif count in counts:
            others = ""
            if len(counts) > 1:
                others = f"  (overloads: {', '.join(map(str, sorted(counts)))})"
            print(f"  ok    {raw.split(';->')[-1]}  ({count} parameters){others}")
        else:
            reason = (f"{owner}.{name} has {', '.join(map(str, sorted(counts)))} parameters, "
                      f"the profile says {count}")
            failures.append((number, raw, reason))
            print(f"  FAIL  {raw}\n          {reason}")

    print()
    if not failures:
        print(f"all {checked} entries match {noun}")
        return 0

    print(f"{len(failures)} of {checked} entries do not match {noun}:")
    for number, raw, reason in failures:
        print(f"    line {number}: {reason}")
    print()
    print("A mismatched line is dropped silently, so fix it or delete it.")
    return 1


if __name__ == "__main__":
    sys.exit(main())
