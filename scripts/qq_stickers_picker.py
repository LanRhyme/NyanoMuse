#!/usr/bin/env python3
"""
QQ Sticker Picker & Labeler for nanoMuse
Provides both:
1. Discovery/Picking: Visual grid of QQ cache emojis to export.
2. Labeling/Tagging: Name and tag exported stickers, with one-click ADB sync to phone.
"""

import os
import sys
import glob
import json
import time
import hashlib
import mimetypes
import subprocess
from http.server import HTTPServer, BaseHTTPRequestHandler
from urllib.parse import urlparse

MANIFEST_PATH = os.path.abspath('data/stickers/manifest.json')
EXPORT_DIR = os.path.abspath('data/stickers')

STICKERS = []
STICKERS_BY_ID = {}

def detect_image_format(data: bytes) -> str:
    if data.startswith(b'GIF87a') or data.startswith(b'GIF89a'):
        return 'gif'
    elif data.startswith(b'\x89PNG\r\n\x1a\n'):
        return 'png'
    elif data.startswith(b'\xff\xd8\xff'):
        return 'jpg'
    elif data.startswith(b'RIFF') and b'WEBP' in data[:16]:
        return 'webp'
    return 'bin'

def load_manifest():
    if not os.path.isfile(MANIFEST_PATH):
        return []
    try:
        with open(MANIFEST_PATH, 'r', encoding='utf-8') as f:
            data = json.load(f)
            return data.get('stickers', [])
    except Exception as e:
        print('Error reading manifest:', e)
        return []

def save_manifest(stickers_list):
    os.makedirs(EXPORT_DIR, exist_ok=True)
    with open(MANIFEST_PATH, 'w', encoding='utf-8') as f:
        json.dump({
            'count': len(stickers_list),
            'updated_at': time.time(),
            'stickers': stickers_list
        }, f, ensure_ascii=False, indent=2)

def scan_qq_stickers():
    global STICKERS, STICKERS_BY_ID
    personal_candidates = [
        os.path.expanduser('~/.config/QQ/nt_qq_*/nt_data/Emoji/personal_emoji/Ori'),
        '/mnt/WindowsC/Users/*/Documents/Tencent Files/*/nt_qq/nt_data/Emoji/personal_emoji/Ori',
        '/mnt/WindowsD/Users/*/Documents/Tencent Files/*/nt_qq/nt_data/Emoji/personal_emoji/Ori',
        '/mnt/WindowsC/Users/*/Documents/Tencent Files/*/Image/CustomFace',
        '/mnt/WindowsD/Users/*/Documents/Tencent Files/*/Image/CustomFace',
    ]

    recv_candidates = [
        os.path.expanduser('~/.config/QQ/nt_qq_*/nt_data/Emoji/emoji-recv/*/Ori'),
    ]

    found_files = []
    for pattern in personal_candidates:
        for folder in glob.glob(pattern):
            if not os.path.isdir(folder): continue
            for entry in os.scandir(folder):
                if entry.is_file():
                    found_files.append((entry.path, 'personal'))

    for pattern in recv_candidates:
        for folder in glob.glob(pattern):
            if not os.path.isdir(folder): continue
            for entry in os.scandir(folder):
                if entry.is_file():
                    found_files.append((entry.path, 'recv'))

    seen_hashes = {}
    stickers = []

    for path, category in found_files:
        try:
            sz = os.path.getsize(path)
            if sz < 300:
                continue
            with open(path, 'rb') as fp:
                head = fp.read(32)
                fmt = detect_image_format(head)
                if fmt == 'bin':
                    continue
                fp.seek(0)
                file_hash = hashlib.sha256(fp.read()).hexdigest()

            if file_hash in seen_hashes:
                if category == 'personal' and seen_hashes[file_hash]['category'] == 'recv':
                    seen_hashes[file_hash]['category'] = 'personal'
                continue

            mtime = os.path.getmtime(path)
            item = {
                'id': file_hash[:16],
                'hash': file_hash,
                'path': path,
                'format': fmt,
                'size': sz,
                'mtime': mtime,
                'date_str': time.strftime('%Y-%m-%d %H:%M', time.localtime(mtime)),
                'category': category,
                'source': 'Linux QQ' if path.startswith(os.path.expanduser('~')) else 'Windows QQ',
            }
            seen_hashes[file_hash] = item
            stickers.append(item)
        except Exception:
            continue

    stickers.sort(key=lambda x: x['mtime'], reverse=True)
    STICKERS = stickers
    STICKERS_BY_ID = {s['id']: s for s in stickers}

def push_manifest_to_phone():
    # Detect connected phone via adb
    try:
        proc = subprocess.run(['adb', 'devices'], capture_output=True, text=True, check=True)
        lines = [l.strip() for l in proc.stdout.splitlines() if l.strip() and not l.startswith('List')]
        devices = [l.split()[0] for l in lines if 'device' in l]
        if not devices:
            return False, "未检测到 ADB 在线设备"
        
        target_dev = 'ed3fdd92' if 'ed3fdd92' in devices else devices[0]
        remotes = [
            '/sdcard/Android/data/top.lanrhyme.nyanomuse/files/stickers/manifest.json',
            '/sdcard/Pictures/NyanoMuse/stickers/manifest.json'
        ]
        for remote in remotes:
            subprocess.run(['adb', '-s', target_dev, 'push', MANIFEST_PATH, remote], check=True)
        return True, f"已成功推送到设备 {target_dev}"
    except Exception as e:
        return False, str(e)

HTML_PAGE = """<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>nanoMuse 表情包命名与标注</title>
<style>
:root {
  --bg: #18181b;
  --card-bg: #27272a;
  --card-hover: #323238;
  --border: #3f3f46;
  --border-focus: #10b981;
  --text: #f4f4f5;
  --text-muted: #a1a1aa;
  --accent: #10b981;
  --accent-hover: #059669;
}
* { box-sizing: border-box; margin: 0; padding: 0; }
body {
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "PingFang SC", sans-serif;
  background: var(--bg);
  color: var(--text);
  padding: 24px;
  padding-bottom: 96px;
}
header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
  flex-wrap: wrap;
  gap: 16px;
}
h1 { font-size: 22px; font-weight: 600; display: flex; align-items: center; gap: 10px; }
.badge-count {
  background: #3f3f46;
  color: #38bdf8;
  font-size: 13px;
  padding: 2px 8px;
  border-radius: 12px;
  font-weight: normal;
}
.sub { font-size: 14px; color: var(--text-muted); margin-top: 4px; }
.toolbar {
  display: flex;
  gap: 12px;
  align-items: center;
  flex-wrap: wrap;
  background: var(--card-bg);
  padding: 12px 18px;
  border-radius: 12px;
  margin-bottom: 24px;
  border: 1px solid var(--border);
}
button {
  background: #3f3f46;
  color: #fff;
  border: 1px solid #52525b;
  padding: 7px 16px;
  border-radius: 8px;
  cursor: pointer;
  font-size: 13px;
  font-weight: 500;
  transition: all 0.15s;
}
button:hover { background: #52525b; }
button.primary {
  background: var(--accent);
  border-color: var(--accent);
}
button.primary:hover { background: var(--accent-hover); }
select, input[type="text"] {
  background: #18181b;
  color: var(--text);
  border: 1px solid var(--border);
  padding: 7px 12px;
  border-radius: 8px;
  font-size: 13px;
}
input[type="text"]:focus {
  outline: none;
  border-color: var(--border-focus);
}
.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(170px, 1fr));
  gap: 16px;
}
.card {
  background: var(--card-bg);
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 10px;
  display: flex;
  flex-direction: column;
  position: relative;
  transition: all 0.15s;
}
.card:hover { border-color: #52525b; }
.card.named {
  border-color: #059669;
  background: #1c2622;
}
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
  font-size: 12px;
  font-weight: 600;
  color: var(--text-muted);
}
.card.named .card-header .id-tag {
  color: #34d399;
}
.thumb-wrap {
  width: 100%;
  aspect-ratio: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  border-radius: 8px;
  background: rgba(0,0,0,0.3);
  margin-bottom: 10px;
}
.thumb {
  max-width: 100%;
  max-height: 100%;
  object-fit: contain;
}
.name-input {
  width: 100%;
  font-size: 13px;
  padding: 6px 10px;
  border-radius: 6px;
  border: 1px solid var(--border);
  background: #18181b;
  color: #fff;
  transition: border-color 0.15s;
}
.name-input:focus {
  border-color: var(--accent);
}
.badge {
  padding: 2px 5px;
  border-radius: 4px;
  font-size: 10px;
  font-weight: 600;
  text-transform: uppercase;
}
.badge.gif { background: rgba(245, 158, 11, 0.2); color: #fbbf24; }
.badge.png { background: rgba(56, 189, 248, 0.2); color: #38bdf8; }
.badge.jpg { background: rgba(148, 163, 184, 0.2); color: #94a3b8; }
.footer-bar {
  position: fixed;
  bottom: 0;
  left: 0;
  right: 0;
  background: rgba(24, 24, 27, 0.95);
  backdrop-filter: blur(8px);
  border-top: 1px solid var(--border);
  padding: 14px 28px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  z-index: 100;
}
.status-info {
  display: flex;
  gap: 16px;
  font-size: 14px;
  color: var(--text-muted);
}
.status-info strong {
  color: var(--accent);
}
#toast {
  position: fixed;
  top: 24px;
  right: 24px;
  background: var(--accent);
  color: white;
  padding: 10px 20px;
  border-radius: 8px;
  font-size: 14px;
  opacity: 0;
  transition: opacity 0.3s;
  pointer-events: none;
  z-index: 1000;
  box-shadow: 0 4px 12px rgba(0,0,0,0.4);
}
</style>
</head>
<body>

<header>
  <div>
    <h1>nanoMuse 表情包命名标注 <span class="badge-count" id="total-badge">156 张</span></h1>
    <div class="sub">在卡片下方填入简短名称/场景（如 <strong>摸鱼猫猫</strong>、<strong>问号流汗</strong>），AI 将在匹配氛围时主动发送</div>
  </div>
  <div style="display:flex; gap:10px;">
    <button onclick="saveAll(false)">保存修改</button>
    <button class="primary" onclick="saveAll(true)">保存并推送到手机</button>
  </div>
</header>

<div class="toolbar">
  <label style="font-size:13px; color:var(--text-muted)">筛选：
    <select id="status-filter" onchange="renderGrid()">
      <option value="all">全部表情</option>
      <option value="unnamed">仅未命名</option>
      <option value="named">仅已命名</option>
      <option value="gif">仅 GIF 动图</option>
    </select>
  </label>
  <span style="color:#3f3f46">|</span>
  <input type="text" id="search-input" placeholder="搜索已填写的标签..." oninput="renderGrid()" style="width: 200px;">
  <span style="font-size:12px; color:var(--text-muted); margin-left:auto;">
    提示：支持快捷键 <strong>Tab</strong> 快速跳到下一个输入框，<strong>Ctrl + S</strong> 保存
  </span>
</div>

<div class="grid" id="grid"></div>

<div class="footer-bar">
  <div class="status-info">
    <span>已命名: <strong id="named-count">0</strong> / <span id="total-count">0</span></span>
  </div>
  <div style="display:flex; gap:12px;">
    <button onclick="saveAll(false)">保存本地</button>
    <button class="primary" onclick="saveAll(true)">保存并推送到手机 (ADB)</button>
  </div>
</div>

<div id="toast"></div>

<script>
let manifest = __MANIFEST_DATA__;

function showToast(msg, isError=false) {
  const t = document.getElementById('toast');
  t.textContent = msg;
  t.style.background = isError ? '#ef4444' : '#10b981';
  t.style.opacity = '1';
  setTimeout(() => t.style.opacity = '0', 2500);
}

function updateStats() {
  const named = manifest.filter(s => s.name && s.name.trim()).length;
  document.getElementById('named-count').textContent = named;
  document.getElementById('total-count').textContent = manifest.length;
  document.getElementById('total-badge').textContent = manifest.length + ' 张';
}

function onNameChange(id, val) {
  const item = manifest.find(s => s.id === id);
  if (item) {
    item.name = val.trim();
    const card = document.getElementById('card-' + id);
    if (card) card.classList.toggle('named', Boolean(item.name));
    updateStats();
  }
}

function renderGrid() {
  const filter = document.getElementById('status-filter').value;
  const keyword = document.getElementById('search-input').value.trim().toLowerCase();
  const grid = document.getElementById('grid');
  grid.innerHTML = '';

  manifest.forEach((s, idx) => {
    const isNamed = Boolean(s.name && s.name.trim());
    if (filter === 'unnamed' && isNamed) return;
    if (filter === 'named' && !isNamed) return;
    if (filter === 'gif' && s.format !== 'gif') return;
    if (keyword && (!s.name || !s.name.toLowerCase().includes(keyword))) return;

    const card = document.createElement('div');
    card.className = 'card' + (isNamed ? ' named' : '');
    card.id = 'card-' + s.id;

    const seqId = String(idx + 1).padStart(3, '0');
    card.innerHTML = `
      <div class="card-header">
        <span class="id-tag">[${seqId}]</span>
        <span class="badge ${s.format}">${s.format}</span>
      </div>
      <div class="thumb-wrap">
        <img class="thumb" src="/file/${s.filename}" loading="lazy" alt="sticker">
      </div>
      <input type="text"
             class="name-input"
             value="${s.name || ''}"
             placeholder="填入名称/场景..."
             oninput="onNameChange('${s.id}', this.value)">
    `;
    grid.appendChild(card);
  });
  updateStats();
}

async function saveAll(syncPhone=false) {
  try {
    const res = await fetch('/api/save_tags', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        stickers: manifest,
        sync_phone: syncPhone
      })
    });
    const data = await res.json();
    if (data.ok) {
      if (syncPhone) {
        showToast(data.message || '保存成功并已推送到手机！');
      } else {
        showToast('标签保存成功！');
      }
    } else {
      showToast('保存失败：' + (data.error || '未知错误'), true);
    }
  } catch (e) {
    showToast('请求出错：' + e.message, true);
  }
}

window.addEventListener('keydown', (e) => {
  if ((e.ctrlKey || e.metaKey) && e.key === 's') {
    e.preventDefault();
    saveAll(true);
  }
});

renderGrid();
</script>
</body>
</html>
"""

class LabelServer(BaseHTTPRequestHandler):
    def do_GET(self):
        url = urlparse(self.path)
        if url.path == '/' or url.path == '/index.html':
            manifest_data = load_manifest()
            content = HTML_PAGE.replace('__MANIFEST_DATA__', json.dumps(manifest_data))
            self.send_response(200)
            self.send_header('Content-Type', 'text/html; charset=utf-8')
            self.end_headers()
            self.wfile.write(content.encode('utf-8'))
        elif url.path.startswith('/file/'):
            filename = url.path[len('/file/'):]
            filepath = os.path.join(EXPORT_DIR, filename)
            if not os.path.isfile(filepath):
                self.send_response(404)
                self.end_headers()
                return
            mime, _ = mimetypes.guess_type(filepath)
            self.send_response(200)
            self.send_header('Content-Type', mime or 'application/octet-stream')
            self.send_header('Cache-Control', 'max-age=86400')
            self.end_headers()
            with open(filepath, 'rb') as fp:
                self.wfile.write(fp.read())
        else:
            self.send_response(404)
            self.end_headers()

    def do_POST(self):
        url = urlparse(self.path)
        if url.path == '/api/save_tags':
            length = int(self.headers.get('Content-Length', 0))
            body = self.rfile.read(length)
            try:
                data = json.loads(body.decode('utf-8'))
                stickers_list = data.get('stickers', [])
                sync_phone = data.get('sync_phone', False)

                save_manifest(stickers_list)

                msg = "本地标签保存成功"
                if sync_phone:
                    ok, phone_msg = push_manifest_to_phone()
                    if ok:
                        msg = f"已保存并推送到手机！({phone_msg})"
                    else:
                        msg = f"本地已保存，但推送手机失败：{phone_msg}"

                self.send_response(200)
                self.send_header('Content-Type', 'application/json')
                self.end_headers()
                self.wfile.write(json.dumps({'ok': True, 'message': msg}).encode('utf-8'))
            except Exception as e:
                self.send_response(500)
                self.send_header('Content-Type', 'application/json')
                self.end_headers()
                self.wfile.write(json.dumps({'ok': False, 'error': str(e)}).encode('utf-8'))
        else:
            self.send_response(404)
            self.end_headers()

    def log_message(self, format, *args):
        pass

def main():
    port = 3842
    server = HTTPServer(('127.0.0.1', port), LabelServer)
    url = f"http://127.0.0.1:{port}"
    print(f"\n=======================================================")
    print(f" nanoMuse 表情包命名标注服务已启动: {url}")
    print(f" 浏览器打开该地址即可直接逐张输入名称/标签！")
    print(f" 支持 Tab 切换，输入后点击「保存并推送到手机」即可！")
    print(f"=======================================================\n")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n服务已退出")

if __name__ == '__main__':
    main()
