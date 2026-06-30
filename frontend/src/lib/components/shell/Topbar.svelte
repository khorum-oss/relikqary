<script lang="ts">
  import { version } from '$lib/version';
  import { searchQuery, setSearchQuery } from '$lib/search.svelte';

  // The section header bar (feature 016): the active section title as the page's single h1, an optional
  // artifact search (shown on the catalog), and a version chip.
  let { title, showSearch = false }: { title: string; showSearch?: boolean } = $props();
</script>

<header class="topbar">
  <h1>{title}</h1>
  <div class="spacer"></div>
  {#if showSearch}
    <input
      class="rq-input search"
      type="search"
      placeholder="Search artifacts…"
      data-testid="topbar-search"
      value={searchQuery()}
      oninput={(e) => setSearchQuery((e.currentTarget as HTMLInputElement).value)}
    />
  {/if}
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
  .search {
    width: 240px;
    padding: 6px 11px;
    font-size: 12px;
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
