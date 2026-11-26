import { debugFlow, debugPath } from "../debug";
import { ref } from "vue";
import { api } from "../api";
import type { Page } from "../types";

/** 管理列表游标分页工具：翻页失败时保留当前数据与原游标。 */
export function usePaged<T>(path: string) {
  const items = ref<T[]>([]) as import("vue").Ref<T[]>;
  const status = ref(""),
    cursor = ref(""),
    nextCursor = ref("");
  const previous = ref<string[]>([]);

  async function load() {
    debugFlow("PAGING", "读取列表页", {
      path: debugPath(path),
      hasCursor: !!cursor.value,
    });
    const query = new URLSearchParams({
      status: status.value,
      cursor: cursor.value,
    });
    const page = await api<Page<T>>(path + "?" + query);
    items.value = page.items;
    nextCursor.value = page.nextCursor;
  }

  async function reset() {
    const old = cursor.value;
    cursor.value = "";
    try {
      await load();
      previous.value = [];
    } catch (error) {
      cursor.value = old;
      throw error;
    }
  }

  async function next() {
    if (!nextCursor.value) return;
    const old = cursor.value;
    cursor.value = nextCursor.value;
    try {
      await load();
      previous.value.push(old);
    } catch (error) {
      cursor.value = old;
      throw error;
    }
  }

  async function back() {
    if (!previous.value.length) return;
    const old = cursor.value;
    cursor.value = previous.value.at(-1)!;
    try {
      await load();
      previous.value.pop();
    } catch (error) {
      cursor.value = old;
      throw error;
    }
  }

  return { items, status, nextCursor, previous, load, reset, next, back };
}
