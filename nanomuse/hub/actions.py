"""This computer's hands for the other devices: the actions the hub lets a phone or another
computer of the same account call here — ``info``, ``shell``, ``files``, ``file.get``,
``file.put``, ``open``, ``screen``, ``notify``. Each takes the frame's ``args`` and returns
a JSON-able dict; file bytes travel base64 in ``data``.

The contract (docs/hub.md) judges a command on the device that *asked*, before it is sent
— the phone's ShellGuard, another runtime's Sentinel — so nothing here asks again; the
switch that decides whether this computer takes calls at all is ``[hub] remote_control``
(:class:`nanomuse.hub.service.HubService`). Everything runs as the signed-in user. Ported
from the terminal edition's actions module (dropped in 0.1.39).
"""

from __future__ import annotations

import base64
import getpass
import mimetypes
import os
import platform
import shutil
import socket
import subprocess
import sys
import time
import webbrowser
from pathlib import Path
from typing import Any

from nanomuse import __version__
from nanomuse.computer.screen import BlackScreen, take_screenshot
from nanomuse.tools.base import number_arg
from nanomuse.tools.shell import kill_tree, scrubbed_env

OUTPUT_LIMIT = 200_000
FILE_LIMIT = 8 * 1024 * 1024  # base64 inside one hub frame
SHELL_TIMEOUT_MAX = 900.0

# what this device announces in `hello`; `task`, `stop` and `approve` are the service's
ACTIONS = ("info", "shell", "files", "file.get", "file.put", "open", "screen", "notify", "image.generate")


class ActionError(Exception):
    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code
        self.message = message


def expand(path: str | None) -> Path:
    return Path(os.path.expandvars(os.path.expanduser(path or "~"))).resolve()


def shell_name() -> str:
    if os.name == "nt":
        return os.environ.get("COMSPEC", "cmd.exe")
    return os.environ.get("SHELL", "/bin/sh")


def info(extra_actions: tuple[str, ...] = ()) -> dict[str, Any]:
    return {
        "name": socket.gethostname(),
        "kind": "computer",
        "os": platform.system(),
        "os_version": platform.release(),
        "arch": platform.machine(),
        "user": getpass.getuser(),
        "home": str(Path.home()),
        "cwd": os.getcwd(),
        "shell": shell_name(),
        "python": platform.python_version(),
        "runtime": __version__,
        "actions": [*ACTIONS, *extra_actions],
    }


def _text(b: bytes) -> str:
    s = b.decode("utf-8", errors="replace")
    if len(s) > OUTPUT_LIMIT:
        s = s[:OUTPUT_LIMIT] + f"\n… [{len(s) - OUTPUT_LIMIT} more characters not shown]"
    return s


def shell(command: str, cwd: str | None = None, timeout: Any = 120) -> dict[str, Any]:
    if not command or not command.strip():
        raise ActionError("usage", "a command is required")
    timeout = number_arg(timeout, 120, 1.0, SHELL_TIMEOUT_MAX)
    started = time.monotonic()
    timed_out = False
    try:
        proc = subprocess.Popen(
            command,
            shell=True,
            cwd=str(expand(cwd)) if cwd else None,
            env=scrubbed_env(),
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            start_new_session=os.name != "nt",
        )
    except (OSError, ValueError) as exc:
        return {
            "exit_code": 127,
            "stdout": "",
            "stderr": str(exc),
            "timed_out": False,
            "duration_ms": 0,
        }
    try:
        out, err = proc.communicate(timeout=timeout)
        code = proc.returncode
    except subprocess.TimeoutExpired:
        timed_out = True
        kill_tree(proc)
        try:
            out, err = proc.communicate(timeout=5)
        except subprocess.TimeoutExpired:
            out, err = b"", b""
        code = 124
    return {
        "exit_code": code,
        "stdout": _text(out),
        "stderr": _text(err),
        "timed_out": timed_out,
        "duration_ms": int((time.monotonic() - started) * 1000),
    }


def _entry(p: Path, st: os.stat_result) -> dict[str, Any]:
    kind = "link" if p.is_symlink() else "dir" if p.is_dir() else "file"
    return {"name": p.name, "type": kind, "size": st.st_size, "mtime": int(st.st_mtime)}


def files(path: str | None = None) -> dict[str, Any]:
    p = expand(path)
    if not p.exists():
        raise ActionError("not_found", f"{p} does not exist")
    if p.is_file():
        return {"path": str(p), "entries": [_entry(p, p.stat())]}
    entries = []
    try:
        children = sorted(p.iterdir(), key=lambda c: (not c.is_dir(), c.name.lower()))
    except PermissionError as exc:
        raise ActionError("forbidden", f"cannot read {p}") from exc
    for child in children:
        try:
            entries.append(_entry(child, child.lstat()))
        except OSError:
            continue
        if len(entries) >= 2000:
            break
    return {"path": str(p), "entries": entries}


def file_get(path: str) -> dict[str, Any]:
    p = expand(path)
    if not p.is_file():
        raise ActionError("not_found", f"{p} is not a file")
    size = p.stat().st_size
    if size > FILE_LIMIT:
        raise ActionError(
            "too_large", f"{p.name} is {size} bytes; the limit over the hub is {FILE_LIMIT}"
        )
    return {
        "path": str(p),
        "name": p.name,
        "bytes": size,
        "mime": mimetypes.guess_type(p.name)[0] or "application/octet-stream",
        "data": base64.b64encode(p.read_bytes()).decode(),
    }


def file_put(path: str, data: str, force: bool = False) -> dict[str, Any]:
    p = expand(path)
    raw = base64.b64decode(data or "")
    if p.is_dir():
        raise ActionError("is_dir", f"{p} is a folder")
    if p.exists() and not force:
        raise ActionError("exists", f"{p} already exists; force replaces it")
    p.parent.mkdir(parents=True, exist_ok=True)
    tmp = p.with_name(p.name + ".nanomuse-part")
    tmp.write_bytes(raw)
    os.replace(tmp, p)
    return {"path": str(p), "bytes": len(raw)}


def open_target(url: str) -> dict[str, Any]:
    url = (url or "").strip()
    if not url:
        raise ActionError("usage", "a URL or a path is required")
    if "://" not in url:
        p = expand(url)
        if not p.exists():
            raise ActionError("not_found", f"{p} does not exist")
        url = p.as_uri()
    ok = False
    try:
        if sys.platform == "darwin":
            ok = subprocess.run(["open", url], capture_output=True, timeout=20).returncode == 0
        elif os.name == "nt":
            os.startfile(url)  # type: ignore[attr-defined]
            ok = True
        elif shutil.which("xdg-open"):
            ok = subprocess.run(["xdg-open", url], capture_output=True, timeout=20).returncode == 0
    except (OSError, subprocess.TimeoutExpired):
        ok = False
    if not ok:
        ok = webbrowser.open(url)
    return {"ok": bool(ok), "url": url}


def screen() -> dict[str, Any]:
    try:
        shot = take_screenshot()
    except BlackScreen as exc:
        raise ActionError("black_screen", str(exc)) from exc
    if shot is None:
        raise ActionError(
            "no_screen", "this computer cannot take a screenshot (no display, or no tool for it)"
        )
    return {
        "mime": shot.mime,
        "bytes": len(shot.data),
        "width": shot.width,
        "height": shot.height,
        "data": shot.base64,
    }


def notify(text: str, title: str = "nanoMuse") -> dict[str, Any]:
    text = (text or "").strip()
    if not text:
        raise ActionError("usage", "text is required")
    ok = False
    try:
        if sys.platform == "darwin":
            script = f'display notification "{_osa(text)}" with title "{_osa(title)}"'
            ok = (
                subprocess.run(
                    ["osascript", "-e", script], capture_output=True, timeout=20
                ).returncode
                == 0
            )
        elif os.name == "nt":
            ps = (
                "[Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, "
                "ContentType = WindowsRuntime] | Out-Null;"
                "$t=[Windows.UI.Notifications.ToastNotificationManager]::GetTemplateContent("
                "[Windows.UI.Notifications.ToastTemplateType]::ToastText02);"
                "$x=$t.GetElementsByTagName('text');"
                f"$x.Item(0).AppendChild($t.CreateTextNode('{_ps(title)}')) | Out-Null;"
                f"$x.Item(1).AppendChild($t.CreateTextNode('{_ps(text)}')) | Out-Null;"
                "[Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier("
                "'nanoMuse').Show([Windows.UI.Notifications.ToastNotification]::new($t))"
            )
            ok = (
                subprocess.run(
                    ["powershell", "-NoProfile", "-Command", ps], capture_output=True, timeout=20
                ).returncode
                == 0
            )
        elif shutil.which("notify-send"):
            ok = (
                subprocess.run(
                    ["notify-send", "-a", "nanoMuse", title, text], capture_output=True, timeout=20
                ).returncode
                == 0
            )
    except (OSError, subprocess.TimeoutExpired):
        ok = False
    return {"ok": True, "shown": ok}


def _osa(s: str) -> str:
    return s.replace("\\", "\\\\").replace('"', '\\"')[:400]


def _ps(s: str) -> str:
    return s.replace("'", "''")[:400]


def image_generate(args: dict[str, Any]) -> dict[str, Any]:
    prompt = str(args.get("prompt") or "").strip()
    if not prompt:
        raise ActionError("empty_prompt", "prompt must not be empty")
    aspect_ratio = str(args.get("aspect_ratio") or "1:1").strip()

    raw_name = str(args.get("image_name") or "").strip()
    import re
    if raw_name and re.match(r"^[A-Za-z0-9_-]{1,64}$", raw_name):
        image_name = raw_name
    else:
        slug = re.sub(r"[^a-z0-9_]+", "_", prompt[:30].lower()).strip("_") or "generated_image"
        image_name = f"{slug}_{int(time.time() * 1000)}"

    agy_cli = shutil.which("agy")
    if not agy_cli:
        raise ActionError("agy_not_found", "Antigravity CLI (agy) is not installed on this computer")

    import json
    task_desc = f"Call generate_image tool with Prompt={json.dumps(prompt)} and ImageName={json.dumps(image_name)} and AspectRatio={json.dumps(aspect_ratio)}"
    cmd = [
        agy_cli,
        "--output-format",
        "json",
        "--dangerously-skip-permissions",
        "--print",
        task_desc,
    ]

    try:
        proc = subprocess.run(cmd, capture_output=True, text=True, timeout=120)
    except subprocess.TimeoutExpired:
        raise ActionError("timeout", "Image generation timed out after 120 seconds")
    except Exception as exc:
        raise ActionError("execution_failed", str(exc))

    # Note: agy sometimes exits with non-zero return code even after successfully generating an image, so proceed to parse stdout if available
    if proc.returncode != 0 and not proc.stdout:
        raise ActionError("execution_error", proc.stderr.strip() or "Antigravity CLI failed")

    data: dict[str, Any] = {}
    try:
        import json
        data = json.loads(proc.stdout)
    except Exception:
        import json
        lines = [line.strip() for line in proc.stdout.splitlines() if line.strip().startswith("{")]
        if lines:
            data = json.loads(lines[-1])

    response_text = str(data.get("response") or "")
    cid = str(data.get("conversation_id") or "")

    import re
    m = re.search(r"file://([^\s\)]+\.(?:jpg|jpeg|png|webp))", response_text)
    img_path = Path(m.group(1)) if m else None

    if not img_path or not img_path.is_file():
        if cid:
            for base in [Path.home() / ".gemini" / "antigravity-cli", Path.home() / ".gemini" / "antigravity"]:
                bdir = base / "brain" / cid
                if bdir.is_dir():
                    candidates = list(bdir.glob("*.jpg")) + list(bdir.glob("*.png")) + list(bdir.glob("*.webp"))
                    if candidates:
                        candidates.sort(key=lambda p: -p.stat().st_mtime)
                        img_path = candidates[0]
                        break

    if not img_path or not img_path.is_file():
        raise ActionError("image_not_found", f"Image output not found: {response_text[:200]}")

    desc_m = re.search(r"\*\*Description\*\*:\s*([^\n]+)", response_text)
    description = desc_m.group(1).strip() if desc_m else ""

    raw_bytes = img_path.read_bytes()
    b64_data = base64.b64encode(raw_bytes).decode("ascii")
    mime, _ = mimetypes.guess_type(str(img_path))
    mime = mime or "image/jpeg"

    return {
        "ok": True,
        "path": str(img_path),
        "filename": img_path.name,
        "mime_type": mime,
        "size": len(raw_bytes),
        "data": b64_data,
        "prompt": prompt,
        "aspect_ratio": aspect_ratio,
        "description": description,
    }


def run(action: str, args: dict[str, Any]) -> dict[str, Any]:
    """Dispatch by the hub's action name (blocking; the service runs it in a thread)."""
    if action == "info":
        return info()
    if action == "shell":
        return shell(str(args.get("command", "")), args.get("cwd"), args.get("timeout"))
    if action == "files":
        return files(args.get("path"))
    if action == "file.get":
        return file_get(str(args.get("path", "")))
    if action == "file.put":
        return file_put(
            str(args.get("path", "")), str(args.get("data", "")), bool(args.get("force"))
        )
    if action == "open":
        return open_target(str(args.get("url", "")))
    if action == "screen":
        return screen()
    if action == "notify":
        return notify(str(args.get("text", "")), str(args.get("title") or "nanoMuse"))
    if action == "image.generate":
        return image_generate(args)
    raise ActionError("unknown_action", f"this computer does not do '{action}'")


__all__ = [
    "ACTIONS",
    "FILE_LIMIT",
    "ActionError",
    "expand",
    "file_get",
    "file_put",
    "files",
    "image_generate",
    "info",
    "notify",
    "open_target",
    "run",
    "screen",
    "shell",
]
