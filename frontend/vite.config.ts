import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

const target=process.env.DATAOCEAN_API_PROXY_URL || 'http://127.0.0.1:8080';
export default defineConfig({
  plugins:[react()],
  server:{host:'127.0.0.1',port:5173,strictPort:true,proxy:{'/api':{target,changeOrigin:true}}},
  preview:{host:'127.0.0.1',port:4173,strictPort:true,proxy:{'/api':{target,changeOrigin:true}}},
  test:{environment:'jsdom',setupFiles:['./src/shared/test/setup.ts'],clearMocks:true},
});
