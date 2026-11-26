<script setup lang="ts">
import { useRouter } from "vue-router";
import { api, resetCsrf } from "../api";
import { authenticated } from "../router";
import { useAction } from "../shared";
import NotebookShell from "../../../frontend/src/components/NotebookShell.vue";
const router = useRouter();
const { busy, error, run } = useAction();
async function logout() {
  await run(async () => {
    // Leaving the editor first runs its save guard before the session is revoked.
    if (await router.push("/admin/usage")) return;
    await api("/api/auth/logout", "POST", {});
    authenticated.value = false;
    resetCsrf();
    await router.replace("/admin/login");
  });
}
</script>
<template>
  <NotebookShell
    :authenticated="authenticated"
    :busy="busy"
    :error="error"
    @logout="logout"
  />
</template>
