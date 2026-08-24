import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  build: {
    rollupOptions: {
      output: {
        manualChunks: (id: string) => {
          if (id.includes("jspdf")) return "jspdf";
          if (id.includes("firebase")) return "firebase";
          if (id.includes("node_modules/react") || id.includes("@tanstack")) return "vendor";
          return undefined;
        },
      },
    },
  },
})
