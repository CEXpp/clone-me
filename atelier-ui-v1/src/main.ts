import { createApp } from "vue";
import App from "./App.vue";
import { router } from "./router";
import { debugFlow } from "./debug";
import "./atelier.css";

debugFlow("BOOT", "挂载笺注前端应用");
createApp(App).use(router).mount("#app");
