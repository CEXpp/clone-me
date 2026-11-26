/** Closed implicit surface: a heart with rounded lobes and real side volume. */
export interface HeartParticle {
  x: number;
  y: number;
  z: number;
  nx: number;
  ny: number;
  nz: number;
  phase: number;
}

export const HEART_TURN_SECONDS = 30;
export function createHeartParticles(count = 7200): HeartParticle[] {
  const particles: HeartParticle[] = [];
  const goldenAngle = Math.PI * (3 - Math.sqrt(5));
  for (let i = 0; i < count; i++) {
    // Fibonacci directions avoid collapsed rings and a visible front/back seam.
    const vertical = 1 - (2 * (i + 0.5)) / count;
    const horizontal = Math.sqrt(1 - vertical * vertical);
    const dx = horizontal * Math.cos(i * goldenAngle);
    const dd = horizontal * Math.sin(i * goldenAngle);
    const a = dx * dx + 2.25 * dd * dd + vertical * vertical;
    const b = vertical ** 3 * (dx * dx + 0.1125 * dd * dd);
    let low = 0,
      high = 2;
    for (let step = 0; step < 30; step++) {
      const r = (low + high) / 2;
      const field = (a * r * r - 1) ** 3 - b * r ** 5;
      if (field > 0) high = r;
      else low = r;
    }
    const r = (low + high) / 2;
    const x = dx * r,
      d = dd * r,
      v = vertical * r;
    const q = x * x + 2.25 * d * d + v * v - 1;
    const nx = 6 * x * q * q - 2 * x * v ** 3;
    const ny = -(6 * v * q * q - 3 * v * v * (x * x + 0.1125 * d * d));
    // Broaden depth and compensate the normal for the nonuniform transform.
    const nz = (13.5 * d * q * q - 0.225 * d * v ** 3) / 1.35;
    const length = Math.hypot(nx, ny, nz) || 1;
    particles.push({
      x: x / 1.1,
      y: -v / 1.1 + 0.07,
      z: (d * 1.35) / 1.1,
      nx: nx / length,
      ny: ny / length,
      nz: nz / length,
      phase: i * 2.399963,
    });
  }
  return particles;
}
