import { NavLink } from 'react-router-dom'
import { useEntryState, useLibraryEntries, useLibraryFilter, useNewPlaylist } from '../screens/LibraryScreen'
import { PlayingBars } from '../ui/components'
import { Icon, IconButton } from '../ui/kit'
import { useNav } from '../ui/nav'

/** Wide screens: Home and Search at the top, Your Library always in view below (like a desktop music app). */
export function Sidebar({ onLogOut }: { onLogOut: () => void }) {
  const nav = useNav()
  const { filter, chips } = useLibraryFilter('sidebar.filter')
  const { entries, empty, hint } = useLibraryEntries(filter)
  const newPlaylist = useNewPlaylist()
  const state = useEntryState()
  return (
    <aside className="sidebar">
      <nav className="panel side-nav">
        {[
          { to: '/', label: 'Home', icon: 'home' },
          { to: '/search', label: 'Search', icon: 'search' },
        ].map((l) => (
          <NavLink key={l.to} to={l.to} end={l.to === '/'} className={({ isActive }) => `side-link${isActive ? ' selected' : ''}`}>
            <Icon name={l.icon} size={26} />
            {l.label}
          </NavLink>
        ))}
      </nav>
      <section className="panel side-library">
        <div className="side-library-head">
          <a
            href="/library"
            className="side-link"
            style={{ flex: 1, padding: 0, height: 40 }}
            onClick={(e) => {
              e.preventDefault()
              nav.openLibrary()
            }}
          >
            <Icon name="library_music" size={26} />
            Your Library
          </a>
          <IconButton icon="add" label="New playlist" onClick={newPlaylist.start} />
          <IconButton icon="logout" label="Log out" onClick={onLogOut} />
        </div>
        <div style={{ padding: '6px 16px 8px' }}>{chips}</div>
        <div className="side-library-list">
          {entries.map((e) => {
            const s = state(e)
            return (
              <div key={e.key} className={`side-item${s.open ? ' selected' : ''}${s.playing ? ' playing' : ''}`} onClick={e.open}>
                {e.art(48)}
                <div style={{ minWidth: 0, flex: 1 }}>
                  <div className="title body-medium ellipsis" style={{ fontWeight: 600 }}>{e.title}</div>
                  <div className="body-small muted ellipsis">{e.subtitle}</div>
                </div>
                {s.playing && <PlayingBars playing={s.isPlaying} size={14} />}
              </div>
            )
          })}
          {empty && <div className="body-small muted" style={{ padding: '12px 8px' }}>{hint}</div>}
        </div>
      </section>
      {newPlaylist.dialog}
    </aside>
  )
}
