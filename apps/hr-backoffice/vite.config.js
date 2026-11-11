import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// Tailwind CSS v4는 Vite 플러그인으로 연결하며 tailwind.config.js가 필요 없습니다.
export default defineConfig({
  plugins: [react(), tailwindcss()],
})
