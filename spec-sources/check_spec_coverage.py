#!/usr/bin/env python3
"""Check that the Spock specs and the requirements catalogs correspond exactly (no judgment).

Usage: python3 -I check_spec_coverage.py [RFC numbers, default 7643 7644 9865 9967]

Scans src/main/groovy/.../specs/Rfc<NNNN><Area>Spec.groovy (the new specs; the old A*_*Spec classes
are ignored). For every feature `def "<id>: ..."` it reads the annotations above it. Checks:
  * exactly one @SpecRef, with an id that exists in requirements-rfcNNNN.yaml
  * the display name starts with that id; no id is used by two features
  * the feature sits in the class of its requirement's RFC and area
  * @RequiresCapability equals the requirement's capability (absent = core)
  * every requirement with disposition other than na has exactly one feature; na requirements have none
Reports how many features are still skeletons (@Ignore) and how many are implemented.
Prints every problem; exit code 1 if any.
"""
import glob
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import rfc_config as cfg  # noqa: E402
from check_requirements import parse  # noqa: E402

RFCS = [a for a in sys.argv[1:] if a.isdigit()] or ["7643", "7644", "9865", "9967"]
SPECS = os.path.join(cfg.HERE, "..", "src", "main", "groovy", "de", "palsoftware", "scim", "validator", "specs")
errors = []


def err(w, msg):
    errors.append(f"{w}: {msg}")


def camel(area):
    return "".join(p.capitalize() for p in area.split("-"))


def features(path):
    """[(display name, [annotation lines])] for every `def "..."()` of a Groovy spec."""
    lines = open(path, encoding="utf-8").read().split("\n")
    out = []
    for i, l in enumerate(lines):
        m = re.match(r'^\s*def\s+"((?:[^"\\]|\\.)*)"\s*\(', l)
        if m:
            ann, j = [], i - 1
            while j >= 0 and (lines[j].strip().startswith("@") or lines[j].strip().startswith("//")):
                ann.append(lines[j].strip())
                j -= 1
            out.append((m.group(1), ann))
    return out


def main():
    catalog = {}
    for rfc in RFCS:
        for e in parse(cfg.catalog_path(rfc)):
            catalog[e["id"]] = e
    used, skeleton, implemented = {}, 0, 0
    for path in sorted(glob.glob(os.path.join(SPECS, "Rfc[0-9]*Spec.groovy"))):
        cls = os.path.basename(path)[:-len(".groovy")]
        m = re.fullmatch(r"Rfc(\d+)(\w+)Spec", cls)
        if not m or m.group(1) not in RFCS:
            continue
        for name, ann in features(path):
            w = f"{cls}: {name[:50]}"
            refs = [re.match(r'@SpecRef\("([^"]*)"', a).group(1) for a in ann if a.startswith("@SpecRef(")]
            if len(refs) != 1:
                err(w, f"needs exactly one @SpecRef, has {len(refs)}")
                continue
            rid = refs[0]
            e = catalog.get(rid)
            if e is None:
                err(w, f"@SpecRef {rid} is not in the catalog")
                continue
            if not name.startswith(rid + ":"):
                err(w, f"display name must start with '{rid}:'")
            if rid in used:
                err(w, f"{rid} is also used in {used[rid]}")
            used[rid] = cls
            if cls != f"Rfc{e['rfc']}{camel(e['area'])}Spec":
                err(w, f"belongs in Rfc{e['rfc']}{camel(e['area'])}Spec (area {e['area']})")
            caps = [re.match(r'@RequiresCapability\("([^"]*)"', a).group(1) for a in ann if a.startswith("@RequiresCapability(")]
            want = None if e["capability"] == "core" else e["capability"]
            if (caps[0] if caps else None) != want:
                err(w, f"@RequiresCapability {caps or None} differs from catalog capability {e['capability']!r}")
            if e["disposition"] == "na":
                err(w, "requirement is na and must have no feature")
            if any(a.startswith("@Ignore") for a in ann):
                skeleton += 1
            else:
                implemented += 1
    for rid, e in catalog.items():
        if e["disposition"] != "na" and rid not in used:
            err(rid, f"no feature (area {e['area']})")
    todo = sum(1 for e in catalog.values() if e["disposition"] != "na")
    print(f"RFC {', '.join(RFCS)}: {len(catalog)} requirements ({todo} need a test), {len(used)} features: "
          f"{implemented} implemented, {skeleton} skeletons")
    for x in errors:
        print("  PROBLEM", x)
    print("OK" if not errors else f"{len(errors)} problem(s)")
    sys.exit(1 if errors else 0)


if __name__ == "__main__":
    main()
