<script lang="ts">
  import { onMount } from 'svelte';
  import { listRepositories, upload, type RepositorySummary } from '$lib/api';
  import UploadForm from '$lib/components/UploadForm.svelte';
  import EmptyState from '$lib/components/EmptyState.svelte';

  let hosted = $state<RepositorySummary[]>([]);
  let selected = $state('');
  let copied = $state(false);

  let repoUrl = $derived(
    typeof window !== 'undefined' ? `${window.location.origin}/${selected || 'releases'}` : `/${selected || 'releases'}`,
  );
  let snippet = $derived(
    `plugins { \`maven-publish\` }\n\n` +
      `publishing {\n` +
      `    publications {\n` +
      `        create<MavenPublication>("release") {\n` +
      `            from(components["java"])\n` +
      `        }\n` +
      `    }\n` +
      `    repositories {\n` +
      `        maven {\n` +
      `            name = "relikquary"\n` +
      `            url = uri("${repoUrl}")\n` +
      `            credentials {\n` +
      `                username = System.getenv("RELIKQUARY_USER")\n` +
      `                password = System.getenv("RELIKQUARY_TOKEN")\n` +
      `            }\n` +
      `        }\n` +
      `    }\n` +
      `}`,
  );

  onMount(async () => {
    try {
      const all = await listRepositories();
      hosted = all.filter((r) => r.kind === 'HOSTED');
      selected = hosted[0]?.name ?? '';
    } catch {
      hosted = [];
    }
  });

  async function copy() {
    await navigator.clipboard?.writeText(snippet);
    copied = true;
    setTimeout(() => (copied = false), 1400);
  }

  async function doUpload(path: string, file: File): Promise<string | null> {
    const status = await upload(selected, path, file);
    if (status === 200 || status === 201) return null;
    if (status === 401) return 'Sign in to publish.';
    if (status === 403) return 'You are not permitted to publish here.';
    if (status === 409) return 'Conflict: this release already exists and is immutable.';
    if (status === 405) return 'This repository does not accept uploads.';
    return `Upload failed (${status}).`;
  }
</script>

<section class="block rq-panel">
  <div class="head">
    <div class="title">Publish via Gradle</div>
    <div class="hint">Add to your <span class="rq-mono">build.gradle.kts</span> and run <span class="rq-mono">./gradlew publish</span></div>
  </div>
  <div class="code-wrap">
    <pre>{snippet}</pre>
    <button class="copy" onclick={copy} data-testid="publish-copy">{copied ? 'Copied' : 'Copy'}</button>
  </div>
</section>

{#if hosted.length === 0}
  <EmptyState message="No hosted repositories are configured to publish into." />
{:else}
  <section class="picker">
    <span class="rq-uppercase">Target repository</span>
    <select class="rq-input select" bind:value={selected} data-testid="publish-repo">
      {#each hosted as r (r.name)}
        <option value={r.name}>{r.name}</option>
      {/each}
    </select>
  </section>
  {#key selected}
    <UploadForm repo={selected} basePath="" onUpload={doUpload} />
  {/key}
{/if}

<style>
  .block {
    margin-bottom: 14px;
  }
  .head {
    padding: 14px 18px;
    border-bottom: 1px solid var(--rq-border);
  }
  .title {
    font-family: var(--rq-serif);
    font-size: 13px;
    color: var(--rq-gold);
  }
  .hint {
    font-size: 11px;
    color: var(--rq-muted);
    margin-top: 2px;
  }
  .code-wrap {
    position: relative;
    padding: 16px 18px;
  }
  pre {
    margin: 0;
    background: var(--rq-inset);
    border: 1px solid var(--rq-border-subtle);
    color: var(--rq-text);
    padding: 14px 16px;
    border-radius: var(--rq-radius);
    overflow-x: auto;
    font-family: var(--rq-mono);
    font-size: 12px;
    line-height: 1.7;
  }
  .copy {
    position: absolute;
    top: 26px;
    right: 28px;
    font-family: var(--rq-serif);
    font-size: 10px;
    letter-spacing: 1px;
    padding: 0.25rem 0.7rem;
    border: 1px solid var(--rq-border-strong);
    border-radius: var(--rq-radius);
    background: var(--rq-panel);
    color: var(--rq-muted);
    cursor: pointer;
  }
  .copy:hover {
    color: var(--rq-gold);
  }
  .picker {
    display: flex;
    align-items: center;
    gap: 10px;
    margin: 6px 0 2px;
  }
  .select {
    width: auto;
    min-width: 14rem;
  }
</style>
