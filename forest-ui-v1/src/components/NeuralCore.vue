<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref, watch } from "vue";
import { createHeartParticles, HEART_TURN_SECONDS } from "../heartModel";
const props = defineProps<{ motion: boolean }>();
const canvas = ref<HTMLCanvasElement>();
let frame = 0,
  time = 0,
  last = 0;
let observer: ResizeObserver;
const reduced = window.matchMedia("(prefers-reduced-motion: reduce)");
const heartPoints = createHeartParticles();
function draw(now = 0) {
  const el = canvas.value;
  if (!el) return;
  const ctx = el.getContext("2d");
  if (!ctx) return;
  const { width: w, height: h } = el.getBoundingClientRect();
  if (!w || !h) return;
  const dpr = Math.min(devicePixelRatio, 2);
  if (el.width !== Math.round(w * dpr) || el.height !== Math.round(h * dpr)) {
    el.width = Math.round(w * dpr);
    el.height = Math.round(h * dpr);
  }
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  ctx.clearRect(0, 0, w, h);
  if (props.motion && !reduced.matches && !document.hidden)
    time += Math.min((now - last) / 1000 || 0, 0.04);
  last = now;
  const radius = Math.min(w * 0.32, h * 0.37),
    orbitRadius = Math.min(w * 0.29, h * 0.34),
    cx = w * 0.5,
    cy = h * 0.47;
  const glow = ctx.createRadialGradient(cx, cy, 0, cx, cy, radius * 1.8);
  glow.addColorStop(0, "#16a5de24");
  glow.addColorStop(0.5, "#127fe315");
  glow.addColorStop(1, "#1177df00");
  ctx.fillStyle = glow;
  ctx.fillRect(0, 0, w, h);
  ctx.save();
  ctx.translate(cx, cy + radius * 0.24);
  ctx.rotate(-0.18);
  for (let k = 0; k < 2; k++) {
    ctx.beginPath();
    ctx.ellipse(
      0,
      0,
      orbitRadius * (1.37 + k * 0.13),
      orbitRadius * (0.44 + k * 0.1),
      0,
      0,
      Math.PI * 2,
    );
    ctx.strokeStyle = k === 0 ? "#62dbff40" : "#57aaff1a";
    ctx.lineWidth = k === 0 ? 1.2 : 0.6;
    ctx.stroke();
  }
  ctx.restore();
  const yaw = 0.24 + (time * Math.PI * 2) / HEART_TURN_SECONDS;
  const tilt = -0.1 + Math.sin(time * 0.27) * 0.035;
  const beat = 1 + 0.016 * Math.sin(time * 1.65);
  const cosYaw = Math.cos(yaw),
    sinYaw = Math.sin(yaw);
  const cosTilt = Math.cos(tilt),
    sinTilt = Math.sin(tilt);
  const cosRoll = Math.cos(-0.055),
    sinRoll = Math.sin(-0.055);
  const points = heartPoints.map((p) => {
    const xx = p.x * cosYaw + p.z * sinYaw;
    const zz = -p.x * sinYaw + p.z * cosYaw;
    const yy = p.y * cosTilt - zz * sinTilt;
    const z = p.y * sinTilt + zz * cosTilt;
    const nx = p.nx * cosYaw + p.nz * sinYaw;
    const nz1 = -p.nx * sinYaw + p.nz * cosYaw;
    const ny = p.ny * cosTilt - nz1 * sinTilt;
    const nz = p.ny * sinTilt + nz1 * cosTilt;
    const perspective = 5 / (5 - z);
    // A fixed upper-left key light makes the real volume legible as it turns.
    const light = Math.max(0, nx * -0.35 + ny * -0.5 + nz * 0.79);
    const rim = (1 - Math.abs(nz)) ** 3;
    return {
      x: cx + (xx * cosRoll - yy * sinRoll) * radius * beat * perspective,
      y: cy + (xx * sinRoll + yy * cosRoll) * radius * beat * perspective,
      z,
      light,
      rim,
      facing: nz,
      shimmer: 0.94 + 0.06 * Math.sin(p.phase + time * 0.8),
    };
  });
  points.sort((a, b) => a.z - b.z);
  for (const p of points) {
    ctx.beginPath();
    // Dim the rear shell; avoid see-through faces flattening the silhouette.
    const rear = p.facing < -0.08;
    const brightness = rear
      ? 0.045
      : (0.2 + p.light * 0.61 + p.rim * 0.19) * p.shimmer;
    ctx.arc(
      p.x,
      p.y,
      (0.6 + p.light * 0.48 + p.rim * 0.2) * Math.min(1, w / 450 + 0.3),
      0,
      Math.PI * 2,
    );
    ctx.fillStyle = `rgba(${!rear && p.light > 0.48 ? "161,240,255" : "64,160,242"},${brightness})`;
    ctx.fill();
  }
  for (let i = 0; i < 9; i++) {
    const a = i * 2.4 + time * 0.23;
    const x = cx + Math.cos(a) * orbitRadius * 1.55,
      y = cy + radius * 0.24 + Math.sin(a) * orbitRadius * 0.58;
    ctx.beginPath();
    ctx.arc(x, y, i % 3 === 0 ? 2.7 : 1.4, 0, Math.PI * 2);
    ctx.fillStyle = "#a4f1ff";
    ctx.shadowColor = "#4bcdff";
    ctx.shadowBlur = 12;
    ctx.fill();
    ctx.shadowBlur = 0;
  }
  if (props.motion && !reduced.matches && !document.hidden)
    frame = requestAnimationFrame(draw);
}
function restart() {
  cancelAnimationFrame(frame);
  last = performance.now();
  draw(last);
}
onMounted(() => {
  observer = new ResizeObserver(restart);
  observer.observe(canvas.value!);
  document.addEventListener("visibilitychange", restart);
  reduced.addEventListener("change", restart);
  restart();
});
watch(() => props.motion, restart);
onBeforeUnmount(() => {
  cancelAnimationFrame(frame);
  observer?.disconnect();
  document.removeEventListener("visibilitychange", restart);
  reduced.removeEventListener("change", restart);
});
</script>
<template>
  <div class="core-visual" aria-hidden="true">
    <div class="core-grid"></div>
    <canvas ref="canvas"></canvas
    ><span class="core-label core-label-one">A HEART / DIGITAL PRESENCE</span
    ><span class="core-label core-label-two"
      >MEMORY<br />CONNECTION<br />REASONING</span
    ><span class="core-coordinate">+ &nbsp; PERSONAL EDITION</span>
    <div class="core-caption">
      <span class="status-dot"></span> 一颗心 · 记忆与思考的共鸣
    </div>
  </div>
</template>
