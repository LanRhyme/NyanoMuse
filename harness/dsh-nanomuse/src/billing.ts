/**
 * DeepSeek's billing window, as the phone reads it (aligned with the dsh-cost-meter
 * plugin): Asia/Shanghai time; Saturday, Sunday and China's statutory holidays are the
 * cheap window all day; on a working day 09:00-12:00 and 14:00-18:00 are full price and
 * everything else is half. Shared by the host route and the header chip so the two can
 * never disagree.
 */

/** The days China's State Council sets aside, by their date in Asia/Shanghai. */
export const STATUTORY_HOLIDAYS: ReadonlySet<string> = new Set([
  // 2026, the arrangement published by the State Council
  '2026-01-01', '2026-01-02', '2026-01-03', // New Year
  '2026-02-15', '2026-02-16', '2026-02-17', '2026-02-18', '2026-02-19', '2026-02-20', '2026-02-21', '2026-02-22', '2026-02-23', // Spring Festival
  '2026-04-04', '2026-04-05', '2026-04-06', // Tomb Sweeping Day
  '2026-05-01', '2026-05-02', '2026-05-03', '2026-05-04', '2026-05-05', // Labour Day
  '2026-06-19', '2026-06-20', '2026-06-21', // Dragon Boat Festival
  '2026-09-25', '2026-09-26', '2026-09-27', // Mid-Autumn Festival
  '2026-10-01', '2026-10-02', '2026-10-03', '2026-10-04', '2026-10-05', '2026-10-06', '2026-10-07', // National Day
])

export interface BillingWindow {
  /** True while the half-price window is on. */
  offPeak: boolean
  /** `off-peak` or `peak`, for a class name. */
  kind: 'off-peak' | 'peak'
  /** "Off-peak · weekend all day (50% off)" or "Peak (09:00-12:00, 14:00-18:00)". */
  detail: string
  /** Why it is off-peak: a weekend, a holiday, or the hours. */
  reason: 'weekend' | 'holiday' | 'hours' | 'peak'
  /** When the window changes next, in epoch milliseconds. */
  until: number
}

/** The clock in Asia/Shanghai, whatever the computer's own zone is. */
export function shanghai(now: number): { date: string; hour: number; minute: number; day: number } {
  const shifted = new Date(now + 8 * 3600_000)
  const date = shifted.toISOString().slice(0, 10)
  return { date, hour: shifted.getUTCHours(), minute: shifted.getUTCMinutes(), day: shifted.getUTCDay() }
}

/** The window `now` falls in, and when the next one starts. */
export function billingWindow(now: number): BillingWindow {
  const { date, hour, day } = shanghai(now)
  const atNextHour = (from: number): number => {
    const next = new Date(from)
    next.setUTCMinutes(0, 0, 0)
    return next.getTime() + 3600_000
  }
  if (day === 0 || day === 6) {
    return { offPeak: true, kind: 'off-peak', detail: 'Off-peak · weekend, all day (50% off)', reason: 'weekend', until: midnightShanghai(now) }
  }
  if (STATUTORY_HOLIDAYS.has(date)) {
    return { offPeak: true, kind: 'off-peak', detail: 'Off-peak · public holiday, all day (50% off)', reason: 'holiday', until: midnightShanghai(now) }
  }
  const peak = (hour >= 9 && hour < 12) || (hour >= 14 && hour < 18)
  if (peak) {
    const end = hour < 12 ? 12 : 18
    return { offPeak: false, kind: 'peak', detail: `Peak (09:00-12:00, 14:00-18:00), full price until ${String(end).padStart(2, '0')}:00`, reason: 'peak', until: hourStart(now, end) }
  }
  // the next boundary is the start of the next window: 09:00, 12:00, 14:00 or 18:00
  const nextStart = hour < 9 ? 9 : hour < 12 ? 12 : hour < 14 ? 14 : hour < 18 ? 18 : 24
  return {
    offPeak: true,
    kind: 'off-peak',
    detail: hour < 9
      ? 'Off-peak (50% off) until 09:00'
      : hour < 12
        ? 'Off-peak (50% off) until 12:00'
        : hour < 14
          ? 'Off-peak (50% off) until 14:00'
          : hour < 18
            ? 'Off-peak (50% off) until 18:00'
            : 'Off-peak (50% off) until 09:00 tomorrow',
    reason: 'hours',
    until: nextStart >= 24 ? midnightShanghai(now) : hourStart(now, nextStart),
  }
}

function hourStart(now: number, hour: number): number {
  const { hour: current, minute } = shanghai(now)
  const midnight = now - current * 3600_000 - minute * 60_000
  return midnight + hour * 3600_000
}

function midnightShanghai(now: number): number {
  return hourStart(now, 0) + 24 * 3600_000
}

/** The price multiplier the window applies to a request started now. */
export function priceMultiplier(now: number): number {
  return billingWindow(now).offPeak ? 0.5 : 1
}
