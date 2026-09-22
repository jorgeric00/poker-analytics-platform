import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
// @ts-expect-error type error without @types/node package
import process from "node:process";
const host = process.env.TAURI_DEV_HOST;

// https://vite.dev/config/
export default defineConfig(() => ({
  plugins: [react()],

  // Vite options tailored for Tauri development and only applied in `tauri dev` or `tauri build`
  //
  // 1. prevent Vite from obscuring rust errors
  clearScreen: false,
  // 2. tauri expects a fixed port, fail if that port is not available
  server: {
    port: 1420,
    strictPort: true,
    host: host || false,
    hmr: host
      ? {
          protocol: "ws",
          host,
          port: 1421,
        }
      : undefined,
    watch: {
      // 3. tell Vite to ignore watching `src-tauri`
      ignored: ["**/src-tauri/**"],
    },
    // Proxies to the backend (docs/decisions/ADR-004-backend-packaging.md: local HTTP, port 8080
    // in dev per CLAUDE.md) instead of calling it cross-origin — avoids needing CORS configuration
    // on the backend for `npm run dev` / `tauri dev`. The packaged app talks to the sidecar's
    // OS-assigned port instead (docs/10-api-design.md's Base URL & Discovery) — this proxy is a
    // dev-only convenience, not how the shipped app finds the backend.
    proxy: {
      "/api": {
        target: "http://localhost:8080",
        changeOrigin: true,
      },
    },
  },
}));
