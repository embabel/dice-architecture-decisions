import { defineConfig } from 'vite'

export default defineConfig({
  root: 'web',
  build: {
    outDir: '../target/generated-web/static',
    emptyOutDir: true,
    assetsDir: 'assets/architecture',
  },
})
