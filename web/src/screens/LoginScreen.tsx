import { useState } from 'react'
import { credentialsFor, subsonic } from '../api/subsonic'
import { social } from '../api/social'
import { signInToSocial, useSession } from '../state/session'
import { PasswordStrength, checkPassword } from '../ui/password'

/**
 * Log in with an existing account, or sign up with an invite code from a friend
 * (ui/login/LoginScreen.kt). The web app lives on the same address as the servers, so unlike the
 * phone app there are no server addresses to type.
 */
export function LoginScreen() {
  const logoutReason = useSession((s) => s.logoutReason)
  const [signingUp, setSigningUp] = useState(false)
  const [inviteCode, setInviteCode] = useState('')
  const [username, setUsername] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [password, setPassword] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  // New accounts must pass the password rules; logging in accepts whatever password you already have.
  const check = signingUp && password ? checkPassword(password, username) : null
  const canSubmit = !busy && username.trim() && password && (!signingUp || (inviteCode.trim() && !check?.problem))

  async function submit(e?: React.FormEvent) {
    e?.preventDefault()
    if (!canSubmit) return
    setBusy(true)
    setError(null)
    try {
      const creds = credentialsFor(username, password)
      if (signingUp) {
        // Creates the account and signs in to the friends side; the music side checks the password next.
        const r = await social.signup(inviteCode.trim(), username.trim(), password, displayName.trim())
        useSession.getState().saveSocial({ token: r.sessionToken, user: r.user })
      }
      await subsonic.ping(creds) // checks the password
      if (!signingUp) await signInToSocial(creds)
      useSession.getState().saveCredentials(creds) // the app then switches to the main screen (which loads your likes)
    } catch (err) {
      setError((err as Error).message || "Couldn't connect")
      setBusy(false)
    }
  }

  return (
    <div style={{ minHeight: '100%', display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 24, background: 'var(--surface)' }}>
      <form onSubmit={submit} style={{ width: '100%', maxWidth: 420, display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 12 }}>
        <div style={{ fontSize: 34, color: 'var(--primary)', fontFamily: 'var(--display)', fontWeight: 700 }}>Jukebox</div>
        <div className="title-medium">{signingUp ? 'Join your friends on Jukebox' : 'Your music, your server'}</div>
        {logoutReason && <div style={{ color: 'var(--tertiary)', textAlign: 'center' }}>{logoutReason}</div>}
        {signingUp && (
          <div className="field">
            <label>Invite code</label>
            <input value={inviteCode} placeholder="ABCD-EFGH" autoCapitalize="characters" onChange={(e) => setInviteCode(e.target.value.toUpperCase())} />
          </div>
        )}
        <div className="field">
          <label>Username</label>
          <input value={username} autoComplete="username" autoCapitalize="none" onChange={(e) => setUsername(e.target.value.trim())} />
          {signingUp && <div className="support">Letters, numbers, . _ -  (3–24)</div>}
        </div>
        {signingUp && (
          <div className="field">
            <label>Your name (shown to friends)</label>
            <input value={displayName} autoCapitalize="words" onChange={(e) => setDisplayName(e.target.value)} />
          </div>
        )}
        <div className="field">
          <label>Password</label>
          <input type="password" value={password} autoComplete={signingUp ? 'new-password' : 'current-password'} onChange={(e) => setPassword(e.target.value)} />
          {signingUp && !password && <div className="support">At least 10 characters. A few words work well</div>}
        </div>
        {check && <PasswordStrength check={check} />}
        {error && <div className="error-text">{error}</div>}
        <button className="btn full" type="submit" disabled={!canSubmit}>
          {busy ? <span className="spinner" style={{ width: 20, height: 20, borderWidth: 2 }} /> : signingUp ? 'Create account' : 'Log in'}
        </button>
        <button
          type="button"
          className="btn text"
          onClick={() => {
            setSigningUp(!signingUp)
            setError(null)
          }}
        >
          {signingUp ? 'Already have an account? Log in' : 'Got an invite code? Sign up'}
        </button>
      </form>
    </div>
  )
}
