<script lang="ts">
  import { version } from '$lib/version';

  // The section header bar (feature 016): the active section title as the page's single h1, plus a
  // version chip. A right-aligned slot is reserved for per-section controls (e.g. Phase 2 search).
  let { title, children }: { title: string; children?: import('svelte').Snippet } = $props();
</script>

<header class="topbar">
  <h1>{title}</h1>
  <div class="spacer"></div>
  {#if children}{@render children()}{/if}
  <span class="version">v{version}</span>
</header>

<style>
  .topbar {
    height: 48px;
    flex-shrink: 0;
    background: #130e06;
    border-bottom: 1px solid var(--rq-border);
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 0 22px;
  }
  h1 {
    margin: 0;
    font-family: var(--rq-serif);
    font-size: 15px;
    font-weight: 600;
    letter-spacing: 1px;
    color: var(--rq-gold);
  }
  .spacer {
    flex: 1;
  }
  .version {
    font-family: var(--rq-serif);
    font-size: 10px;
    color: var(--rq-dim);
    padding: 3px 8px;
    border: 1px solid var(--rq-border-subtle);
    border-radius: var(--rq-radius);
    white-space: nowrap;
  }
</style>
