// 开发端口固定为 5175（与 frontend:5173、forest-ui-v1:5174 同级并存），API 代理到本机后端。
import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";

export default defineConfig({
  plugins: [vue()],
  resolve: { dedupe: ["vue", "vue-router"] },
  server: {
    port: 5175,
    strictPort: true,
    proxy: { "/api": { target: "http://127.0.0.1:8080", changeOrigin: false } },
  },
  build: { outDir: "dist", emptyOutDir: true },
});
