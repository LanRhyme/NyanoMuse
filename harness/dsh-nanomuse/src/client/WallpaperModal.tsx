/**
 * Wallpaper & Background configuration modal for nanoMuse desktop.
 * Provides acrylic Morandi live preview, Noctalia system wallpaper sync,
 * custom image upload, and real-time blur and dim sliders.
 */
import { createElement as h, Fragment, useEffect, useRef, useState, type ReactNode } from 'react'
import type { Translate } from './api.ts'
import { IconCheck, IconImage, IconPaperclip, IconRefresh } from './icons.tsx'
import { Sheet } from './ui.tsx'
import {
  resetWallpaperConfig,
  saveWallpaperConfig,
  useWallpaperConfig,
  useWallpaperModalOpen,
  wallpaperBus,
  type WallpaperConfig,
} from './wallpaper.ts'

export function WallpaperModal({ t }: { t: Translate }): ReactNode {
  const isOpen = useWallpaperModalOpen()
  if (!isOpen) return null
  return h(WallpaperSheet, { t, onClose: () => wallpaperBus.close() })
}

function WallpaperSheet({ t, onClose }: { t: Translate; onClose(): void }): ReactNode {
  const config = useWallpaperConfig()
  const [enabled, setEnabled] = useState(config.enabled)
  const [useSystem, setUseSystem] = useState(config.useSystem)
  const [blur, setBlur] = useState(config.blur)
  const [dim, setDim] = useState(config.dim)
  const [uploading, setUploading] = useState(false)
  const [savedToast, setSavedToast] = useState(false)
  const fileInputRef = useRef<HTMLInputElement>(null)

  useEffect(() => {
    setEnabled(config.enabled)
    setUseSystem(config.useSystem)
    setBlur(config.blur)
    setDim(config.dim)
  }, [config])

  const handleToggleEnabled = async () => {
    const next = !enabled
    setEnabled(next)
    await saveWallpaperConfig({ enabled: next })
  }

  const handleUseSystem = async () => {
    setUseSystem(true)
    setEnabled(true)
    await saveWallpaperConfig({ enabled: true, useSystem: true })
  }

  const handleFileChange = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0]
    if (!file) return
    setUploading(true)
    try {
      const reader = new FileReader()
      reader.onload = async () => {
        const dataUrl = reader.result as string
        if (dataUrl) {
          await saveWallpaperConfig({ enabled: true, useSystem: false, imageData: dataUrl })
          setUseSystem(false)
          setEnabled(true)
        }
        setUploading(false)
      }
      reader.readAsDataURL(file)
    } catch {
      setUploading(false)
    }
  }

  const handleBlurChange = (val: number) => {
    setBlur(val)
    saveWallpaperConfig({ blur: val })
  }

  const handleDimChange = (val: number) => {
    setDim(val)
    saveWallpaperConfig({ dim: val })
  }

  const handleReset = async () => {
    await resetWallpaperConfig()
  }

  const previewImgUrl = `/nanomuse/cloud/wallpaper/image?v=${Date.now()}`

  const footer = h(
    'div',
    { className: 'nm-sheet-actions', style: { display: 'flex', justifyContent: 'space-between', alignItems: 'center', width: '100%' } },
    h(
      'button',
      {
        type: 'button',
        className: 'nm-pill nm-pill-ghost nm-pill-sm',
        style: { gap: 6 },
        onClick: handleReset,
        title: '清除自定义壁纸并恢复默认纯色背景',
      },
      h(IconRefresh, { size: 14 }),
      h('span', null, '恢复默认')
    ),
    h(
      'button',
      {
        type: 'button',
        className: 'nm-pill nm-pill-sm',
        onClick: onClose,
      },
      '完成'
    )
  )

  return h(
    Sheet,
    {
      title: '个性化聊天背景与壁纸',
      closeLabel: t('close'),
      onClose,
      footer,
      wide: true,
    },
    h(
      'div',
      { className: 'nm-wp-modal-body', style: { display: 'flex', flexDirection: 'column', gap: 16 } },

      // Live Preview Box
      h(
        'div',
        {
          className: 'nm-wp-preview-card',
          style: {
            position: 'relative',
            height: 150,
            borderRadius: 16,
            overflow: 'hidden',
            border: '1px solid var(--nm-divider)',
            background: 'var(--nm-base)',
            display: 'flex',
            flexDirection: 'column',
            justifyContent: 'space-between',
            padding: 12,
            boxSizing: 'border-box',
          },
        },
        enabled
          ? h('div', {
              key: `preview-bg-${blur}`,
              style: {
                position: 'absolute',
                inset: -10,
                backgroundImage: `url(${previewImgUrl})`,
                backgroundSize: 'cover',
                backgroundPosition: 'center',
                filter: `blur(${Math.round(blur * 2.4)}px) saturate(0.92)`,
                transform: 'scale(1.08)',
              },
            })
          : null,
        enabled
          ? h('div', {
              key: `preview-dim-${dim}`,
              style: {
                position: 'absolute',
                inset: 0,
                backgroundColor: `rgba(0, 0, 0, ${dim / 100})`,
              },
            })
          : null,

        // Sample bubbles on top of preview
        h(
          'div',
          {
            style: {
              position: 'relative',
              zIndex: 2,
              display: 'flex',
              flexDirection: 'column',
              gap: 8,
              width: '100%',
            },
          },
          h(
            'div',
            {
              style: {
                alignSelf: 'flex-start',
                padding: '6px 12px',
                borderRadius: 14,
                fontSize: 12,
                background: enabled ? 'rgba(32, 34, 42, 0.75)' : 'var(--nm-agent-bubble)',
                color: 'var(--dsw-alias-label-primary)',
                backdropFilter: enabled ? 'blur(12px)' : 'none',
                WebkitBackdropFilter: enabled ? 'blur(12px)' : 'none',
                border: '1px solid rgba(255,255,255,0.08)',
                maxWidth: '75%',
              },
            },
            '本喵已就绪，老大今天想做什么呢？(ฅ>ω<*ฅ)'
          ),
          h(
            'div',
            {
              style: {
                alignSelf: 'flex-end',
                padding: '6px 12px',
                borderRadius: 14,
                fontSize: 12,
                background: enabled ? 'rgba(42, 48, 62, 0.85)' : 'var(--nm-user-bubble)',
                color: enabled ? '#f3f4f6' : 'var(--nm-user-bubble-fg)',
                backdropFilter: enabled ? 'blur(12px)' : 'none',
                WebkitBackdropFilter: enabled ? 'blur(12px)' : 'none',
                border: '1px solid rgba(255,255,255,0.12)',
                maxWidth: '75%',
              },
            },
            '测试一下新壁纸的显示效果和透光度'
          )
        ),
        h(
          'div',
          {
            style: {
              position: 'relative',
              zIndex: 2,
              fontSize: 11,
              color: 'var(--dsw-alias-label-tertiary)',
              textAlign: 'right',
            },
          },
          enabled ? (useSystem ? '当前来源: 桌面系统壁纸 (Noctalia)' : '当前来源: 自定义本地图片') : '未启用背景壁纸'
        )
      ),

      // Enable Toggle
      h(
        'div',
        {
          className: 'nm-setting-row',
          style: {
            display: 'flex',
            justifyContent: 'space-between',
            alignItems: 'center',
            padding: '10px 0',
            borderBottom: '1px solid var(--nm-divider)',
          },
        },
        h(
          'div',
          null,
          h('div', { style: { fontWeight: 600, fontSize: 14 } }, '启用壁纸背景'),
          h('div', { style: { fontSize: 12, color: 'var(--dsw-alias-label-secondary)' } }, '让聊天界面呈现亚克力磨砂玻璃质感')
        ),
        h('button', {
          type: 'button',
          role: 'switch',
          'aria-checked': enabled,
          className: 'nm-switch',
          onClick: handleToggleEnabled,
        })
      ),

      // Source Switcher
      enabled
        ? h(
            'div',
            { style: { display: 'flex', flexDirection: 'column', gap: 10 } },
            h('div', { style: { fontSize: 13, fontWeight: 600 } }, '壁纸来源'),
            h(
              'div',
              { style: { display: 'flex', gap: 10, flexWrap: 'wrap' } },
              h(
                'button',
                {
                  type: 'button',
                  className: `nm-pill nm-pill-sm ${useSystem ? '' : 'nm-pill-ghost'}`,
                  style: { gap: 6 },
                  onClick: handleUseSystem,
                },
                h(IconImage, { size: 14 }),
                h('span', null, '同步系统壁纸 (Noctalia)')
              ),
              h(
                'button',
                {
                  type: 'button',
                  className: `nm-pill nm-pill-sm ${!useSystem && config.hasCustom ? '' : 'nm-pill-ghost'}`,
                  style: { gap: 6 },
                  onClick: () => fileInputRef.current?.click(),
                },
                h(IconPaperclip, { size: 14 }),
                h('span', null, uploading ? '上传中...' : '选择本地图片...')
              ),
              h('input', {
                type: 'file',
                ref: fileInputRef,
                style: { display: 'none' },
                accept: 'image/png,image/jpeg,image/webp',
                onChange: handleFileChange,
              })
            ),
            useSystem && config.systemPath
              ? h(
                  'div',
                  { style: { fontSize: 11, color: 'var(--dsw-alias-label-tertiary)', wordBreak: 'break-all' } },
                  `系统壁纸路径: ${config.systemPath}`
                )
              : null
          )
        : null,

      // Sliders
      enabled
        ? h(
            'div',
            {
              style: {
                display: 'flex',
                flexDirection: 'column',
                gap: 14,
                padding: '12px 14px',
                borderRadius: 14,
                background: 'var(--nm-card)',
                border: '1px solid var(--nm-divider)',
              },
            },
            // Blur Slider
            h(
              'div',
              { style: { display: 'flex', flexDirection: 'column', gap: 6 } },
              h(
                'div',
                { style: { display: 'flex', justifyContent: 'space-between', fontSize: 13 } },
                h('span', { style: { fontWeight: 500 } }, '虚化模糊程度 (Blur)'),
                h('span', { style: { color: 'var(--dsw-alias-label-secondary)' } }, `${blur} px`)
              ),
              h('input', {
                type: 'range',
                min: 0,
                max: 40,
                step: 1,
                value: blur,
                onChange: (e: React.ChangeEvent<HTMLInputElement>) => handleBlurChange(Number(e.target.value)),
                style: { width: '100%', accentColor: 'var(--nm-blue)' },
              })
            ),
            // Dim Slider
            h(
              'div',
              { style: { display: 'flex', flexDirection: 'column', gap: 6 } },
              h(
                'div',
                { style: { display: 'flex', justifyContent: 'space-between', fontSize: 13 } },
                h('span', { style: { fontWeight: 500 } }, '暗色遮罩深度 (Dim)'),
                h('span', { style: { color: 'var(--dsw-alias-label-secondary)' } }, `${dim} %`)
              ),
              h('input', {
                type: 'range',
                min: 0,
                max: 85,
                step: 1,
                value: dim,
                onChange: (e: React.ChangeEvent<HTMLInputElement>) => handleDimChange(Number(e.target.value)),
                style: { width: '100%', accentColor: 'var(--nm-blue)' },
              })
            )
          )
        : null
    )
  )
}
