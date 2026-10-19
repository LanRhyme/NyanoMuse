/**
 * The sign-in form for self-hosted NyanoMuse Relay: enter server address,
 * username and password to connect and authenticate.
 */
import { Button, Input } from '@deepseek-ai/dsh-client-ui-primitives'
import { createElement as h, useState, type FormEvent, type ReactNode } from 'react'
import { call, column, errorStyle, row, type CloudStatus, type Translate, failureText } from './api.ts'

export interface SignInProps {
  t: Translate
  onSignedIn(status: CloudStatus): void
  /** Rendered under the form (the relay line, a "later" button…). */
  footer?: ReactNode
  /** The primary button takes the whole row (the welcome dialog). */
  wide?: boolean
}

export function SignIn({ t, onSignedIn, footer, wide = false }: SignInProps): ReactNode {
  const [baseURL, setBaseURL] = useState('')
  const [identifier, setIdentifier] = useState('')
  const [password, setPassword] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | undefined>()

  const run = async (work: () => Promise<void>) => {
    setBusy(true)
    setError(undefined)
    try {
      await work()
    } catch (err: unknown) {
      setError(failureText(t, err))
    } finally {
      setBusy(false)
    }
  }

  const login = (event: FormEvent) => {
    event.preventDefault()
    if (!identifier.trim() || !password) return
    void run(async () => {
      const payload: Record<string, string> = { identifier: identifier.trim(), password }
      if (baseURL.trim()) payload.baseURL = baseURL.trim()
      const status = await call<CloudStatus>('login', payload)
      setPassword('')
      onSignedIn(status)
    })
  }

  const primary = { variant: 'primary' as const, size: 'md' as const, type: 'submit' as const, style: wide ? { width: '100%' } : undefined }

  return h('form', { style: column, onSubmit: login },
    h('div', { style: { ...column, gap: 10 } },
      h(Input, {
        value: baseURL,
        onChange: (e: FormEvent<HTMLInputElement>) => setBaseURL(e.currentTarget.value),
        placeholder: '服务器地址（例如 http://192.168.x.x:8000，留空使用默认）',
        autoComplete: 'off',
        'aria-label': '服务器地址',
      }),
      h(Input, {
        value: identifier,
        onChange: (e: FormEvent<HTMLInputElement>) => setIdentifier(e.currentTarget.value),
        placeholder: '用户名 / 账号',
        autoComplete: 'username',
        autoFocus: true,
        'aria-label': '用户名',
      }),
      h(Input, {
        value: password,
        type: 'password',
        onChange: (e: FormEvent<HTMLInputElement>) => setPassword(e.currentTarget.value),
        placeholder: t('obPassword'),
        autoComplete: 'current-password',
        'aria-label': t('obPassword'),
      })),
    error ? h('div', { style: errorStyle, role: 'alert' }, error) : null,
    h('div', { style: row },
      h(Button, { ...primary, disabled: busy || identifier.trim().length === 0 || password.length === 0 }, busy ? t('signingIn') : t('signIn'))),
    footer)
}
