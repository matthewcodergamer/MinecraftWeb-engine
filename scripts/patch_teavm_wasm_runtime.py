#!/usr/bin/env python3
"""Apply the browser-safe WeakReference fallback to TeaVM's staged Wasm-GC runtime."""
from pathlib import Path
import sys

path = Path(sys.argv[1] if len(sys.argv) > 1 else "build/web/clientmain.wasm-runtime.js")
source = path.read_text(encoding="utf-8")

# TeaVM 0.16.0's Wasm-GC import assumes every Java reference is a JS WeakRef
# target. Wasm-GC values are not always valid JS WeakRef targets. Keep normal
# WeakRefs where supported, but fall back to a strong dereference cell when
# the host rejects the target. Java Reference.clear() still nulls the Wasm
# reference field, and this fallback intentionally skips finalization.
needle = "createWeakRef(e,t,n){if(n!==null){r.register(e,{ref:t,queue:n})}return new WeakRef(e)}"
replacement = "createWeakRef(e,t,n){try{if(n!==null){r.register(e,{ref:t,queue:n})}return new WeakRef(e)}catch(o){return {deref:()=>e}}}"

count = source.count(needle)
if count != 1:
    raise SystemExit(f"Expected exactly one TeaVM createWeakRef implementation, found {count}")

source = source.replace(needle, replacement)
if replacement not in source:
    raise SystemExit("WeakReference fallback patch verification failed")

path.write_text(source, encoding="utf-8")
print(f"Patched TeaVM createWeakRef with strong-reference fallback: {path}")
