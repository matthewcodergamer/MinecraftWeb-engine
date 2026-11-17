#!/usr/bin/env python3
"""Summarise a failed CI run of the browser port.

Pulls the failed-step log for a workflow run and prints the runtime evidence we
actually need: the last Java stage reached, the first fatal exception, the
timeout page state (fiber dump, monitor dump) and whether monitor diagnostics
were emitted at all.

usage: scripts/ci_digest.py <run-id> [--repo owner/name]
"""
import argparse
import json
import re
import subprocess
import sys

REPORT_RE = re.compile(r"(\d+\.\d+)s console\.log: \[REPORT\] (\S+) = (.*)$")
STAGE_RE = re.compile(r"(\d+\.\d+)s console\.log: \[JAVA_STAGE\] (.*?) @")


def failed_log(run_id, repo):
    out = subprocess.run(
        ["gh", "run", "view", str(run_id), "-R", repo, "--log-failed"],
        capture_output=True, text=True, check=False).stdout
    return out.splitlines()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("run_id")
    ap.add_argument("--repo", default="matthewcodergamer/MinecraftWeb-engine")
    ap.add_argument("--tail", type=int, default=25)
    args = ap.parse_args()

    lines = failed_log(args.run_id, args.repo)
    reports, stages = [], []
    for line in lines:
        m = REPORT_RE.search(line)
        if m:
            reports.append((float(m.group(1)), m.group(2), m.group(3)))
        m = STAGE_RE.search(line)
        if m:
            stages.append((float(m.group(1)), m.group(2)))

    print(f"log lines: {len(lines)}  reports: {len(reports)}")
    monitors = sum(1 for r in reports if r[2].startswith("[MONITOR]"))
    tobj = sum(1 for r in reports if "[TOBJ]" in r[2])
    print(f"[MONITOR] lines: {monitors}   [TOBJ] lines: {tobj}")
    print(f"last java stage: {stages[-1] if stages else '(none)'}")
    print(f"port-stage events: {[s for s in stages if s[1].startswith('port-stage-')][-6:]}")

    errors = [r for r in reports if r[1] in ("error", "watchdog-error")]
    if errors:
        print("\nfirst error:", errors[0][2][:400])
        print("last error :", errors[-1][2][:400])

    tobj_lines = [r for r in reports if "[TOBJ]" in r[2] or "[MONITOR]" in r[2]]
    if tobj_lines:
        print("\nmonitor diagnostics (last 12):")
        for ts, _, value in tobj_lines[-12:]:
            print(f"  {ts:7.2f}s {value[:300]}")

    state = [l for l in lines if "timeout page state" in l]
    if state:
        payload = state[-1].split("timeout page state: ", 1)[1].strip()
        try:
            data = json.loads(payload)
        except Exception as exc:  # noqa: BLE001 - log payloads are best effort
            print(f"\ntimeout page state (unparsed, {exc}): {payload[:600]}")
            return
        mc = (data.get("iframe") or {}).get("mc") or {}
        print("\ntimeout page state:")
        print("  stage :", mc.get("stage"))
        print("  trace :", mc.get("trace"))
        print("  bridge:", data.get("bridge"))
        print("  fibers:\n   ", (mc.get("fibers") or "").replace(" | ", "\n    "))
        print("  monitors:\n   ", mc.get("monitors") or "(empty)")

    tail = [r for r in reports][-args.tail:]
    if tail:
        print("\nlast reports:")
        for ts, key, value in tail:
            print(f"  {ts:7.2f}s {key} = {value[:200]}")


if __name__ == "__main__":
    sys.exit(main())
