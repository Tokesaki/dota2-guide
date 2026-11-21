import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// Vite 配置文件：相当于后端的 application.yml
// 注意：项目锁定 Vite 6（package.json 中 ^6.4.4）。
// 原因：Vite 7/8 要求 Node ≥ 22.12，当前 Node 22.11.0 不满足，升级 Node 后可换新版本。
export default defineConfig({
  // 启用 Vue 单文件组件（.vue）编译插件，缺了它 .vue 文件无法被识别
  plugins: [vue()],

  server: {
    // 开发服务器端口（Vite 默认就是 5173，显式写上更清晰）
    port: 5173,

    // 开发代理：前端请求 /api/xxx 时 Vite 转发到 http://localhost:8080/api/xxx。
    // 前端代码里统一写相对地址 /api，这样打包成本地软件后（前后端同一个端口、由后端托管页面）
    // 不用改任何代码；开发时也不存在跨域问题。
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  }
})
