#!/usr/bin/env python3
"""Smoke-test POST /api/sessions/{id}/vision/form-stream (SSE) for form_vision_fill."""
from __future__ import annotations

import json
import sys
import uuid
from pathlib import Path
from typing import Any

import requests

BASE = "http://127.0.0.1:8888"
ASSETS = Path(
    "/Users/qjli/.cursor/projects/Users-qjli-03LD-26-21-agentscope-feishu-001-cursor-agentscope-feishu/assets"
)


def parse_sse(resp: requests.Response) -> dict[str, Any] | None:
    last: dict[str, Any] | None = None
    buf = ""
    for chunk in resp.iter_content(chunk_size=8192):
        if not chunk:
            continue
        buf += chunk.decode("utf-8", errors="replace")
        while "\n\n" in buf:
            block, buf = buf.split("\n\n", 1)
            for line in block.split("\n"):
                line = line.strip()
                if not line.startswith("data:"):
                    continue
                raw = line[5:].strip()
                if not raw:
                    continue
                try:
                    obj = json.loads(raw)
                except json.JSONDecodeError:
                    continue
                if obj.get("type") == "error":
                    print("SSE error:", obj, file=sys.stderr)
                    return None
                if obj.get("type") == "result":
                    last = obj
    return last


def post_vision(session_id: str, paths: list[tuple[Path, str]]) -> dict[str, Any] | None:
    files: list[tuple[str, tuple[str, Any, str]]] = []
    holders: list[Any] = []
    try:
        for p, fname in paths:
            fh = p.open("rb")
            holders.append(fh)
            files.append(("files", (fname, fh, "image/png")))
        r = requests.post(
            f"{BASE}/api/sessions/{session_id}/vision/form-stream",
            files=files,
            headers={"Accept": "text/event-stream"},
            stream=True,
            timeout=900,
        )
        r.raise_for_status()
        return parse_sse(r)
    finally:
        for h in holders:
            h.close()


def summarize(label: str, res: dict[str, Any] | None) -> None:
    print(f"\n=== {label} ===")
    if res is None:
        print("(no result / error)")
        return
    patch = res.get("formPatch") or res.get("form_patch") or {}
    amb = res.get("ambiguities") or []
    print("form_patch keys:", sorted(patch.keys()))
    print("ambiguities count:", len(amb))
    if amb:
        for a in amb[:5]:
            print("  -", a.get("field_key"), a.get("question_for_user", "")[:80])
    rep = (res.get("reply") or "")[:400]
    print("reply head:", rep.replace("\n", " ") + ("…" if len(res.get("reply") or "") > 400 else ""))


def main() -> int:
    biz = ASSETS / "____-fcb3c71f-8e25-465d-89d8-680d707cdb8c.png"
    idc = ASSETS / "_____-8da978b3-d0ba-4062-ac0c-feee011d86de.png"
    road = ASSETS / "___________-cded1a92-b748-4593-9540-60ac464606f5.png"
    safe = ASSETS / "________-78882a1f-cca4-4945-9a49-dc7ab0765a5d.png"
    for p in (biz, idc, road, safe):
        if not p.is_file():
            print("Missing image:", p, file=sys.stderr)
            return 2

    sid = str(uuid.uuid4())
    # 文件名含关键词，便于 upload_material_coverage 推断（与本脚本无关）
    batch = [
        (biz, "营业执照-河南安彩.png"),
        (idc, "身份证人像面-段晓涛.png"),
        (road, "道路危险货物运输许可证-河北昆仑.png"),
        (safe, "危险化学品经营许可证-山东某某.png"),
    ]
    summarize("4 images at once", post_vision(sid, batch))

    sid2 = str(uuid.uuid4())
    for i, item in enumerate(batch, start=1):
        summarize(f"single upload {i}/4", post_vision(sid2, [item]))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
