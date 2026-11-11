"""The Kahf Connect reference connector: a remote MCP server serving
`time.busy@1` and `context.read@1` (SPEC §2, §4), with a bearer token.

It is the working example for anyone writing a connector — Kahf Labs or not —
and what each SDK's end-to-end test runs against.

    pip install "mcp>=2.3,<3" uvicorn
    KAHF_REFERENCE_TOKEN=reference python server.py        # http://127.0.0.1:8765/mcp

Its data is made up: a lunch hour every day and three notes. A real connector
would read a calendar or a notes app; the contract it answers is the same.
"""

import functools
import os
from datetime import datetime, timedelta, timezone
from typing import Annotated

from pydantic import BaseModel, Field

TOKEN = os.environ.get("KAHF_REFERENCE_TOKEN", "reference")

MANIFEST = {
    "kahf_connect": "1",
    "id": "com.kahflabs.reference",
    "name": {"en": "Reference connector", "ar": "الموصل المرجعي"},
    "description": {"en": "The Kahf Connect example: a lunch hour every day and three notes."},
    "version": "1.0.0",
    "author": {"name": "Kahf Labs", "url": "https://kahflabs.com"},
    "host": {"kind": "remote", "url": "http://127.0.0.1:8765/mcp"},
    "auth": {"kind": "bearer", "setting": "token"},
    "capabilities": [
        {"id": "time.busy", "version": 1, "reason": {"en": "To show how busy times reach a product."}},
        {"id": "context.read", "version": 1, "reason": {"en": "To show how notes reach an agent."}},
    ],
    "settings": [{"key": "token", "kind": "secret", "label": {"en": "Token"}, "required": True}],
}

NOTES = [
    {"id": "n1", "title": "Release plan", "text": "Ship Qasd 1.6 with connectors; Mizwala after."},
    {"id": "n2", "title": "Reading list", "text": "Designing Data-Intensive Applications, chapter 5."},
    {"id": "n3", "title": "Weekly plan", "text": "Deep work mornings; meetings after 14:00."},
]


class Window(BaseModel):
    start: str
    end: str
    label: str | None = None
    all_day: bool = False


class BusyAnswer(BaseModel):
    windows: list[Window]


class Item(BaseModel):
    id: str
    title: str
    text: str
    url: str | None = None
    updated_at: str | None = None


class ContextAnswer(BaseModel):
    items: list[Item]


def _parse(t: str) -> datetime:
    return datetime.fromisoformat(t.replace("Z", "+00:00"))


def busy(start: datetime, end: datetime) -> BusyAnswer:
    """A lunch hour, 12:00–13:00 UTC, every day meeting [start, end)."""
    if end <= start or end - start > timedelta(days=62):
        raise ValueError("ask for at most 62 days, with to after from")
    out = []
    day = start.astimezone(timezone.utc).replace(hour=0, minute=0, second=0, microsecond=0)
    while day < end:
        s, e = day.replace(hour=12), day.replace(hour=13)
        if s < end and e > start:
            out.append(Window(start=s.isoformat().replace("+00:00", "Z"),
                              end=e.isoformat().replace("+00:00", "Z"), label="Lunch"))
        day += timedelta(days=1)
    return BusyAnswer(windows=out)


def read(query: str, limit: int) -> ContextAnswer:
    q = query.lower().strip()
    hits = [n for n in NOTES if not q or q in n["title"].lower() or q in n["text"].lower()]
    now = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
    return ContextAnswer(items=[Item(**n, updated_at=now) for n in hits[:limit]])


def _aliased(fn, **aliases):
    """Lets a tool take an argument whose wire name is a Python keyword.

    The schema still reads the parameter's alias (`from`), through
    `__wrapped__`; the call arrives with the wire name and is renamed here.
    """

    @functools.wraps(fn)
    def inner(**kw):
        for wire, param in aliases.items():
            if wire in kw:
                kw[param] = kw.pop(wire)
        return fn(**kw)

    return inner


def build():
    from mcp.server.mcpserver import MCPServer

    server = MCPServer(name="Kahf Connect reference", version="1.0.0",
                       instructions="A Kahf Connect connector. Tools are named kahf_<capability>_v<major>.")

    def kahf_time_busy_v1(
        from_: Annotated[str, Field(alias="from", description="RFC 3339 start")],
        to: Annotated[str, Field(description="RFC 3339 end")],
    ) -> BusyAnswer:
        return busy(_parse(from_), _parse(to))

    server.tool(name="kahf_time_busy_v1", description="time.busy@1: times the person is not free in [from, to).")(
        _aliased(kahf_time_busy_v1, **{"from": "from_"})
    )

    @server.tool(name="kahf_context_read_v1", description="context.read@1: notes an agent may read.")
    def kahf_context_read_v1(query: str = "", limit: int = 20) -> ContextAnswer:
        return read(query, max(1, min(limit, 50)))

    return server


class BearerOnly:
    """ASGI middleware: the configured token, or 401."""

    def __init__(self, app, token: str = TOKEN):
        self.app, self.token = app, token

    async def __call__(self, scope, receive, send):
        if scope["type"] == "http":
            headers = {k.decode().lower(): v.decode() for k, v in scope.get("headers", [])}
            if headers.get("authorization") != f"Bearer {self.token}":
                await send({"type": "http.response.start", "status": 401,
                            "headers": [(b"www-authenticate", b"Bearer")]})
                await send({"type": "http.response.body", "body": b""})
                return
        await self.app(scope, receive, send)


def app():
    server = build()
    inner = server.streamable_http_app(streamable_http_path="/mcp", json_response=True, stateless_http=True)
    return BearerOnly(inner)


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app(), host="127.0.0.1", port=int(os.environ.get("PORT", "8765")))
