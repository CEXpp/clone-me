import { debugFlow, debugPath } from "../debug";
import { ref } from "vue";
import { api } from "../api";
import type { Page } from "../types";

/** 两个管理列表共用稳定游标，翻页失败时恢复原位置，不丢失当前列表。 */
export function usePaged<T>(path: string) {
  const items = ref<T[]>([]) as import("vue").Ref<T[]>;
  const status = ref(""),
    cursor = ref(""),
    nextCursor = ref("");
  const previous = ref<string[]>([]);
  // previous 是“上一页起点”的栈；游标不是页码，不能简单通过 page + 1 推算。
  async function load() {
    debugFlow("PAGING", "开始读取列表页", {
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
    debugFlow("PAGING", "列表页已加载", {
      itemCount: page.items.length,
      hasNext: !!page.nextCursor,
    });
  }
  async function reset() {
    debugFlow("PAGING", "重置列表，尝试读取第一页");
    const old = cursor.value;
    cursor.value = "";
    try {
      await load();
      previous.value = [];
    } catch (error) {
      cursor.value = old;
      debugFlow("PAGING", "加载失败，已恢复原游标并保留当前列表");
      throw error;
    }
  }
  async function next() {
    // 先试读目标页，成功才登记历史；失败恢复旧游标，与屏幕上保留的旧数据保持一致。
    if (!nextCursor.value) return;
    debugFlow("PAGING", "尝试读取下一页，成功后再登记翻页历史");
    const old = cursor.value;
    cursor.value = nextCursor.value;
    try {
      await load();
      previous.value.push(old);
    } catch (error) {
      cursor.value = old;
      debugFlow("PAGING", "加载失败，已恢复原游标并保留当前列表");
      throw error;
    }
  }
  async function back() {
    if (!previous.value.length) return;
    debugFlow("PAGING", "尝试读取上一页");
    const old = cursor.value;
    cursor.value = previous.value.at(-1)!;
    try {
      await load();
      previous.value.pop();
    } catch (error) {
      cursor.value = old;
      debugFlow("PAGING", "加载失败，已恢复原游标并保留当前列表");
      throw error;
    }
  }
  return { items, status, nextCursor, previous, load, reset, next, back };
}
