import { spawn } from "node:child_process";
import { randomBytes, randomUUID } from "node:crypto";
import { mkdir, readFile, writeFile, rm } from "node:fs/promises";
import { resolve, dirname, sep } from "node:path";
import { fileURLToPath } from "node:url";
import { createServer } from "node:net";

// 只使用一次性数据库、测试管理员和空供应商配置，不调用真实模型或 SMTP。
const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const cache = resolve(root, ".cache");
const data = resolve(cache, "e2e-" + randomUUID());
const authFile = resolve(cache, "e2e-auth.json");
const port = Number(process.env.E2E_PORT || 18080);
const jar = resolve(root, "backend/target/clone-me-0.1.0-SNAPSHOT.jar");
await new Promise((ok, fail) => {
  const probe = createServer();
  probe.once("error", fail);
  probe.listen(port, "127.0.0.1", () => probe.close(ok));
});
await mkdir(data, { recursive: true });
const env = Object.fromEntries(Object.entries(process.env).filter(([key]) => !/^(CLONE_|DEEPSEEK_|SILICONFLOW_|TYPESAFE_|DASHSCOPE_|BAILIAN_|TURNSTILE_|SPRING_)/.test(key)));
Object.assign(env, {
  PORT: String(port), CLONE_DATA_DIR: data, CLONE_LOG_FILE: resolve(data, "logs/app.jsonl"),
  CLONE_MASTER_KEY: randomBytes(32).toString("base64"), CLONE_ADMIN_USERNAME: "fixture_owner",
  CLONE_ADMIN_PASSWORD: "browser-fixture-password-2026", CLONE_CHAT_ENABLED: "true",
  CLONE_PRODUCTION: "false", CLONE_SECURE_COOKIES: "false", SPRING_MAIL_HOST: "",
});
const args = ["--enable-native-access=ALL-UNNAMED", "-Xmx512m", "-jar", jar];
function java(extra = []) { return spawn("java", [...args, ...extra], { cwd: resolve(root, "backend"), env, stdio: ["ignore", "pipe", "pipe"], windowsHide: true }); }
const bootstrap = java(["--initialize-admin"]);
let output = "";
bootstrap.stdout.on("data", (value) => { output += value; });
bootstrap.stderr.on("data", (value) => { output += value; });
const initialized = await new Promise((ok) => bootstrap.once("exit", ok));
if (initialized !== 0) {
  await writeFile(resolve(data, "bootstrap.log"), output);
  throw new Error("E2E administrator initialization failed; inspect the private fixture directory: " + data);
}
const codes = output.split(/\r?\n/).filter((line) => /^[A-Za-z0-9_-]{20}$/.test(line));
if (codes.length !== 8) throw new Error("E2E recovery fixture was not generated");
await writeFile(authFile, JSON.stringify({ username: env.CLONE_ADMIN_USERNAME, password: env.CLONE_ADMIN_PASSWORD, codes }));
delete env.CLONE_ADMIN_PASSWORD;
const child = java();
child.stdout.on("data", () => {});
child.stderr.on("data", () => {});
let stopping = false;
async function stop() {
  if (stopping) return;
  stopping = true;
  child.kill("SIGTERM");
}
process.once("SIGINT", stop);
process.once("SIGTERM", stop);
child.once("exit", async (code) => {
  await rm(authFile, { force: true });
  if (data.startsWith(cache + sep)) await rm(data, { recursive: true, force: true, maxRetries: 5, retryDelay: 200 });
  process.exit(stopping ? 0 : (code || 1));
});
console.log("E2E fixture server starting on 127.0.0.1:" + port);
