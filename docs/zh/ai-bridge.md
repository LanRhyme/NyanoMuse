# 外部 AI 桥接与自由配置指南

本项目支持外部 AI 代理（如 Antigravity、Claude Code、Cursor、本地脚本与 LLM）无沙箱壁垒地读取、修改与热重载系统配置、人格设定、记忆库以及立绘形象

## 1. 架构概览与存储分布

- 桌面端运行时数据统一归档于 `~/.nanomuse/desktop/`
  - `SOUL.md`: 智能体核心人格设定、系统提示词与语言风格
  - `nanomuse/profile.json`: 形象显示状态、名称、头像类型与 faceId
  - `nanomuse/cloud.json`: 自建中继账号、同步游标与模型提供商配置
  - `nanomuse/faces/<faceId>/`: 5 状态 WebP 立绘资源（idle、working、waiting、happy、error）与元数据
  - `desktop.json`: 桌面端窗口设置、毛玻璃特效与自适应主题
- 移动端数据位于应用内部私有目录与 `/data/data/io.github.nanomuse.app/`
  - 支持通过 ADB 广播机制动态注入 SOUL、立绘包与中继配置

## 2. CLI 桥接工具（nanomuse-bridge）

脚本已软链接至 `~/.local/bin/nanomuse-bridge`，支持以下子命令：

### 查看全量状态
```bash
nanomuse-bridge info
```

### 读写人格设定（SOUL.md）
```bash
# 读取当前设定
nanomuse-bridge soul get

# 更新设定（支持传入文本或文件路径）
nanomuse-bridge soul set /path/to/new_soul.md
nanomuse-bridge soul set "--- name: 'nanoMeow' --- ..."
```

### 导入多状态立绘包
支持 ZIP 压缩包、目录或单张图片，自动识别 idle、working、waiting、happy、error 状态并裁切优化为 512x512 WebP，同步更新本地并推送到自建中继
```bash
nanomuse-bridge avatar-import /path/to/avatar_pack.zip --name "NyanoMuse" --description "黑猫形象" --sync-android
```

### 移动端联动与热更新
通过 ADB 向连接的 Android 设备分发配置变更：
```bash
# 向手机推送并更新 SOUL 设定
nanomuse-bridge android push-soul /path/to/SOUL.md

# 向手机分发并导入立绘包
nanomuse-bridge android push-avatar /path/to/avatar_pack.zip

# 触发手机重新拉取云端与本地缓存
nanomuse-bridge android reload
```

## 3. Android 端外部广播协议

应用注册了 `io.github.nanomuse.bridge.ExternalAiReceiver` 广播接收器，外部工具或 ADB 均可直接调用：

- `io.github.nanomuse.UPDATE_SOUL`: 附加参数 `--es text "<内容>"` 或 `--es file "</sdcard/path>"`
- `io.github.nanomuse.IMPORT_AVATAR`: 附加参数 `--es path "</sdcard/pack.zip>"`
- `io.github.nanomuse.UPDATE_RELAY`: 附加参数 `--es url "<中继地址>"` 与 `--es token "<凭据>"`
- `io.github.nanomuse.RELOAD_CONFIG`: 无附加参数，直接触发热重载
