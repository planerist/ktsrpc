import { defineConfig } from 'vite'

export default defineConfig({
    server: {
        proxy: {
            '/rpc': {
                target: 'http://localhost:8080',
                changeOrigin: true,
                ws: true,
            },
            '/auth': {
                target: 'http://localhost:8080',
                changeOrigin: true,
            }
        }
    }
})
