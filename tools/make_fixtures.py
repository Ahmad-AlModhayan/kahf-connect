"""Writes fixtures/ — the conformance cases every Kahf Connect SDK must agree on.

Run from the repository root:  python tools/make_fixtures.py
Then sign the test catalog:    (cd dart && dart run tool/catalog_tool.dart sign ../fixtures/catalog/catalog.json ../fixtures/catalog/test-only.key)

Each invalid case names the rule it breaks (the `rule` an SDK's error carries) and
whether the JSON Schema alone can see it (`schema`): some rules — https for remote
hosts, a bearer token held in a secret setting — need code, not schema.
"""

import copy
import json
import pathlib

ROOT = pathlib.Path(__file__).resolve().parent.parent
FIX = ROOT / "fixtures"

BASE = {
    "kahf_connect": "1",
    "id": "com.example.busy",
    "name": {"en": "Example busy", "ar": "مثال"},
    "description": {"en": "Example connector for the conformance suite."},
    "version": "1.0.0",
    "author": {"name": "Example Co", "url": "https://example.com"},
    "host": {"kind": "remote", "url": "https://connect.example.com/mcp"},
    "auth": {"kind": "bearer", "setting": "token"},
    "capabilities": [
        {"id": "time.busy", "version": 1, "reason": {"en": "To plan around your busy times."}}
    ],
    "settings": [{"key": "token", "kind": "secret", "label": {"en": "Token"}, "required": True}],
}


def variant(**changes):
    m = copy.deepcopy(BASE)
    for path, value in changes.items():
        keys = path.split("__")
        target = m
        for k in keys[:-1]:
            target = target[int(k)] if isinstance(target, list) else target[k]
        last = keys[-1]
        if value is DELETE:
            del target[last]
        else:
            if isinstance(target, list):
                target[int(last)] = value
            else:
                target[last] = value
    return m


DELETE = object()

VALID = {
    "remote-bearer": BASE,
    "builtin-calendar-feed": {
        "kahf_connect": "1",
        "id": "com.kahflabs.calendar-feed",
        "name": {"en": "Calendar feed", "ar": "رابط التقويم"},
        "description": {"en": "Busy times from a private calendar address."},
        "version": "1.0.0",
        "author": {"name": "Kahf Labs"},
        "host": {"kind": "builtin"},
        "auth": {"kind": "none"},
        "capabilities": [{"id": "time.busy", "version": 1, "reason": {"en": "To plan around busy times."}}],
        "settings": [{"key": "url", "kind": "secret-url", "label": {"en": "Address"}, "required": True}],
    },
    "device-mizwala": {
        "kahf_connect": "1",
        "id": "com.kahflabs.mizwala.times",
        "name": {"en": "Mizwala", "ar": "مزولة"},
        "description": {"en": "Times you keep for prayer, from Mizwala on this phone."},
        "version": "1.0.0",
        "author": {"name": "Kahf Labs"},
        "host": {"kind": "device", "package": "com.kahf.mizwala"},
        "auth": {"kind": "none"},
        "capabilities": [{"id": "time.busy", "version": 1, "reason": {"en": "To keep those times free."}}],
    },
    "remote-oauth2-two-capabilities": variant(
        auth={
            "kind": "oauth2",
            "authorize_url": "https://example.com/oauth/authorize",
            "token_url": "https://example.com/oauth/token",
            "scopes": ["busy.read", "notes.read"],
        },
        settings=DELETE,
        capabilities=[
            {"id": "time.busy", "version": 1, "reason": {"en": "Busy times."}},
            {"id": "context.read", "version": 1, "reason": {"en": "Notes your agent may read."}},
        ],
    ),
    "localhost-http-for-development": variant(host={"kind": "remote", "url": "http://localhost:8765/mcp"}),
}

INVALID = [
    ("unknown-field", True, variant(extra=1)),
    ("version-of-spec", True, variant(kahf_connect="2")),
    ("id", True, variant(id="Example.Busy")),
    ("id", True, variant(id="busy")),
    ("version", True, variant(version="1.0")),
    ("localised", True, variant(name={"ar": "مثال"})),
    ("localised", True, variant(name={"en": ""})),
    ("host-url", True, variant(host={"kind": "remote"})),
    ("host-https", False, variant(host={"kind": "remote", "url": "http://connect.example.com/mcp"})),
    ("host-package", True, variant(host={"kind": "device"})),
    ("capabilities", True, variant(capabilities=[])),
    ("capability-unknown", True, variant(capabilities=[{"id": "files.write", "version": 1, "reason": {"en": "x"}}])),
    ("capability-reason", True, variant(capabilities=[{"id": "time.busy", "version": 1}])),
    ("capability-duplicate", False, variant(capabilities=[
        {"id": "time.busy", "version": 1, "reason": {"en": "x"}},
        {"id": "time.busy", "version": 1, "reason": {"en": "y"}},
    ])),
    ("auth-setting", True, variant(auth={"kind": "bearer"})),
    ("auth-setting", False, variant(settings=[{"key": "token", "kind": "text", "label": {"en": "Token"}}])),
    ("auth-oauth2", True, variant(auth={"kind": "oauth2"}, settings=DELETE)),
    ("auth-oauth2-https", True, variant(auth={
        "kind": "oauth2",
        "authorize_url": "http://example.com/oauth/authorize",
        "token_url": "https://example.com/oauth/token",
    }, settings=DELETE)),
    ("setting-key", True, variant(settings=[{"key": "Token", "kind": "secret", "label": {"en": "T"}}])),
    ("setting-kind", True, variant(settings=[{"key": "token", "kind": "password", "label": {"en": "T"}}])),
    # Parser differentials (kahf-connect#16): URLs one parser reads as a safe host and another as a different one.
    # The schema cannot see these; every SDK must refuse them, so the host it checks is the host it connects to.
    ("uri", False, variant(host={"kind": "remote", "url": "https://evil.example\\@good.example/mcp"})),
    ("uri", False, variant(host={"kind": "remote", "url": "https://a@b@good.example/mcp"})),
    ("uri", False, variant(host={"kind": "remote", "url": "https://good.example /mcp"})),
    ("uri", False, variant(host={"kind": "remote", "url": "https://gööd.example/mcp"})),
    ("uri", False, variant(host={"kind": "remote", "url": "https://%65vil.example/mcp"})),
    ("host-https", False, variant(host={"kind": "remote", "url": "http://localhost@evil.example/mcp"})),
    ("host-https", False, variant(host={"kind": "remote", "url": "http://127.0.0.1.evil.example/mcp"})),
    ("auth-oauth2-https", False, variant(auth={
        "kind": "oauth2",
        "authorize_url": "http://localhost:80@evil.example/oauth/authorize",
        "token_url": "https://example.com/oauth/token",
    }, settings=DELETE)),
]

BUSY_FROM = "2026-10-27T00:00:00Z"
BUSY_TO = "2026-10-28T00:00:00Z"

BUSY = {
    "valid": [
        {"windows": []},
        {"windows": [{"start": "2026-10-27T09:00:00Z", "end": "2026-10-27T10:00:00Z"}]},
        {"windows": [{"start": "2026-10-27T12:00:00+03:00", "end": "2026-10-27T13:00:00+03:00", "label": "Lunch"}]},
        {"windows": [{"start": "2026-10-26T23:00:00Z", "end": "2026-10-27T01:00:00Z", "all_day": False, "extra": "ignored"}]},
    ],
    "invalid": [
        ("shape", {}),
        ("time-offset", {"windows": [{"start": "2026-10-27T09:00:00", "end": "2026-10-27T10:00:00Z"}]}),
        ("order", {"windows": [{"start": "2026-10-27T10:00:00Z", "end": "2026-10-27T09:00:00Z"}]}),
        ("out-of-range", {"windows": [{"start": "2026-10-29T09:00:00Z", "end": "2026-10-29T10:00:00Z"}]}),
        ("label", {"windows": [{"start": "2026-10-27T09:00:00Z", "end": "2026-10-27T10:00:00Z", "label": 3}]}),
    ],
}

CONTEXT = {
    "valid": [
        {"items": []},
        {"items": [{"id": "n1", "title": "Plan", "text": "Ship v2", "url": "https://notes.example.com/n1",
                    "updated_at": "2026-10-26T08:00:00Z"}]},
    ],
    "invalid": [
        ("shape", {"notes": []}),
        ("id", {"items": [{"id": "", "title": "t", "text": "x"}]}),
        ("id-duplicate", {"items": [{"id": "a", "title": "t", "text": "x"}, {"id": "a", "title": "u", "text": "y"}]}),
        ("text", {"items": [{"id": "a", "title": "t"}]}),
    ],
}


def write(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")


def main():
    for name, m in VALID.items():
        write(FIX / "manifests" / "valid" / f"{name}.json", m)
    index = []
    for i, (rule, schema, m) in enumerate(INVALID):
        name = f"{i:02d}-{rule}.json"
        write(FIX / "manifests" / "invalid" / name, m)
        index.append({"file": name, "rule": rule, "schema": schema})
    write(FIX / "manifests" / "invalid" / "index.json", index)

    write(FIX / "capabilities" / "time.busy.v1.json", {
        "from": BUSY_FROM, "to": BUSY_TO,
        "valid": BUSY["valid"],
        "invalid": [{"rule": r, "output": o} for r, o in BUSY["invalid"]],
    })
    write(FIX / "capabilities" / "context.read.v1.json", {
        "limit": 20,
        "valid": CONTEXT["valid"],
        "invalid": [{"rule": r, "output": o} for r, o in CONTEXT["invalid"]],
    })

    catalog = {
        "kahf_connect": "1",
        "issued_at": "2026-10-26T00:00:00Z",
        "key_id": "test-only",
        "entries": [
            {"reviewed": True, "manifest": VALID["builtin-calendar-feed"]},
            {"reviewed": True, "manifest": VALID["device-mizwala"]},
        ],
    }
    write(FIX / "catalog" / "catalog.json", catalog)
    print("fixtures written")


if __name__ == "__main__":
    main()
