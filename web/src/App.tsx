import { useEffect } from 'react'
import { BrowserRouter, NavLink, Route, Routes, useLocation } from 'react-router-dom'
import { setLoginRejectedHandler } from './api/subsonic'
import { MiniPlayer } from './player/MiniPlayer'
import * as player from './player/player'
import { AlbumScreen } from './screens/AlbumScreen'
import { AlbumsScreen } from './screens/AlbumsScreen'
import { ArtistScreen, ArtistsScreen } from './screens/ArtistScreens'
import { HomeScreen } from './screens/HomeScreen'
import { LoginScreen } from './screens/LoginScreen'
import { NowPlaying } from './screens/NowPlaying'
import { SearchScreen } from './screens/SearchScreen'
import { useSession } from './state/session'
import { Icon, IconButton, Toasts } from './ui/kit'
import { usePlayerOpen } from './ui/nav'
import { ErrorBoundary } from './ui/ErrorBoundary'

const TABS = [
  { label: 'Home', path: '/', icon: 'home' },
  { label: 'Search', path: '/search', icon: 'search' },
  { label: 'Albums', path: '/albums', icon: 'album' },
  { label: 'Music directors', path: '/artists', icon: 'piano' },
]

export default function App() {
  const signedIn = useSession((s) => s.credentials != null)
  useEffect(() => setLoginRejectedHandler(() => useSession.getState().clear('Your password changed. Please log in again.')), [])
  return (
    <BrowserRouter>
      {signedIn ? <Main /> : <LoginScreen />}
      <Toasts />
    </BrowserRouter>
  )
}

function Main() {
  const open = usePlayerOpen((s) => s.open)
  const location = useLocation()
  useEffect(() => player.restoreQueue(), [])
  return (
    <div className="app">
      <div className="content">
        <ErrorBoundary resetKey={location.pathname + location.search}>
        <Routes>
          <Route path="/" element={<HomeScreen />} />
          <Route path="/search" element={<SearchScreen />} />
          <Route path="/albums" element={<AlbumsScreen />} />
          <Route path="/album/:id" element={<AlbumScreen />} />
          <Route path="/artists" element={<ArtistsScreen />} />
          <Route path="/artist/:id" element={<ArtistScreen />} />
          <Route path="/singer/:id" element={<ArtistScreen />} />
          <Route path="*" element={<HomeScreen />} />
        </Routes>
        </ErrorBoundary>
      </div>
      <div className="mini-slot">
        <ErrorBoundary>
          <MiniPlayer onOpen={() => usePlayerOpen.getState().setOpen(true)} />
        </ErrorBoundary>
      </div>
      <nav className="bottom-nav">
        {TABS.map((t) => (
          <NavLink key={t.path} to={t.path} end={t.path === '/'} className={({ isActive }) => `nav-item${isActive ? ' selected' : ''}`}>
            <span className="pill"><Icon name={t.icon} /></span>
            <span className="label-medium">{t.label}</span>
          </NavLink>
        ))}
        <div className="nav-item" style={{ flex: '0 0 56px' }}>
          <IconButton icon="logout" label="Log out" onClick={() => { player.stop(); useSession.getState().clear() }} />
        </div>
      </nav>
      {open && (
        <ErrorBoundary>
          <NowPlaying />
        </ErrorBoundary>
      )}
    </div>
  )
}
