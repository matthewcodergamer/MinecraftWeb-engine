#!/usr/bin/env python3
"""Apply browser-safe strong-reference fallbacks to TeaVM's staged Wasm-GC runtime."""
from pathlib import Path
import sys

path = Path(sys.argv[1] if len(sys.argv) > 1 else "build/web/clientmain.wasm-runtime.js")
source = path.read_text(encoding="utf-8")


def apply_once(source: str, label: str, original: str, replacement: str) -> str:
    original_count = source.count(original)
    replacement_count = source.count(replacement)
    if original_count == 1 and replacement_count == 0:
        source = source.replace(original, replacement)
        print(f"Patched TeaVM {label} with strong-reference fallback")
    elif original_count == 0 and replacement_count == 1:
        print(f"TeaVM {label} fallback already present")
    else:
        raise SystemExit(
            f"Expected exactly one original or patched {label} implementation; "
            f"found original={original_count}, patched={replacement_count}"
        )
    return source


# Wasm-GC values are not always valid JavaScript WeakRef targets. Preserve native
# WeakRefs where supported; if either registration or WeakRef construction fails,
# return a dereference cell that strongly retains the Java object. Java-side clear()
# still nulls the reference field; this fallback intentionally skips finalization.
source = apply_once(
    source,
    "createWeakRef",
    "createWeakRef(e,t,n){if(n!==null){r.register(e,{ref:t,queue:n})}return new WeakRef(e)}",
    "createWeakRef(e,t,n){try{if(n!==null){r.register(e,{ref:t,queue:n})}return new WeakRef(e)}catch(o){return {deref:()=>e}}}",
)

# TeaVM also emits a string-specific weak-reference helper. It needs the same
# fallback because Wasm-GC references can fail the host WeakRef target check.
source = apply_once(
    source,
    "createStringWeakRef",
    "createStringWeakRef(e,t){n.register(e,t);return new WeakRef(e)}",
    "createStringWeakRef(e,t){try{n.register(e,t);return new WeakRef(e)}catch(o){return {deref:()=>e}}}",
)

path.write_text(source, encoding="utf-8")
print(f"Verified idempotent TeaVM WeakReference compatibility patch: {path}")
