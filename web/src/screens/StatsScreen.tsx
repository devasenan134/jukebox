import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import type { RangeStats, TopItem, UserStats } from '../api/socialTypes'
import { social } from '../api/social'
import { Avatar } from '../social/avatars'
import { useSession } from '../state/session'
import { Cover, ScreenHeader, SectionTitle } from '../ui/components'
import { ErrorBox, IconButton, Loading } from '../ui/kit'
import { useNav } from '../ui/nav'

// Everyone's listening, for admins only (ui/settings/StatsScreen.kt; the server checks too). A song counts once
// half of it (or 4 minutes) is heard.

const RANGES = [['today', 'Today'], ['7d', '7 days'], ['30d', '30 days'], ['all', 'All time']] as const
const EMPTY: RangeStats = { hours: 0, plays: 0, topSongs: [], topMovies: [], topComposers: [] }

const hoursText = (h: number) => (h >= 10 ? `${Math.round(h)} h` : h >= 1 ? `${h.toFixed(1)} h` : `${Math.round(h * 60)} min`)

function ago(at: number): string {
  const minutes = Math.floor((Date.now() - at) / 60_000)
  if (minutes < 1) return 'just now'
  if (minutes < 60) return `${minutes} min ago`
  if (minutes < 24 * 60) return `${Math.floor(minutes / 60)} h ago`
  if (minutes < 2 * 24 * 60) return 'yesterday'
  return `${Math.floor(minutes / (24 * 60))} days ago`
}

export function StatsScreen() {
  const nav = useNav()
  const user = useSession((s) => s.social?.user.id)
  const stats = useQuery({
    queryKey: ['listening-stats', user],
    queryFn: () => social.listeningStats(Intl.DateTimeFormat().resolvedOptions().timeZone),
    enabled: user != null,
  })
  const [range, setRange] = useState<string>('7d')
  const [open, setOpen] = useState<string | null>(null)
  const data = stats.data

  const people = (data?.users ?? [])
    .map((u) => [u, u.ranges[range] ?? EMPTY] as [UserStats, RangeStats])
    .sort((a, b) => b[1].hours - a[1].hours || (b[0].lastPlayedAt ?? 0) - (a[0].lastPlayedAt ?? 0))
  const most = Math.max(0, ...people.map(([, s]) => s.hours)) || 1
  const total = people.reduce((t, [, s]) => t + s.hours, 0)

  return (
    <div className="page">
      <ScreenHeader title="Listening stats" onBack={nav.back} actions={<IconButton icon="refresh" label="Refresh" onClick={() => void stats.refetch()} />} />
      {stats.error && !data ? (
        <ErrorBox message={stats.error.message || "Couldn't load the stats"} onRetry={() => void stats.refetch()} />
      ) : !data ? (
        <Loading />
      ) : (
        <div style={{ maxWidth: 760, padding: '0 16px 24px' }}>
          <div className="chips" style={{ marginBottom: 12 }}>
            {RANGES.map(([key, label]) => (
              <button key={key} className={`chip${range === key ? ' selected' : ''}`} onClick={() => setRange(key)}>{label}</button>
            ))}
          </div>
          <div className="display-small" style={{ fontWeight: 800 }}>{hoursText(total)}</div>
          <div className="body-medium muted" style={{ marginBottom: 12 }}>
            listened by {people.filter(([, s]) => s.plays > 0).length} of {people.length} people · {people.reduce((t, [, s]) => t + s.plays, 0).toLocaleString('en')} plays
          </div>
          {people.map(([u, s]) => (
            <PersonRow key={u.username} user={u} stats={s} share={s.hours / most} open={open === u.username} onClick={() => setOpen(open === u.username ? null : u.username)} />
          ))}
          <SectionTitle>Last 30 days, everyone</SectionTitle>
          <BarChart
            label="Hours listened per day, last 30 days"
            values={data.daily.map((d) => d.hours)}
            describe={(i) => `${new Date(data.daily[i].date + 'T12:00').toLocaleDateString('en-GB', { weekday: 'short', day: 'numeric', month: 'short' })}: ${hoursText(data.daily[i].hours)}`}
            ticks={data.daily.map((d, i) => (i % 7 === 0 || (i === data.daily.length - 1 && i % 7 >= 4) ? new Date(d.date + 'T12:00').toLocaleDateString('en-GB', { day: 'numeric', month: 'short' }) : ''))}
          />
          <SectionTitle>Time of day, last 30 days</SectionTitle>
          <BarChart
            label="Hours listened by hour of the day, last 30 days"
            values={data.hourOfDay}
            describe={(i) => `${String(i).padStart(2, '0')}:00–${String((i + 1) % 24).padStart(2, '0')}:00: ${hoursText(data.hourOfDay[i])}`}
            ticks={data.hourOfDay.map((_, i) => (i % 6 === 0 ? `${String(i).padStart(2, '0')}:00` : ''))}
          />
        </div>
      )}
    </div>
  )
}

function PersonRow({ user, stats, share, open, onClick }: { user: UserStats; stats: RangeStats; share: number; open: boolean; onClick: () => void }) {
  return (
    <div className="list-row" style={{ display: 'block', padding: '10px 8px' }} onClick={onClick} aria-expanded={open}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
        <Avatar name={user.displayName} userKey={user.username} size={40} />
        <div style={{ flex: 1, minWidth: 0 }}>
          <div className="body-large ellipsis">{user.displayName}</div>
          <div className="body-small muted ellipsis">{stats.plays.toLocaleString('en')} plays · {user.lastPlayedAt ? `last played ${ago(user.lastPlayedAt)}` : 'no plays yet'}</div>
        </div>
        <div className="title-medium" style={{ fontWeight: 700, fontVariantNumeric: 'tabular-nums' }}>{hoursText(stats.hours)}</div>
      </div>
      {/* Compared with whoever listened most in this range. */}
      <div className="meter" style={{ marginLeft: 52, marginTop: 6 }}><span style={{ width: `${Math.max(0, Math.min(1, share)) * 100}%` }} /></div>
      {open && (
        <div style={{ marginLeft: 52, marginTop: 8 }} onClick={(e) => e.stopPropagation()}>
          {stats.plays === 0 ? <div className="body-medium muted">Nothing played in this range.</div> : (
            <>
              <TopList title="Top songs" items={stats.topSongs} covers />
              <TopList title="Top albums" items={stats.topMovies} covers />
              <TopList title="Top composers" items={stats.topComposers} />
            </>
          )}
        </div>
      )}
    </div>
  )
}

function TopList({ title, items, covers }: { title: string; items: TopItem[]; covers?: boolean }) {
  if (!items.length) return null
  return (
    <div style={{ marginBottom: 8 }}>
      <div className="label-large muted" style={{ marginBottom: 4 }}>{title}</div>
      {items.map((it, i) => (
        <div key={i} style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '4px 0' }}>
          {covers && <Cover coverArt={it.coverArt} size={100} corner={4} style={{ width: 36, height: 36 }} />}
          <div style={{ flex: 1, minWidth: 0 }}>
            <div className="body-medium ellipsis">{it.name}</div>
            {it.detail && <div className="body-small muted ellipsis">{it.detail}</div>}
          </div>
          <span className="body-small muted">{it.plays}×</span>
        </div>
      ))}
    </div>
  )
}

/**
 * A single-series bar chart: thin bars rounded at the top with a 2px gap, a quiet baseline, a few axis labels,
 * and hover (or click) a bar to read its exact value on the line above the chart.
 */
function BarChart({ values, describe, ticks, label }: { values: number[]; describe: (i: number) => string; ticks: string[]; label: string }) {
  const [at, setAt] = useState<number | null>(null)
  const max = Math.max(0, ...values) || 1
  return (
    <figure className="bar-chart" aria-label={label} style={{ margin: 0 }}>
      <div className="body-small muted" style={{ minHeight: 18, marginBottom: 6 }} aria-live="polite">
        {at != null ? describe(at) : 'Hover over a bar for its value'}
      </div>
      <div className="chart-bars" onMouseLeave={() => setAt(null)}>
        {values.map((v, i) => (
          <button
            key={i} className={`bar-hit${at === i ? ' on' : ''}`} aria-label={describe(i)}
            onMouseEnter={() => setAt(i)} onFocus={() => setAt(i)} onClick={() => setAt(i)}
          >
            <span style={{ height: `${(v / max) * 100}%` }} />
          </button>
        ))}
      </div>
      <div className="bar-ticks">
        {ticks.map((t, i) => <span key={i}>{t}</span>)}
      </div>
    </figure>
  )
}
