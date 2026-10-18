/**
 * 前端启动入口：加载本地构建样式，挂载路由和根组件到页面容器。
 */
import { createApp } from "vue";
import App from "./App.vue";
import { router } from "./router";
import "./style.css";
createApp(App).use(router).mount("#app");
