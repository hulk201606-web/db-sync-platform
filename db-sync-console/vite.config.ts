import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// 后端 Spring Boot 默认端口 8080；dev 阶段所有 /api 请求走代理。
// 构建产物默认输出到本地 dist/，避免误覆盖 db-sync-server 模块下的占位页面；
// 需要交付到后端静态目录时显式执行 `npm run build:server`。
export default defineConfig({
  plugins: [react()],
  // 部署在 Spring Boot 静态目录下、由根路径 / 提供服务，故必须用绝对 base；
  // 相对 base 会让 /compare/:id 这类二级路由把 assets 解析到 /compare/assets/*。
  base: '/',
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  build: {
    outDir: 'dist',
    sourcemap: false,
    chunkSizeWarningLimit: 900,
  },
});
