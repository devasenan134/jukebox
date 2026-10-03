/**
 * Password rules, following current NIST guidance: favour length, block known-bad passwords, no
 * "must contain a symbol" rules. Same as the app's data/PasswordRules.kt and the friends server's copy.
 */
export const MIN_LENGTH = 10
export const MAX_LENGTH = 128

export type Strength = 'TooWeak' | 'Okay' | 'Strong'
export interface PasswordCheck {
  problem: string | null
  strength: Strength
}

/** ~9,000 most-used passwords of 10+ characters (UK NCSC list via SecLists), lowercase. Loaded once. */
let common: Set<string> | null = null
fetch('/common-passwords.txt')
  .then((r) => (r.ok ? r.text() : ''))
  .then((t) => (common = new Set(t.split('\n').map((l) => l.trim()).filter(Boolean))))
  .catch(() => {})

export function checkPassword(password: string, username: string): PasswordCheck {
  const lower = password.toLowerCase()
  const problem =
    password.length < MIN_LENGTH ? `Use at least ${MIN_LENGTH} characters`
    : password.length > MAX_LENGTH ? `Use at most ${MAX_LENGTH} characters`
    : new Set(password).size < 4 ? 'Too repetitive. Mix in more different characters'
    : username.length >= 3 && lower.includes(username.toLowerCase()) ? "Don't include your username in the password"
    : common?.has(lower) ? "That's one of the most commonly used passwords"
    : null
  const strength: Strength = problem ? 'TooWeak' : estimatedBits(password) < 70 ? 'Okay' : 'Strong'
  return { problem, strength }
}

/** Rough guessing difficulty: length × log2(size of the character pool used). */
function estimatedBits(p: string) {
  let pool = 0
  if (/[a-z]/.test(p)) pool += 26
  if (/[A-Z]/.test(p)) pool += 26
  if (/[0-9]/.test(p)) pool += 10
  if (/[^A-Za-z0-9\u0080-￿]/.test(p)) pool += 33
  if (/[\u0080-￿]/.test(p)) pool += 100 // Tamil and other scripts
  return [...p].length * Math.log2(Math.max(pool, 1))
}

/** A bar under a new-password field: red "too weak" with the reason, amber "okay", green "strong". */
export function PasswordStrength({ check }: { check: PasswordCheck }) {
  const [progress, color, label] =
    check.strength === 'TooWeak' ? [0.25, 'var(--error)', `Too weak: ${check.problem}`]
    : check.strength === 'Okay' ? [0.6, '#E0A33A', 'Okay. A longer passphrase would be stronger']
    : [1, '#4CC38A', 'Strong']
  return (
    <div style={{ width: '100%' }}>
      <div style={{ height: 4, borderRadius: 2, background: 'var(--surface-variant)' }}>
        <div style={{ height: 4, borderRadius: 2, width: `${progress * 100}%`, background: color }} />
      </div>
      <div className="body-small" style={{ color, marginTop: 4 }}>{label}</div>
    </div>
  )
}
