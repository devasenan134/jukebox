import { useEffect, useLayoutEffect, useRef } from 'react'
import { BrowserRouter, NavLink, Route, Routes, useLocation, useNavigationType } from 'react-router-dom'
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
import { LibraryScreen } from './screens/LibraryScreen'
import { LikedSongsScreen, MixScreen, PlaylistScreen } from './screens/PlaylistScreens'
import { useLikes } from './state/likes'
import { useMixes, useMyPlaylists } from './state/library'
import { signInToSocial, useSession } from './state/session'
import { Icon, IconButton, Toasts } from './ui/kit'
import { usePlayerOpen } from './ui/nav'
import { ErrorBoundary } from './ui/ErrorBoundary'

const TABS = [
  { label: 'Home', path: '/', icon: 'home' },
  { label: 'Search', path: '/search', icon: 'search' },
  { label: 'Your Library', path: '/library', icon: 'library_music' },
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
  const navigationType = useNavigationType()
  // Where each page was scrolled to: Back returns to the same place, a new page starts at the top.
  const scroller = useRef<HTMLDivElement>(null)
  const positions = useRef(new Map<string, number>())
  useLayoutEffect(() => {
    const el = scroller.current
    if (el) el.scrollTop = navigationType === 'POP' ? (positions.current.get(location.key) ?? 0) : 0
  }, [location.key, navigationType])
  useEffect(() => {
    player.restoreQueue()
    // Signed in before the friends side was: sign in to it now, then load likes, playlists and mixes.
    const s = useSession.getState()
    const ready = s.social || !s.credentials ? Promise.resolve() : signInToSocial(s.credentials)
    ready.then(() => {
      useLikes.getState().refresh()
      useMyPlaylists.getState().refresh()
      useMixes.getState().refresh()
    })
  }, [])
  return (
    <div className="app">
      <div className="content" ref={scroller} onScroll={(e) => positions.current.set(location.key, e.currentTarget.scrollTop)}>
        <ErrorBoundary resetKey={location.pathname + location.search}>
        <Routes>
          <Route path="/" element={<HomeScreen />} />
          <Route path="/search" element={<SearchScreen />} />
          <Route path="/albums" element={<AlbumsScreen />} />
          <Route path="/album/:id" element={<AlbumScreen />} />
          <Route path="/artists" element={<ArtistsScreen />} />
          <Route path="/artist/:id" element={<ArtistScreen />} />
          <Route path="/singer/:id" element={<ArtistScreen />} />
          <Route path="/library" element={<LibraryScreen />} />
          <Route path="/liked" element={<LikedSongsScreen />} />
          <Route path="/playlist/:id" element={<PlaylistScreen />} />
          <Route path="/mix/:id" element={<MixScreen />} />
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
