"""Checks the JSON Schemas against fixtures/: every valid manifest passes, and every
invalid one the schema alone can see (`schema: true` in the index) fails.

Run from the repository root:  python tools/check_schemas.py
Needs `jsonschema` (pip install jsonschema).
"""

import json
import pathlib
import sys

from jsonschema import Draft202012Validator
from referencing import Registry, Resource

ROOT = pathlib.Path(__file__).resolve().parent.parent


def load(p):
    return json.loads(p.read_text(encoding="utf-8"))


def main():
    manifest = load(ROOT / "schema" / "manifest.schema.json")
    catalog = load(ROOT / "schema" / "catalog.schema.json")
    registry = Registry().with_resources([
        (manifest["$id"], Resource.from_contents(manifest)),
        ("manifest.schema.json", Resource.from_contents(manifest)),
    ])
    mv = Draft202012Validator(manifest, registry=registry)
    cv = Draft202012Validator(catalog, registry=registry)
    failures = []

    for p in sorted((ROOT / "fixtures" / "manifests" / "valid").glob("*.json")):
        errs = list(mv.iter_errors(load(p)))
        if errs:
            failures.append(f"valid {p.name}: {errs[0].message}")
    inv = ROOT / "fixtures" / "manifests" / "invalid"
    for case in load(inv / "index.json"):
        errs = list(mv.iter_errors(load(inv / case["file"])))
        if case["schema"] and not errs:
            failures.append(f"invalid {case['file']} ({case['rule']}) passed the schema")
    errs = list(cv.iter_errors(load(ROOT / "fixtures" / "catalog" / "catalog.json")))
    if errs:
        failures.append(f"catalog: {errs[0].message}")
    for name in ("time.busy.v1", "context.read.v1"):
        cap = load(ROOT / "schema" / "capabilities" / f"{name}.json")
        out = Draft202012Validator(cap["output"])
        fx = load(ROOT / "fixtures" / "capabilities" / f"{name}.json")
        for i, ok in enumerate(fx["valid"]):
            e = list(out.iter_errors(ok))
            if e:
                failures.append(f"{name} valid[{i}]: {e[0].message}")
    for f in failures:
        print("FAIL", f)
    print(f"{len(failures)} failure(s)")
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
