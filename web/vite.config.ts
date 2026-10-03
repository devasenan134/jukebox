import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// In development, everything that isn't the app goes on to a Jukebox server (JUKEBOX_URL, default
// http://localhost:8095), so the app and the API share one address like they do in production.
const server = process.env.JUKEBOX_URL ?? 'http://localhost:8095'
const api = ['/rest', '/auth', '/me', '/mixes', '/plays', '/friends', '/users', '/conversations', '/ws', '/search', '/library', '/health']

export default defineConfig({
  plugins: [react()],
  server: { proxy: Object.fromEntries(api.map((p) => [p, { target: server, ws: p === '/ws', changeOrigin: true }])) },
})
