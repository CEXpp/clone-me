<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from "vue";
import { theme } from "../shared";

const props = defineProps<{
  motion: boolean;
  dimmed?: boolean;
  active?: boolean;
}>();

const canvas = ref<HTMLCanvasElement>();
let frame = 0;
let time = 0;
let last = 0;
let observer: ResizeObserver | undefined;
let pointerX = 0;
let pointerY = 0;
let smoothX = 0;
let smoothY = 0;

const reduced =
  typeof window !== "undefined"
    ? window.matchMedia("(prefers-reduced-motion: reduce)")
    : ({ matches: false } as MediaQueryList);

interface Particle {
  theta: number;
  phi: number;
  bx: number;
  by: number;
  bz: number;
  phase: number;
  ring: number;
  ringAngle: number;
  ringRadius: number;
}

const CORE_COUNT = 1280;
const RING_COUNT = 340;
const particles: Particle[] = [];

// 斐波那契球面均匀分布 + 双倾角相对论光子环
const goldenAngle = Math.PI * (3 - Math.sqrt(5));
for (let i = 0; i < CORE_COUNT; i++) {
  const y = 1 - (i / (CORE_COUNT - 1)) * 2;
  const r = Math.sqrt(Math.max(0, 1 - y * y));
  const theta = goldenAngle * i;
  const x = Math.cos(theta) * r;
  const z = Math.sin(theta) * r;
  const phi = Math.acos(Math.max(-1, Math.min(1, y)));
  particles.push({
    theta,
    phi,
    bx: x,
    by: y,
    bz: z,
    phase: (i * 0.37) % (Math.PI * 2),
    ring: 0,
    ringAngle: 0,
    ringRadius: 0,
  });
}

for (let i = 0; i < RING_COUNT; i++) {
  const ring = i % 2 === 0 ? 1 : 2;
  const angle = (i / (RING_COUNT / 2)) * Math.PI * 2 + (i % 7) * 0.04;
  const radius =
    (ring === 1 ? 1.48 : 1.78) + (((i * 17) % 19) / 19 - 0.5) * 0.11;
  particles.push({
    theta: 0,
    phi: 0,
    bx: 0,
    by: 0,
    bz: 0,
    phase: (i * 0.53) % (Math.PI * 2),
    ring,
    ringAngle: angle,
    ringRadius: radius,
  });
}

interface Projected {
  x: number;
  y: number;
  z: number;
  alpha: number;
  size: number;
  hueType: 0 | 1 | 2;
}

const projected: Projected[] = new Array(particles.length);

function onPointerMove(e: PointerEvent) {
  if (!window.innerWidth || !window.innerHeight) return;
  pointerX = (e.clientX / window.innerWidth - 0.5) * 2;
  pointerY = (e.clientY / window.innerHeight - 0.5) * 2;
}

function draw(now = 0) {
  const el = canvas.value;
  if (!el) return;
  const ctx = el.getContext("2d");
  if (!ctx) return;
  const rect = el.getBoundingClientRect();
  const w = rect.width;
  const h = rect.height;
  if (!w || !h) return;

  const dpr = Math.min(window.devicePixelRatio || 1, 2);
  const targetW = Math.round(w * dpr);
  const targetH = Math.round(h * dpr);
  if (el.width !== targetW || el.height !== targetH) {
    el.width = targetW;
    el.height = targetH;
  }

  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  ctx.clearRect(0, 0, w, h);

  const animated = props.motion && !reduced.matches && !document.hidden;
  if (animated) {
    const dt = Math.min((now - last) / 1000 || 0, 0.05);
    time += dt * (props.active ? 1.75 : 1);
  }
  last = now;

  smoothX += (pointerX - smoothX) * 0.06;
  smoothY += (pointerY - smoothY) * 0.06;

  const isLight = theme.value === "titanium";
  const scale = Math.min(w, h) * (w < 640 ? 0.28 : 0.25);
  const cx = w * 0.5;
  const cy = h * 0.48;

  // 中心星核辉光
  const bloom = ctx.createRadialGradient(cx, cy, 0, cx, cy, scale * 2.15);
  if (isLight) {
    bloom.addColorStop(0, "rgba(37, 99, 235, 0.14)");
    bloom.addColorStop(0.45, "rgba(124, 58, 237, 0.07)");
    bloom.addColorStop(1, "rgba(243, 245, 250, 0)");
  } else {
    bloom.addColorStop(0, "rgba(56, 189, 248, 0.22)");
    bloom.addColorStop(0.45, "rgba(167, 139, 250, 0.1)");
    bloom.addColorStop(1, "rgba(5, 6, 10, 0)");
  }
  ctx.fillStyle = bloom;
  ctx.fillRect(0, 0, w, h);

  const yaw = time * 0.24 + smoothX * 0.35;
  const pitch = -0.18 + Math.sin(time * 0.21) * 0.06 - smoothY * 0.25;
  const cosYaw = Math.cos(yaw),
    sinYaw = Math.sin(yaw);
  const cosPitch = Math.cos(pitch),
    sinPitch = Math.sin(pitch);

  const r1Tilt = 0.42;
  const r2Tilt = -0.58;
  const cosR1 = Math.cos(r1Tilt),
    sinR1 = Math.sin(r1Tilt);
  const cosR2 = Math.cos(r2Tilt),
    sinR2 = Math.sin(r2Tilt);
  const amp = props.active ? 0.13 : 0.085;

  for (let i = 0; i < particles.length; i++) {
    const p = particles[i];
    let lx = 0,
      ly = 0,
      lz = 0;
    let nx = 0,
      ny = 0,
      nz = 0;
    let hueType: 0 | 1 | 2 = 0;

    if (p.ring === 0) {
      const wave =
        1 +
        amp *
          Math.sin(3 * p.theta + time * 1.55) *
          Math.cos(2 * p.phi - time * 1.15);
      lx = p.bx * wave;
      ly = p.by * wave;
      lz = p.bz * wave;
      nx = p.bx;
      ny = p.by;
      nz = p.bz;
      hueType = wave > 1.03 ? 0 : wave < 0.96 ? 1 : 2;
    } else {
      const speed = p.ring === 1 ? 0.38 : -0.27;
      const a = p.ringAngle + time * speed;
      const rx = Math.cos(a) * p.ringRadius;
      const rz = Math.sin(a) * p.ringRadius;
      const ry = Math.sin(a * 3 + time) * 0.03;
      if (p.ring === 1) {
        lx = rx;
        ly = ry * cosR1 - rz * sinR1;
        lz = ry * sinR1 + rz * cosR1;
        hueType = 0;
      } else {
        lx = rx * cosR2 - ry * sinR2;
        ly = rx * sinR2 + ry * cosR2;
        lz = rz;
        hueType = 1;
      }
      nx = lx * 0.5;
      ny = ly * 0.5;
      nz = lz * 0.5;
    }

    // 世界空间 Y/X 旋转
    const wx = lx * cosYaw + lz * sinYaw;
    const wz0 = -lx * sinYaw + lz * cosYaw;
    const wy = ly * cosPitch - wz0 * sinPitch;
    const wz = ly * sinPitch + wz0 * cosPitch;

    const wnx = nx * cosYaw + nz * sinYaw;
    const wnz0 = -nx * sinYaw + nz * cosYaw;
    const wny = ny * cosPitch - wnz0 * sinPitch;
    const wnz = ny * sinPitch + wnz0 * cosPitch;

    const perspective = 4.2 / Math.max(0.8, 4.2 - wz);
    const sx = cx + wx * scale * perspective;
    const sy = cy + wy * scale * perspective;

    const rim = Math.pow(1 - Math.min(1, Math.abs(wnz)), 3);
    const key = Math.max(0, wnx * -0.35 + wny * -0.5 + wnz * 0.78);
    const shimmer = 0.88 + 0.12 * Math.sin(p.phase + time * 1.4);
    const front = wnz > -0.15;

    let alpha =
      p.ring === 0
        ? front
          ? (0.24 + key * 0.52 + rim * 0.28) * shimmer
          : 0.08
        : (0.28 + (wz + 1.8) * 0.16) * shimmer;

    if (isLight) alpha = Math.min(0.85, alpha * 0.9);
    else alpha = Math.min(0.95, alpha);

    const size =
      (p.ring === 0 ? 0.85 + key * 0.85 + rim * 0.55 : 0.75 + rim * 0.45) *
      perspective *
      (w < 640 ? 0.85 : 1);

    projected[i] = { x: sx, y: sy, z: wz, alpha, size, hueType };
  }

  projected.sort((a, b) => a.z - b.z);

  for (let i = 0; i < projected.length; i++) {
    const pt = projected[i];
    ctx.beginPath();
    ctx.arc(pt.x, pt.y, pt.size, 0, Math.PI * 2);
    if (isLight) {
      ctx.fillStyle =
        pt.hueType === 0
          ? `rgba(37, 99, 235, ${pt.alpha})`
          : pt.hueType === 1
            ? `rgba(124, 58, 237, ${pt.alpha})`
            : `rgba(15, 23, 42, ${pt.alpha * 0.75})`;
    } else {
      ctx.fillStyle =
        pt.hueType === 0
          ? `rgba(125, 211, 252, ${pt.alpha})`
          : pt.hueType === 1
            ? `rgba(167, 139, 250, ${pt.alpha})`
            : `rgba(244, 247, 255, ${pt.alpha * 0.9})`;
    }
    ctx.fill();
  }

  if (animated) {
    frame = requestAnimationFrame(draw);
  }
}

function restart() {
  cancelAnimationFrame(frame);
  last = performance.now();
  draw(last);
}

onMounted(() => {
  observer = new ResizeObserver(restart);
  if (canvas.value) observer.observe(canvas.value);
  window.addEventListener("pointermove", onPointerMove, { passive: true });
  document.addEventListener("visibilitychange", restart);
  reduced.addEventListener?.("change", restart);
  restart();
});

watch(() => [props.motion, theme.value, props.active], restart);

onBeforeUnmount(() => {
  cancelAnimationFrame(frame);
  observer?.disconnect();
  window.removeEventListener("pointermove", onPointerMove);
  document.removeEventListener("visibilitychange", restart);
  reduced.removeEventListener?.("change", restart);
});
</script>

<template>
  <div
    class="singularity-backdrop"
    :class="{ 'is-dimmed': dimmed }"
    aria-hidden="true"
  >
    <canvas ref="canvas" class="singularity-canvas"></canvas>
  </div>
</template>

<style scoped>
.singularity-backdrop {
  position: fixed;
  inset: 0;
  z-index: 0;
  pointer-events: none;
  transition: opacity 0.35s ease;
  opacity: 1;
}

.singularity-backdrop.is-dimmed {
  opacity: 0.4;
}

.singularity-canvas {
  display: block;
  width: 100%;
  height: 100%;
}
</style>
