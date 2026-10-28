import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import '@fontsource-variable/figtree'
import '@fontsource/noto-sans-tamil/400.css'
import '@fontsource/noto-sans-tamil/600.css'
import '@fontsource/noto-sans-tamil/700.css'
// Mix covers (ui/mixArt.tsx).
import '@fontsource/anton/400.css'
import '@fontsource/space-mono/700.css'
import '@fontsource/fraunces/600-italic.css'
import '@fontsource/permanent-marker/400.css'
import './styles.css'
import App from './App.tsx'
import { QueryClientProvider } from '@tanstack/react-query'
import { queryClient } from './state/queries'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <App />
    </QueryClientProvider>
  </StrictMode>,
)
