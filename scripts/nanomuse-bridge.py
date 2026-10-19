#!/usr/bin/env python3
"""nanoMuse Open Bridge: Programmatic control for external AI agents and scripts.

Allows reading and modifying all settings, SOUL prompt, agent memory,
custom providers, and importing multi-state avatars across Desktop and Android.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
import urllib.request
from pathlib import Path
from typing import Any
import time
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from PIL import Image

NANOMUSE_DIR = (
    Path.home() / ".nyanomuse" / "desktop"
    if (Path.home() / ".nyanomuse").exists()
    else Path.home() / ".nanomuse" / "desktop"
)
CONFIG_DIR = NANOMUSE_DIR / "nanomuse"
SOUL_PATH = NANOMUSE_DIR / "SOUL.md"
CREDENTIALS_PATH = NANOMUSE_DIR / ".credentials.yaml"
PROFILE_PATH = CONFIG_DIR / "profile.json"
CLOUD_PATH = CONFIG_DIR / "cloud.json"
DESKTOP_PATH = NANOMUSE_DIR / "desktop.json"
FACES_DIR = CONFIG_DIR / "faces"
SYNC_KEY_PATH = NANOMUSE_DIR / ".sync_key"
BACKUP_DIR = Path.home() / ".nyanomuse" / "backups" / "configs"


MOOD_ALIASES = {
    "idle": ("idle", "base", "normal", "stand", "default"),
    "working": ("work", "working", "busy", "type", "focus"),
    "waiting": ("wait", "waiting", "sleep", "rest", "pause"),
    "happy": ("happy", "done", "smile", "laugh", "cheer", "success"),
    "error": ("error", "fail", "sad", "cry", "sweat"),
}


def read_json(path: Path) -> dict[str, Any]:
    if not path.exists():
        return {}
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except Exception:
        return {}


def write_json(path: Path, data: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(".tmp")
    tmp.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    tmp.replace(path)


def get_relay_base() -> str:
    env_val = os.getenv("NYANOMUSE_RELAY_URL") or os.getenv("NANOMUSE_CLOUD_URL")
    if env_val:
        return env_val.rstrip("/")
    cfg = read_json(CLOUD_PATH)
    if cfg.get("baseURL"):
        return str(cfg["baseURL"]).rstrip("/")
    desk_cfg = read_json(DESKTOP_PATH)
    if desk_cfg.get("relayUrl"):
        return str(desk_cfg["relayUrl"]).rstrip("/")
    return ""


RELAY_BASE = get_relay_base()


def get_token() -> str:
    if not CREDENTIALS_PATH.exists():
        return ""
    text = CREDENTIALS_PATH.read_text(encoding="utf-8")
    for line in text.splitlines():
        if "NANOMUSE_CLOUD_TOKEN:" in line:
            return line.split(":", 1)[1].strip()
    return ""


def match_mood(filename: str) -> str | None:
    fn = filename.lower()
    for mood, aliases in MOOD_ALIASES.items():
        if any(alias in fn for alias in aliases):
            return mood
    return None


def square_crop_image(img: Image.Image, size: int = 512) -> Image.Image:
    w, h = img.size
    side = min(w, h)
    x = (w - side) // 2
    y = (h - side) // 2
    cropped = img.crop((x, y, x + side, y + side))
    return cropped.resize((size, size), Image.Resampling.LANCZOS)


def cmd_info(args: argparse.Namespace) -> None:
    profile = read_json(PROFILE_PATH)
    cloud = read_json(CLOUD_PATH)
    soul_exists = SOUL_PATH.exists()
    soul_preview = SOUL_PATH.read_text(encoding="utf-8")[:120].replace("\n", " ") if soul_exists else "(missing)"

    info = {
        "desktop": {
            "root": str(NANOMUSE_DIR),
            "soul_file": str(SOUL_PATH),
            "soul_preview": soul_preview,
            "profile": {
                "name": profile.get("name"),
                "avatar": profile.get("avatar"),
                "faceId": profile.get("faceId"),
                "style": profile.get("style"),
            },
            "cloud": {
                "accountId": cloud.get("account", {}).get("id") or cloud.get("sync", {}).get("accountId"),
                "token_configured": bool(get_token()),
                "providers": list(cloud.get("providers", {}).keys()),
            },
        },
        "relay": RELAY_BASE,
    }

    # Check ADB devices
    try:
        adb_out = subprocess.check_output(["adb", "devices"], text=True, stderr=subprocess.DEVNULL)
        devices = [line.split()[0] for line in adb_out.splitlines()[1:] if "\tdevice" in line]
        info["android_devices"] = devices
    except Exception:
        info["android_devices"] = []

    print(json.dumps(info, indent=2, ensure_ascii=False))


def cmd_soul(args: argparse.Namespace) -> None:
    if args.action == "get":
        if SOUL_PATH.exists():
            print(SOUL_PATH.read_text(encoding="utf-8"))
        else:
            print("# No SOUL.md found", file=sys.stderr)
    elif args.action == "set":
        content = args.content
        if not content and not sys.stdin.isatty():
            content = sys.stdin.read()
        elif content and os.path.isfile(content):
            content = Path(content).read_text(encoding="utf-8")
        if not content:
            print("Error: No content provided", file=sys.stderr)
            sys.exit(1)
        SOUL_PATH.write_text(content, encoding="utf-8")
        print(f"Updated {SOUL_PATH}")


def cmd_profile(args: argparse.Namespace) -> None:
    profile = read_json(PROFILE_PATH)
    if args.action == "get":
        print(json.dumps(profile, indent=2, ensure_ascii=False))
    elif args.action == "set":
        data = json.loads(args.json_data)
        profile.update(data)
        write_json(PROFILE_PATH, profile)
        print(f"Updated {PROFILE_PATH}")


def cmd_avatar_import(args: argparse.Namespace) -> None:
    src_path = Path(args.path)
    if not src_path.exists():
        print(f"Error: Path {src_path} not found", file=sys.stderr)
        sys.exit(1)

    work_dir = tempfile.mkdtemp(prefix="nanomuse_avatar_")
    try:
        if src_path.suffix.lower() == ".zip":
            shutil.unpack_archive(str(src_path), work_dir)
            inspect_dir = Path(work_dir)
        elif src_path.is_dir():
            inspect_dir = src_path
        else:
            # Single image file
            inspect_dir = Path(work_dir)
            shutil.copy(str(src_path), inspect_dir / "idle.png")

        files = list(inspect_dir.rglob("*"))
        image_files = [f for f in files if f.suffix.lower() in (".png", ".jpg", ".jpeg", ".webp") and not f.name.startswith(".")]

        if not image_files:
            print("Error: No valid images found", file=sys.stderr)
            sys.exit(1)

        recognized: dict[str, Image.Image] = {}
        for f in image_files:
            mood = match_mood(f.name)
            if mood and mood not in recognized:
                try:
                    img = Image.open(f).convert("RGBA")
                    recognized[mood] = square_crop_image(img, 512)
                except Exception as e:
                    print(f"Warning: Failed to load {f}: {e}", file=sys.stderr)

        if "idle" not in recognized:
            # Use first available image
            img = Image.open(image_files[0]).convert("RGBA")
            recognized["idle"] = square_crop_image(img, 512)

        # Fallback missing moods to idle
        for m in ("working", "waiting", "happy", "error"):
            if m not in recognized:
                recognized[m] = recognized["idle"]

        # Generate faceId from idle image
        idle_bytes = io_webp_bytes(recognized["idle"])
        face_id = hashlib.sha1(idle_bytes).hexdigest()[:12]

        target_face_dir = FACES_DIR / face_id
        target_face_dir.mkdir(parents=True, exist_ok=True)

        face_b64_map: dict[str, str] = {}
        for mood, img in recognized.items():
            out_file = target_face_dir / f"{mood}.webp"
            webp_data = io_webp_bytes(img)
            out_file.write_bytes(webp_data)
            face_b64_map[mood] = base64.b64encode(webp_data).decode("ascii")

        face_meta = {
            "description": args.description or "Custom imported avatar pack",
            "style": args.style or "custom",
            "from": "bridge",
        }
        write_json(target_face_dir / "face.json", face_meta)

        # Update profile.json
        profile = read_json(PROFILE_PATH)
        profile["avatar"] = "face"
        profile["faceId"] = face_id
        profile["style"] = args.style or profile.get("style", "custom")
        if args.name:
            profile["name"] = args.name
        write_json(PROFILE_PATH, profile)
        print(f"Applied avatar {face_id} to {PROFILE_PATH}")

        # Sync to relay if token available
        token = get_token()
        if token:
            try:
                sync_body = {
                    "device": "pc-bridge",
                    "avatar": "face",
                    "name": profile.get("name", "nanoMuse"),
                    "style": profile.get("style", "custom"),
                    "description": face_meta["description"],
                    "face": face_b64_map,
                }
                req = urllib.request.Request(
                    f"{RELAY_BASE}/v1/me/profile",
                    data=json.dumps(sync_body).encode("utf-8"),
                    headers={
                        "Authorization": f"Bearer {token}",
                        "Content-Type": "application/json",
                        "User-Agent": "nanoMuse-Bridge/1.0",
                    },
                    method="PUT",
                )
                with urllib.request.urlopen(req, timeout=10) as resp:
                    print(f"Relay synced successfully (HTTP {resp.status})")
            except Exception as e:
                print(f"Notice: Relay profile sync returned: {e}", file=sys.stderr)

        # Also push to Android if connected
        if args.sync_android:
            push_to_android_pack(src_path)

    finally:
        shutil.rmtree(work_dir, ignore_errors=True)


def io_webp_bytes(img: Image.Image) -> bytes:
    import io
    buf = io.BytesIO()
    img.save(buf, format="WEBP", quality=85)
    return buf.getvalue()


def push_to_android_pack(path: Path) -> None:
    try:
        adb_out = subprocess.check_output(["adb", "devices"], text=True)
        devices = [line.split()[0] for line in adb_out.splitlines()[1:] if "\tdevice" in line]
        if not devices:
            print("No Android device connected for sync", file=sys.stderr)
            return
        device = devices[0]
        remote_path = "/sdcard/Download/avatar_pack" + path.suffix
        subprocess.check_call(["adb", "-s", device, "push", str(path), remote_path])
        subprocess.check_call([
            "adb", "-s", device, "shell", "am", "broadcast",
            "-a", "top.lanrhyme.nyanomuse.IMPORT_AVATAR",
            "--es", "path", remote_path,
        ])
        print(f"Pushed and notified Android device {device}")
    except Exception as e:
        print(f"Android sync error: {e}", file=sys.stderr)


def cmd_android(args: argparse.Namespace) -> None:
    devices = [args.device] if args.device else []
    if not devices:
        adb_out = subprocess.check_output(["adb", "devices"], text=True)
        devices = [line.split()[0] for line in adb_out.splitlines()[1:] if "\tdevice" in line]
    if not devices:
        print("Error: No Android device found", file=sys.stderr)
        sys.exit(1)
    dev = devices[0]

    if args.action == "push-soul":
        text = args.text
        if os.path.isfile(text):
            text = Path(text).read_text(encoding="utf-8")
        # Push to remote file to avoid shell escaping issues with multiline text
        with tempfile.NamedTemporaryFile("w", encoding="utf-8", delete=False) as tf:
            tf.write(text)
            tmp_local = tf.name
        try:
            remote_tmp = "/sdcard/Download/SOUL.md"
            subprocess.check_call(["adb", "-s", dev, "push", tmp_local, remote_tmp])
            subprocess.check_call(["adb", "-s", dev, "shell", "am", "broadcast", "-a", "top.lanrhyme.nyanomuse.UPDATE_SOUL", "--es", "file", remote_tmp])
            print(f"Updated SOUL on {dev}")
        finally:
            if os.path.exists(tmp_local):
                os.unlink(tmp_local)
    elif args.action == "push-avatar":
        push_to_android_pack(Path(args.path))
    elif args.action == "reload":
        subprocess.check_call(["adb", "-s", dev, "shell", "am", "broadcast", "-a", "top.lanrhyme.nyanomuse.RELOAD_CONFIG"])
        print(f"Sent reload to {dev}")


def derive_sync_key(password: str, account_id: str) -> bytes:
    return hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), account_id.encode("utf-8"), 100_000, 32)


def get_account_id() -> str:
    token = get_token()
    if token and RELAY_BASE:
        try:
            req = urllib.request.Request(
                f"{RELAY_BASE}/v1/me",
                headers={"Authorization": f"Bearer {token}", "User-Agent": "nanoMuse-Bridge/1.0"},
            )
            with urllib.request.urlopen(req, timeout=5) as resp:
                data = json.loads(resp.read().decode("utf-8"))
                acc = data.get("account", {})
                acc_id = acc.get("id")
                if acc_id:
                    c = read_json(CLOUD_PATH)
                    c["account"] = acc
                    write_json(CLOUD_PATH, c)
                    return str(acc_id)
        except Exception:
            pass
    c = read_json(CLOUD_PATH)
    return str(c.get("account", {}).get("id") or c.get("sync", {}).get("accountId") or "")



def get_sync_key(override_pw: str | None = None) -> bytes:
    if override_pw:
        acc_id = get_account_id()
        if not acc_id:
            raise ValueError("No account_id found in cloud.json")
        key = derive_sync_key(override_pw, acc_id)
        SYNC_KEY_PATH.parent.mkdir(parents=True, exist_ok=True)
        SYNC_KEY_PATH.write_text(base64.b64encode(key).decode("ascii"), encoding="utf-8")
        return key

    env_pw = os.getenv("NYANOMUSE_SYNC_PASSWORD")
    if env_pw:
        acc_id = get_account_id()
        if acc_id:
            return derive_sync_key(env_pw, acc_id)

    env_key = os.getenv("NYANOMUSE_SYNC_KEY")
    if env_key:
        return base64.b64decode(env_key)

    if SYNC_KEY_PATH.exists():
        raw = SYNC_KEY_PATH.read_text(encoding="utf-8").strip()
        if raw:
            return base64.b64decode(raw)

    raise ValueError("Sync encryption key not found. Run 'sync-config set-password <password>' first")


def encrypt_config_payload(payload: dict, key: bytes) -> tuple[str, str, str]:
    iv = os.urandom(12)
    plaintext = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    aes = AESGCM(key)
    full = aes.encrypt(iv, plaintext, None)
    ct = base64.b64encode(full[:-16]).decode("ascii")
    tag = base64.b64encode(full[-16:]).decode("ascii")
    iv_b64 = base64.b64encode(iv).decode("ascii")
    return ct, iv_b64, tag


def decrypt_config_payload(ct_b64: str, iv_b64: str, tag_b64: str, key: bytes) -> dict:
    ct = base64.b64decode(ct_b64)
    tag = base64.b64decode(tag_b64)
    iv = base64.b64decode(iv_b64)
    aes = AESGCM(key)
    plaintext = aes.decrypt(iv, ct + tag, None)
    return json.loads(plaintext.decode("utf-8"))


def gather_local_config() -> dict:
    cloud = read_json(CLOUD_PATH)
    creds_text = CREDENTIALS_PATH.read_text(encoding="utf-8") if CREDENTIALS_PATH.exists() else ""
    creds_map: dict[str, str] = {}
    for line in creds_text.splitlines():
        line = line.strip()
        if ":" in line and not line.startswith("#"):
            k, v = line.split(":", 1)
            creds_map[k.strip()] = v.strip().strip("'\"")

    providers_out = []
    for pid, pdata in cloud.get("providers", {}).items():
        key_ref = pdata.get("keyRef", "")
        api_key = creds_map.get(key_ref, "")
        providers_out.append({
            "id": pid,
            "label": pdata.get("label", pid),
            "providerType": pdata.get("provider", pid),
            "protocol": pdata.get("protocol", "openai"),
            "baseURL": pdata.get("baseURL", ""),
            "apiKey": api_key,
            "capabilities": pdata.get("capabilities", ["chat"]),
            "models": pdata.get("models", []),
            "updated_at": pdata.get("at", int(time.time() * 1000)),
        })

    soul_body = SOUL_PATH.read_text(encoding="utf-8") if SOUL_PATH.exists() else ""
    profile = read_json(PROFILE_PATH)

    slots = {
        "hands": {
            "providerId": cloud.get("handsProvider", ""),
            "modelId": cloud.get("handsModel", ""),
            "updated_at": int(time.time() * 1000),
        }
    }

    return {
        "version": 1,
        "updated_at": int(time.time() * 1000),
        "device": "desktop",
        "providers": providers_out,
        "slots": slots,
        "soul": {
            "name": profile.get("name", "nanoMuse"),
            "style": profile.get("style", "custom"),
            "lang": "auto",
            "body": soul_body,
            "updated_at": int(time.time() * 1000),
        },
    }


def backup_config(cfg: dict) -> Path:
    BACKUP_DIR.mkdir(parents=True, exist_ok=True)
    ts = int(time.time() * 1000)
    out_file = BACKUP_DIR / f"config_{ts}.json"
    write_json(out_file, cfg)
    return out_file


def apply_remote_config(remote_cfg: dict) -> dict:
    local_cfg = gather_local_config()
    backup_file = backup_config(local_cfg)

    cloud = read_json(CLOUD_PATH)
    providers = cloud.setdefault("providers", {})
    new_creds: dict[str, str] = {}

    for rp in remote_cfg.get("providers", []):
        pid = rp.get("id") or rp.get("providerType") or "custom"
        local_p = providers.get(pid)
        local_at = local_p.get("at", 0) if local_p else 0
        remote_at = rp.get("updated_at", 0)

        # Attribute-level LWW
        if local_p is None or remote_at >= local_at:
            key_ref = f"NANOMUSE_KEY_{pid.upper()}"
            providers[pid] = {
                "id": pid,
                "provider": rp.get("providerType", pid),
                "label": rp.get("label", pid),
                "protocol": rp.get("protocol", "openai"),
                "baseURL": rp.get("baseURL", ""),
                "keyRef": key_ref,
                "capabilities": rp.get("capabilities", ["chat"]),
                "models": rp.get("models", []),
                "at": remote_at or int(time.time() * 1000),
            }
            if rp.get("apiKey"):
                new_creds[key_ref] = rp["apiKey"]

    # Update slots
    remote_slots = remote_cfg.get("slots", {})
    if "hands" in remote_slots:
        cloud["handsModel"] = remote_slots["hands"].get("modelId", cloud.get("handsModel", ""))
        cloud["handsProvider"] = remote_slots["hands"].get("providerId", cloud.get("handsProvider", ""))

    write_json(CLOUD_PATH, cloud)

    # Update credentials.yaml
    if new_creds and CREDENTIALS_PATH.exists():
        lines = CREDENTIALS_PATH.read_text(encoding="utf-8").splitlines()
        updated_lines = []
        has_refs = False
        existing_keys = set()
        for line in lines:
            if line.strip() == "refs:":
                has_refs = True
            for k in new_creds:
                if line.strip().startswith(f"{k}:"):
                    existing_keys.add(k)
                    line = f"  {k}: {new_creds[k]}"
            updated_lines.append(line)
        if not has_refs:
            updated_lines.append("refs:")
        for k, v in new_creds.items():
            if k not in existing_keys:
                updated_lines.append(f"  {k}: {v}")
        CREDENTIALS_PATH.write_text("\n".join(updated_lines) + "\n", encoding="utf-8")

    # Update SOUL.md if remote soul is newer
    remote_soul = remote_cfg.get("soul", {})
    if remote_soul.get("body") and remote_soul.get("updated_at", 0) >= local_cfg.get("soul", {}).get("updated_at", 0):
        SOUL_PATH.write_text(remote_soul["body"], encoding="utf-8")

    return {"backup": str(backup_file), "providers_count": len(providers)}


def cmd_sync_config(args: argparse.Namespace) -> None:
    action = args.action
    token = get_token()
    if not token and action in ("push", "pull"):
        print("Error: No relay token configured in .credentials.yaml", file=sys.stderr)
        sys.exit(1)

    pw_arg = args.password or args.pos_password
    if action == "set-password":
        if not pw_arg:
            print("Error: Password required", file=sys.stderr)
            sys.exit(1)
        key = get_sync_key(override_pw=pw_arg)
        print(f"Sync encryption key derived and saved to {SYNC_KEY_PATH}")
        return

    if action == "status":
        acc_id = get_account_id()
        has_key = SYNC_KEY_PATH.exists()
        print(f"Relay Base: {RELAY_BASE}")
        print(f"Account ID: {acc_id}")
        print(f"E2EE Key Saved: {has_key}")
        if token:
            try:
                req = urllib.request.Request(
                    f"{RELAY_BASE}/v1/sync/config",
                    headers={"Authorization": f"Bearer {token}", "User-Agent": "nanoMuse-Bridge/1.0"},
                )
                with urllib.request.urlopen(req, timeout=5) as resp:
                    data = json.loads(resp.read().decode("utf-8"))
                    print(f"Remote Revision: {data.get('rev', 0)} (updated_at: {data.get('updated_at', 0)})")
            except Exception as e:
                print(f"Remote Status: {e}")
        return

    key = get_sync_key(pw_arg)

    if action == "push":
        local_cfg = gather_local_config()
        # Fetch remote rev first
        client_rev = 0
        try:
            req = urllib.request.Request(
                f"{RELAY_BASE}/v1/sync/config",
                headers={"Authorization": f"Bearer {token}", "User-Agent": "nanoMuse-Bridge/1.0"},
            )
            with urllib.request.urlopen(req, timeout=5) as resp:
                data = json.loads(resp.read().decode("utf-8"))
                client_rev = int(data.get("rev", 0))
        except Exception:
            pass

        ct, iv, tag = encrypt_config_payload(local_cfg, key)
        body = {
            "device": "desktop-bridge",
            "rev": client_rev,
            "ciphertext": ct,
            "iv": iv,
            "tag": tag,
        }
        req = urllib.request.Request(
            f"{RELAY_BASE}/v1/sync/config",
            data=json.dumps(body).encode("utf-8"),
            headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json", "User-Agent": "nanoMuse-Bridge/1.0"},
            method="PUT",
        )
        with urllib.request.urlopen(req, timeout=10) as resp:
            res = json.loads(resp.read().decode("utf-8"))
            print(f"Pushed E2EE config successfully (Revision {res.get('rev')}, {len(local_cfg['providers'])} providers)")

    elif action == "pull":
        req = urllib.request.Request(
            f"{RELAY_BASE}/v1/sync/config",
            headers={"Authorization": f"Bearer {token}", "User-Agent": "nanoMuse-Bridge/1.0"},
        )
        with urllib.request.urlopen(req, timeout=10) as resp:
            data = json.loads(resp.read().decode("utf-8"))
        rev = int(data.get("rev", 0))
        if rev == 0 or not data.get("ciphertext"):
            print("Notice: No remote config found on relay")
            return
        remote_cfg = decrypt_config_payload(data["ciphertext"], data["iv"], data["tag"], key)
        res = apply_remote_config(remote_cfg)
        print(f"Pulled & merged Revision {rev}: {res['providers_count']} providers active (backup saved to {res['backup']})")



def main() -> None:
    parser = argparse.ArgumentParser(description="nanoMuse Open Bridge for External AI")
    sub = parser.add_subparsers(dest="cmd", required=True)

    # info
    sub.add_parser("info", help="Get summary of all local settings, SOUL, and connected devices")

    # soul
    p_soul = sub.add_parser("soul", help="Read or write SOUL.md")
    p_soul.add_argument("action", choices=["get", "set"])
    p_soul.add_argument("content", nargs="?", default="")

    # profile
    p_prof = sub.add_parser("profile", help="Read or update profile.json")
    p_prof.add_argument("action", choices=["get", "set"])
    p_prof.add_argument("json_data", nargs="?", default="{}")

    # avatar import
    p_av = sub.add_parser("avatar-import", help="Import avatar pack (zip, directory, or image)")
    p_av.add_argument("path", help="Path to ZIP, directory, or image")
    p_av.add_argument("--name", help="Agent name")
    p_av.add_argument("--description", help="Appearance description")
    p_av.add_argument("--style", default="custom", help="Art style tag")
    p_av.add_argument("--sync-android", action="store_true", help="Also sync to connected Android device")

    # android
    p_and = sub.add_parser("android", help="Interact with connected Android device")
    p_and.add_argument("action", choices=["push-soul", "push-avatar", "reload"])
    p_and.add_argument("arg", nargs="?", default="")
    p_and.add_argument("--device", help="Specific ADB device serial")

    # sync-config
    p_sync = sub.add_parser("sync-config", help="E2EE Providers, Models & Config Sync")
    p_sync.add_argument("action", choices=["push", "pull", "status", "set-password"])
    p_sync.add_argument("pos_password", nargs="?", default="", help="Account password")
    p_sync.add_argument("--password", help="Account password for key derivation")

    args = parser.parse_args()

    if args.cmd == "info":
        cmd_info(args)
    elif args.cmd == "soul":
        cmd_soul(args)
    elif args.cmd == "profile":
        cmd_profile(args)
    elif args.cmd == "avatar-import":
        cmd_avatar_import(args)
    elif args.cmd == "android":
        if args.action == "push-soul":
            args.text = args.arg
        elif args.action == "push-avatar":
            args.path = args.arg
        cmd_android(args)
    elif args.cmd == "sync-config":
        cmd_sync_config(args)



if __name__ == "__main__":
    main()
