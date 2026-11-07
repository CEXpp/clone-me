// 开发端口固定为 5173，API 代理到本机后端；正式构建只有静态文件，无需 Node 常驻服务。
import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";
export default defineConfig({
  plugins: [vue()],
  resolve: { dedupe: ["vue", "vue-router"] },
  server: {
    port: 5174,
    strictPort: true,
    proxy: { "/api": { target: "http://127.0.0.1:8080", changeOrigin: false } },
  },
  build: { outDir: "dist", emptyOutDir: true },
});
