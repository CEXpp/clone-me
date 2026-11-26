import { ref } from "vue";

export interface VisitorLocation {
  ip: string;
  place: string;
}

export const visitorLocation = ref<VisitorLocation | null>(null);
let expiresAt = 0;

/** 仅使用接口明确返回的行政区划，不猜想未提供的精确位置。 */
export function formatLocation(data: Record<string, unknown>): string {
  const parts = [data.country, data.region, data.city, data.district];
  return [
    ...new Set(
      parts
        .filter(
          (v): v is string =>
            typeof v === "string" &&
            !!v.trim() &&
            !["unknown", "n/a", "-"].includes(v.trim().toLowerCase()),
        )
        .map((v) => v.trim()),
    ),
  ].join(" · ");
}

export async function loadVisitorLocation(signal: AbortSignal) {
  if (visitorLocation.value && Date.now() < expiresAt) return;
  const timedSignal = AbortSignal.any([signal, AbortSignal.timeout(8000)]);
  const options = {
    signal: timedSignal,
    credentials: "omit" as const,
    referrerPolicy: "no-referrer" as const,
  };
  const response = await fetch("https://api.ipify.org?format=json", options);
  if (!response.ok) throw new Error("IP 查询暂不可用");
  const data = (await response.json()) as { ip?: unknown };
  if (typeof data.ip !== "string" || !/^[0-9a-fA-F:.]{3,45}$/.test(data.ip)) {
    throw new Error("IP 响应无效");
  }
  const ip = data.ip;
  visitorLocation.value = { ip, place: "" };
  const lookup = await fetch(
    `https://card.net.coffee/api/ip/lookup/${encodeURIComponent(ip)}`,
    options,
  );
  if (!lookup.ok) throw new Error("归属地查询暂不可用");
  const geo: unknown = await lookup.json();
  if (!geo || typeof geo !== "object" || Array.isArray(geo)) {
    throw new Error("定位响应无效");
  }
  const place = formatLocation(geo as Record<string, unknown>);
  visitorLocation.value = { ip, place };
  if (place) expiresAt = Date.now() + 10 * 60 * 1000;
}
