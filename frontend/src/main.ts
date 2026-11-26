/**
 * 前端启动入口：加载本地构建样式，挂载路由和根组件到页面容器。
 */
import { debugFlow } from "./debug";
import { createApp } from "vue";
import App from "./App.vue";
import { router } from "./router";
import "./style.css";
// index.html 的 #app 是挂载位置；App 是外壳，router 决定外壳里显示哪个页面。
debugFlow("BOOT", "开始创建 Vue 应用，安装路由并挂载根组件");
createApp(App).use(router).mount("#app");
debugFlow("BOOT", "根组件已挂载，页面将在路由和数据加载后更新");
