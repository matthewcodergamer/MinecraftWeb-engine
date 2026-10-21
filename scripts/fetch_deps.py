#!/usr/bin/env python3
"""Resolve and download Maven artifacts (compile+runtime scope, transitive) into tools/lib.

Usage: python3 scripts/fetch_deps.py org.teavm:teavm-cli:0.16.0 [...]
No Maven/Gradle required. Downloads are cached; re-running is a no-op.
"""
import os
import re
import sys
import urllib.request
import xml.etree.ElementTree as ET

REPO = "https://repo1.maven.org/maven2"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LIB = os.path.join(ROOT, "tools", "lib")
NS = "{http://maven.apache.org/POM/4.0.0}"


def url(g, a, v, ext):
    return f"{REPO}/{g.replace('.', '/')}/{a}/{v}/{a}-{v}.{ext}"


def fetch(u):
    with urllib.request.urlopen(u) as r:
        return r.read()


def text(el, tag):
    x = el.find(NS + tag)
    return x.text.strip() if x is not None and x.text else None


def pom_info(g, a, v, cache={}):
    key = (g, a, v)
    if key in cache:
        return cache[key]
    root = ET.fromstring(fetch(url(g, a, v, "pom")))
    props = {"project.version": v, "project.groupId": g}
    parent = root.find(NS + "parent")
    pdeps = {}
    if parent is not None:
        pg, pa, pv = text(parent, "groupId"), text(parent, "artifactId"), text(parent, "version")
        pprops, pdeps, _ = pom_info(pg, pa, pv)
        props.update(pprops)
        props["project.parent.version"] = pv
    pr = root.find(NS + "properties")
    if pr is not None:
        for p in pr:
            props[p.tag.replace(NS, "")] = (p.text or "").strip()

    def sub(s):
        return re.sub(r"\$\{([^}]+)\}", lambda m: props.get(m.group(1), m.group(0)), s) if s else s

    managed = dict(pdeps)
    dm = root.find(NS + "dependencyManagement/" + NS + "dependencies")
    if dm is not None:
        for d in dm:
            managed[(sub(text(d, "groupId")), sub(text(d, "artifactId")))] = sub(text(d, "version"))
    deps = []
    ds = root.find(NS + "dependencies")
    if ds is not None:
        for d in ds:
            scope = text(d, "scope") or "compile"
            if scope not in ("compile", "runtime") or text(d, "optional") == "true":
                continue
            dg, da = sub(text(d, "groupId")), sub(text(d, "artifactId"))
            dv = sub(text(d, "version")) or managed.get((dg, da))
            deps.append((dg, da, dv))
    cache[key] = (props, managed, deps)
    return cache[key]


def resolve(coords):
    seen, order = {}, []
    stack = [tuple(c.split(":")) for c in coords]
    while stack:
        g, a, v = stack.pop(0)
        if (g, a) in seen:
            continue
        seen[(g, a)] = v
        order.append((g, a, v))
        stack.extend(pom_info(g, a, v)[2])
    return order


def main():
    os.makedirs(LIB, exist_ok=True)
    for g, a, v in resolve(sys.argv[1:]):
        dest = os.path.join(LIB, f"{a}-{v}.jar")
        if not os.path.exists(dest):
            try:
                data = fetch(url(g, a, v, "jar"))
            except Exception as e:  # pom-only artifacts
                print(f"skip {g}:{a}:{v} ({e})")
                continue
            with open(dest, "wb") as f:
                f.write(data)
        print(f"ok   {g}:{a}:{v}")


if __name__ == "__main__":
    main()
