import { Component, type ErrorInfo, type ReactNode } from 'react'

/**
 * Catches a crash in the part of the page it wraps, so one broken screen shows a message with a way back
 * instead of blanking the whole site (the music keeps playing either way).
 */
export class ErrorBoundary extends Component<{ children: ReactNode; resetKey?: string }, { error: Error | null }> {
  state = { error: null as Error | null }

  static getDerivedStateFromError(error: Error) {
    return { error }
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error('Screen crashed', error, info.componentStack)
  }

  componentDidUpdate(prev: { resetKey?: string }) {
    // Going to another page tries again.
    if (this.state.error && prev.resetKey !== this.props.resetKey) this.setState({ error: null })
  }

  render() {
    if (!this.state.error) return this.props.children
    return (
      <div className="center-box" style={{ padding: 32, textAlign: 'center', display: 'flex', flexDirection: 'column', gap: 12, alignItems: 'center' }}>
        <div className="title-medium">Something went wrong on this page</div>
        <div className="body-small muted" style={{ maxWidth: 420, wordBreak: 'break-word' }}>{this.state.error.message}</div>
        <div style={{ display: 'flex', gap: 8 }}>
          <button className="btn tonal" onClick={() => this.setState({ error: null })}>Try again</button>
          <button className="btn text" onClick={() => { location.href = '/' }}>Go home</button>
        </div>
      </div>
    )
  }
}
