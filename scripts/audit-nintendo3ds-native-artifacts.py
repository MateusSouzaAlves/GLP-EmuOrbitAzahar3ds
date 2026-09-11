#!/usr/bin/env python3
"""Audit the packaged Nintendo 3DS native boundary without modifying it."""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Sequence


BOOTSTRAP_NEEDED = {"libandroid.so", "libc.so", "libdl.so", "liblog.so", "libm.so"}
CORE_NEEDED = {"libc.so", "libdl.so", "liblog.so", "libm.so"}
CORE_EXPORTS = {
    "retro_api_version",
    "retro_cheat_reset",
    "retro_cheat_set",
    "retro_deinit",
    "retro_get_memory_data",
    "retro_get_memory_size",
    "retro_get_region",
    "retro_get_system_av_info",
    "retro_get_system_info",
    "retro_init",
    "retro_load_game",
    "retro_load_game_special",
    "retro_reset",
    "retro_run",
    "retro_serialize",
    "retro_serialize_size",
    "retro_set_audio_sample",
    "retro_set_audio_sample_batch",
    "retro_set_controller_port_device",
    "retro_set_environment",
    "retro_set_input_poll",
    "retro_set_input_state",
    "retro_set_video_refresh",
    "retro_unload_game",
    "retro_unserialize",
}


@dataclass(frozen=True)
class ToolOutput:
    header: str
    dynamic: str
    program_headers: str
    sections: str
    notes: str
    dynamic_symbols: str


def _lines_with_prefix(text: str, prefix: str) -> list[str]:
    return [line for line in text.splitlines() if line.lstrip().startswith(prefix)]


def _needed_libraries(dynamic: str) -> set[str]:
    return set(re.findall(r"\(NEEDED\).*?\[([^\]]+)\]", dynamic))


def _soname(dynamic: str) -> str | None:
    match = re.search(r"\(SONAME\).*?\[([^\]]+)\]", dynamic)
    return match.group(1) if match else None


def _defined_symbols(nm_output: str) -> set[str]:
    symbols: set[str] = set()
    for line in nm_output.splitlines():
        fields = line.split()
        if fields:
            symbols.add(fields[0].split("@", 1)[0])
    return symbols


def audit_outputs(
    *,
    kind: str,
    artifact_bytes: bytes,
    output: ToolOutput,
    forbidden_paths: Iterable[str] = (),
) -> list[str]:
    errors: list[str] = []
    expected_soname = (
        "libemuorbit_n3ds_bootstrap.so" if kind == "bootstrap" else "azahar_libretro.so"
    )
    expected_needed = BOOTSTRAP_NEEDED if kind == "bootstrap" else CORE_NEEDED

    if not artifact_bytes.startswith(b"\x7fELF"):
        errors.append("artifact is not an ELF file")
    if "Class:" not in output.header or "ELF64" not in output.header:
        errors.append("artifact is not ELF64")
    if not re.search(r"Machine:\s+AArch64", output.header):
        errors.append("artifact is not AArch64")
    if not re.search(r"Type:\s+DYN", output.header):
        errors.append("artifact is not a shared object")

    if _soname(output.dynamic) != expected_soname:
        errors.append(f"unexpected SONAME; expected {expected_soname}")
    needed = _needed_libraries(output.dynamic)
    if needed != expected_needed:
        errors.append(
            "unexpected runtime dependencies: "
            + ", ".join(sorted(needed ^ expected_needed))
        )
    if "BIND_NOW" not in output.dynamic or not re.search(
        r"FLAGS_1[^\r\n]*\bNOW\b", output.dynamic
    ):
        errors.append("immediate relocation binding is missing")

    if "GNU_RELRO" not in output.program_headers:
        errors.append("GNU_RELRO is missing")
    stack_lines = _lines_with_prefix(output.program_headers, "GNU_STACK")
    if len(stack_lines) != 1 or re.search(r"\bE\b", stack_lines[0]):
        errors.append("GNU_STACK is missing or executable")
    load_lines = _lines_with_prefix(output.program_headers, "LOAD")
    if not load_lines:
        errors.append("LOAD segments are missing")
    for line in load_lines:
        alignment = re.search(r"(0x[0-9a-fA-F]+)\s*$", line)
        if not alignment or int(alignment.group(1), 16) < 0x4000:
            errors.append("a LOAD segment is not aligned to 16 KiB")
            break

    forbidden_sections = re.findall(
        r"\]\s+(\.(?:debug[^\s]*|symtab|comment))\s", output.sections
    )
    if forbidden_sections:
        errors.append("forbidden packaged sections: " + ", ".join(sorted(forbidden_sections)))
    if "Build ID:" not in output.notes:
        errors.append("GNU build ID is missing")

    symbols = _defined_symbols(output.dynamic_symbols)
    if kind == "bootstrap":
        if symbols != {"JNI_OnLoad"}:
            errors.append("bootstrap must export only JNI_OnLoad")
    elif symbols != CORE_EXPORTS:
        missing = sorted(CORE_EXPORTS - symbols)
        unexpected = sorted(symbols - CORE_EXPORTS)
        if missing:
            errors.append("core is missing required libretro exports: " + ", ".join(missing))
        if unexpected:
            errors.append(
                f"core exports {len(unexpected)} symbols outside the libretro ABI; sample: "
                + ", ".join(unexpected[:10])
            )

    lowered = artifact_bytes.lower()
    for forbidden_path in forbidden_paths:
        normalized = forbidden_path.strip()
        if len(normalized) < 8:
            continue
        variants = {
            normalized,
            normalized.replace("\\", "/"),
            normalized.replace("/", "\\"),
        }
        if any(variant.lower().encode("utf-8") in lowered for variant in variants):
            errors.append("artifact contains a forbidden build path")
            break
    return errors


def _run(tool: Path, arguments: Sequence[str], artifact: Path) -> str:
    result = subprocess.run(
        [str(tool), *arguments, str(artifact)],
        check=False,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    if result.returncode != 0:
        raise RuntimeError(f"{tool.name} failed: {result.stderr.strip()}")
    return result.stdout


def _tool(ndk_root: Path, name: str) -> Path:
    suffix = ".exe" if os.name == "nt" else ""
    candidates = list(ndk_root.glob(f"toolchains/llvm/prebuilt/*/bin/{name}{suffix}"))
    if len(candidates) != 1:
        raise FileNotFoundError(f"unable to resolve {name} below {ndk_root}")
    return candidates[0]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--artifact", type=Path, required=True)
    parser.add_argument("--kind", choices=("bootstrap", "core"), required=True)
    parser.add_argument("--ndk-root", type=Path, required=True)
    parser.add_argument("--forbid-path", action="append", default=[])
    args = parser.parse_args()

    artifact = args.artifact.resolve(strict=True)
    readelf = _tool(args.ndk_root.resolve(strict=True), "llvm-readelf")
    nm = _tool(args.ndk_root.resolve(strict=True), "llvm-nm")
    output = ToolOutput(
        header=_run(readelf, ("--file-header", "--wide"), artifact),
        dynamic=_run(readelf, ("--dynamic", "--wide"), artifact),
        program_headers=_run(readelf, ("--program-headers", "--wide"), artifact),
        sections=_run(readelf, ("--sections", "--wide"), artifact),
        notes=_run(readelf, ("--notes", "--wide"), artifact),
        dynamic_symbols=_run(nm, ("-D", "--defined-only", "--format=posix"), artifact),
    )
    errors = audit_outputs(
        kind=args.kind,
        artifact_bytes=artifact.read_bytes(),
        output=output,
        forbidden_paths=args.forbid_path,
    )
    report = {
        "artifact": str(artifact),
        "kind": args.kind,
        "status": "PASSED" if not errors else "FAILED",
        "bytes": artifact.stat().st_size,
        "soname": _soname(output.dynamic),
        "needed": sorted(_needed_libraries(output.dynamic)),
        "definedExportCount": len(_defined_symbols(output.dynamic_symbols)),
        "definedExports": (
            sorted(_defined_symbols(output.dynamic_symbols))
            if args.kind == "bootstrap"
            else sorted(
                symbol
                for symbol in _defined_symbols(output.dynamic_symbols)
                if symbol.startswith("retro_")
            )
        ),
        "errors": errors,
    }
    print(json.dumps(report, indent=2))
    return 0 if not errors else 1


if __name__ == "__main__":
    raise SystemExit(main())
