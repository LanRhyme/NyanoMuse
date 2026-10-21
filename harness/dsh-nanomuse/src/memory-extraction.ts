/**
 * Safe, passive natural preference & profile extraction for NyanoMuse long-term memory.
 * Extracts high-signal declarative statements from user messages without requiring explicit "记住：..." prefixes.
 * Strictly adheres to anti-poisoning guidelines: only user messages are inspected.
 */

export interface ExtractedMemory {
  content: string
  category: 'preference' | 'habit' | 'profile' | 'device' | 'custom'
  importance: number
}

// Transient task action stop-words that indicate an ephemeral instruction rather than a long-term trait
const TRANSIENT_PATTERNS = [
  /^(?:帮我|请帮我|给我|替我|麻烦)/,
  /^(?:打开|启动|运行|搜索|查询|删除|修改|重命名|复制|移动|写一段|看一下|解释一下|总结一下)/,
  /(?:今天|刚才|这会儿|这次|这篇|这个文件|这段代码|当前会话|现在几点)/,
  /(?:吗|呢|吧|？|\?)$/,
]

export function extractPassiveMemories(text: string): ExtractedMemory[] {
  if (!text || typeof text !== 'string') return []
  const trimmed = text.trim()

  // 1. Safety & scope checks: ignore commands, code blocks, or excessively long messages
  if (trimmed.startsWith('/') || trimmed.startsWith('!') || trimmed.startsWith('```')) return []
  if (trimmed.length > 800) return [] // long essay or log paste, unlikely a direct personal fact declaration

  // 2. Split into sentence units
  const sentences = trimmed
    .split(/[。\n!！;；,，]+/)
    .map((s) => s.trim())
    .filter((s) => s.length >= 3 && s.length <= 120)

  const results: ExtractedMemory[] = []

  for (const s of sentences) {
    // Check transient exclusions
    if (TRANSIENT_PATTERNS.some((pat) => pat.test(s))) continue

    // Pattern 1: Explicit Remember prefix (highest priority)
    const explicitMatch = /^(?:记住|请记住|以后记住|记录一下)\s*[:：,，]?\s*(.+)$/i.exec(s)
    if (explicitMatch && explicitMatch[1] && explicitMatch[1].length >= 3) {
      results.push({
        content: explicitMatch[1].trim(),
        category: 'preference',
        importance: 0.8,
      })
      continue
    }

    // Pattern 2: Hardware / OS / Tools
    const zhDeviceMatch = /^(?:我(?:的)?(?:当前|现在|主力)?(?:电脑|手机|平板)?)*(手机|平板|电脑|笔记本|操作系统|系统|窗口管理器|合成器|桌面环境|编辑器|IDE|包管理器)(?:是|换成了|换成|使用|为|用)\s*(.+)$/i.exec(s)
    if (zhDeviceMatch && zhDeviceMatch[1] && zhDeviceMatch[2] && zhDeviceMatch[2].length >= 2) {
      const dev = zhDeviceMatch[1]
      const val = zhDeviceMatch[2].trim()
      results.push({
        content: `${dev}使用 ${val}`,
        category: 'device',
        importance: 0.85,
      })
      continue
    }

    const enDeviceMatch = /^my\s+(phone|tablet|laptop|pc|desktop|os|editor|ide|package manager)\s+is\s+(.+)$/i.exec(s)
    if (enDeviceMatch && enDeviceMatch[1] && enDeviceMatch[2]) {
      results.push({
        content: `${enDeviceMatch[1]} is ${enDeviceMatch[2].trim()}`,
        category: 'device',
        importance: 0.85,
      })
      continue
    }

    // Pattern 3: Residence / Location
    const zhLocationMatch = /^我(?:现在|目前|已经|最近|刚)?(?:搬到了|搬到|定居在|定居到|住在|居住在|常驻在)\s*(.+)$/i.exec(s)
    if (zhLocationMatch && zhLocationMatch[1] && zhLocationMatch[1].length >= 2) {
      const loc = zhLocationMatch[1].replace(/[，,].*$/, '').trim()
      results.push({
        content: `现居 ${loc}`,
        category: 'profile',
        importance: 0.8,
      })
      continue
    }

    const enLocationMatch = /^i\s+(?:now\s+)?(?:live in|moved to|relocated to)\s+(.+)$/i.exec(s)
    if (enLocationMatch && enLocationMatch[1]) {
      results.push({
        content: `Lives in ${enLocationMatch[1].trim()}`,
        category: 'profile',
        importance: 0.8,
      })
      continue
    }

    // Pattern 4: Name / Preferred Addressing
    const nameMatch = /^(?:叫我|称呼我为|我的名字是)\s*[:：]?\s*(.+)$/i.exec(s)
    if (nameMatch && nameMatch[1] && nameMatch[1].length >= 1 && nameMatch[1].length <= 20) {
      results.push({
        content: `Call them: ${nameMatch[1].trim()}`,
        category: 'profile',
        importance: 0.9,
      })
      continue
    }

    // Pattern 5: Allergies & Health constraints
    const allergyMatch = /^我(?:对)?(.+?)(?:严重)?过敏$/i.exec(s)
    if (allergyMatch && allergyMatch[1]) {
      results.push({
        content: `健康约束：对${allergyMatch[1].trim()}过敏`,
        category: 'habit',
        importance: 0.9,
      })
      continue
    }

    // Pattern 6: Standing Behavioral Preferences ("我平时喜欢...", "我习惯用...", "我不喜欢/讨厌...")
    const zhPrefMatch = /^我(?:平时|日常|通常|一般)?(?:最喜欢|挺喜欢|特别喜欢|很喜欢|偏好|偏爱|喜欢)\s*(.+)$/i.exec(s)
    if (zhPrefMatch && zhPrefMatch[1] && zhPrefMatch[1].length >= 2) {
      results.push({
        content: `偏好：喜欢${zhPrefMatch[1].trim()}`,
        category: 'preference',
        importance: 0.7,
      })
      continue
    }

    const zhDislikeMatch = /^我(?:平时|日常|通常|一般)?(?:最讨厌|特别讨厌|很讨厌|反感|排斥|不喜欢|不爱)\s*(.+)$/i.exec(s)
    if (zhDislikeMatch && zhDislikeMatch[1] && zhDislikeMatch[1].length >= 2) {
      results.push({
        content: `偏好：不喜欢${zhDislikeMatch[1].trim()}`,
        category: 'preference',
        importance: 0.7,
      })
      continue
    }

    const zhHabitMatch = /^我(?:平时|日常|通常|一般)?(?:习惯用|习惯|只用|只喝|不喝|戒了)\s*(.+)$/i.exec(s)
    if (zhHabitMatch && zhHabitMatch[1] && zhHabitMatch[1].length >= 2) {
      results.push({
        content: `习惯：${s}`,
        category: 'habit',
        importance: 0.7,
      })
      continue
    }

    const enPrefMatch = /^i\s+(?:usually\s+|always\s+)?(prefer|like|love|dislike|hate)\s+(.+)$/i.exec(s)
    if (enPrefMatch && enPrefMatch[1] && enPrefMatch[2]) {
      results.push({
        content: `Preference: ${enPrefMatch[1]} ${enPrefMatch[2].trim()}`,
        category: 'preference',
        importance: 0.7,
      })
      continue
    }

    // Pattern 7: Standing communication guidelines ("以后回答时不要用表情符号", "以后默认用中文回答")
    const guidelineMatch = /^(?:(?:以后|今后)(?:(?:跟我)?(?:回答|说话)时)?|(?:跟我)?(?:回答|说话)时)(?:请|千万|务必|一律)?(不要|别|必须|记得|默认)\s*(.+)$/i.exec(s)
    if (guidelineMatch && guidelineMatch[1] && guidelineMatch[2]) {
      results.push({
        content: `沟通规范：${s}`,
        category: 'preference',
        importance: 0.85,
      })
      continue
    }
  }

  return results
}
