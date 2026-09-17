import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Capacitor loads the built bundle from file:// / https://localhost, so assets
// must be referenced relatively rather than from the domain root.
export default defineConfig({
  base: './',
  plugins: [react()],
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    target: 'es2020',
    sourcemap: false,
  },
  server: {
    port: 3000,
    host: true,
  },
});
