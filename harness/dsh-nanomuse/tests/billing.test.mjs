// DeepSeek's billing window, the phone's rule (aligned with dsh-cost-meter): Beijing time,
// weekends and China's statutory holidays are the cheap window all day, and a working day is
// full price only 09:00-12:00 and 14:00-18:00.
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { billingWindow, priceMultiplier, shanghai } from '../lib/billing.js'

/** A Beijing wall-clock moment as epoch milliseconds. */
const beijing = (date, hour, minute = 0) => Date.parse(`${date}T${String(hour).padStart(2, '0')}:${String(minute).padStart(2, '0')}:00+08:00`)

test('the clock is read in Beijing whatever the computer is set to', () => {
  assert.deepEqual(shanghai(beijing('2026-03-10', 9, 30)), { date: '2026-03-10', hour: 9, minute: 30, day: 2 })
})

test('a working day is peak 09:00-12:00 and 14:00-18:00, off-peak either side', () => {
  const peak = ['09:00', '11:59', '14:00', '17:59']
  const off = ['00:00', '08:59', '12:00', '13:59', '18:00', '23:59']
  for (const at of peak) {
    const [hour, minute] = at.split(':').map(Number)
    assert.equal(billingWindow(beijing('2026-03-10', hour, minute)).offPeak, false, at)
  }
  for (const at of off) {
    const [hour, minute] = at.split(':').map(Number)
    assert.equal(billingWindow(beijing('2026-03-10', hour, minute)).offPeak, true, at)
  }
  assert.equal(priceMultiplier(beijing('2026-03-10', 10)), 1)
  assert.equal(priceMultiplier(beijing('2026-03-10', 20)), 0.5)
})

test('a weekend is the cheap window all day', () => {
  const saturday = beijing('2026-03-14', 10)
  const window = billingWindow(saturday)
  assert.equal(window.offPeak, true)
  assert.equal(window.reason, 'weekend')
  assert.match(window.detail, /weekend/)
})

test('a statutory holiday is the cheap window all day, even on a working day of the week', () => {
  const holiday = billingWindow(beijing('2026-10-01', 10))
  assert.equal(holiday.offPeak, true)
  assert.equal(holiday.reason, 'holiday')
  assert.match(holiday.detail, /holiday/)
})

test('each window says when the next one starts', () => {
  const beforeNine = billingWindow(beijing('2026-03-10', 8, 30))
  assert.equal(beforeNine.until, beijing('2026-03-10', 9))
  const morningPeak = billingWindow(beijing('2026-03-10', 10, 30))
  assert.equal(morningPeak.until, beijing('2026-03-10', 12))
  const evening = billingWindow(beijing('2026-03-10', 19))
  assert.equal(evening.until, beijing('2026-03-11', 0))
  assert.equal(billingWindow(beijing('2026-03-14', 12)).until, beijing('2026-03-15', 0))
})
