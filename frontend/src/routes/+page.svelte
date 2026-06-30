<script lang="ts">
  import { onMount } from 'svelte';
  import { listRepositories, type RepositorySummary } from '$lib/api';
  import RepositoryRow from '$lib/components/RepositoryRow.svelte';
  import CatalogTable from '$lib/components/catalog/CatalogTable.svelte';
  import ErrorBanner from '$lib/components/ErrorBanner.svelte';
  import EmptyState from '$lib/components/EmptyState.svelte';

  // Two coexisting views (feature 016, Phase 2): the cross-repo artifact catalog (default, searchable)
  // and the configured-repository list, which drills into the raw folder/tree browser.
  let view = $state<'catalog' | 'repos'>('catalog');
  let repos = $state<RepositorySummary[]>([]);
  let error = $state('');
  let loaded = $state(false);

  onMount(async () => {
    try {
      repos = await listRepositories();
    } catch (e) {
      error = `Failed to load repositories (${e})`;
    } finally {
      loaded = true;
    }
  });
</script>

<div class="tabs">
  <button class:active={view === 'catalog'} data-testid="catalog-tab" onclick={() => (view = 'catalog')}>Catalog</button>
  <button class:active={view === 'repos'} data-testid="repos-tab" onclick={() => (view = 'repos')}>Repositories</button>
</div>

{#if view === 'catalog'}
  <CatalogTable />
{:else}
  {#if error}
    <ErrorBanner message={error} />
  {/if}
  <ul class="repos">
    {#each repos as repo (repo.name)}
      <RepositoryRow {repo} />
    {/each}
  </ul>
  {#if loaded && repos.length === 0 && !error}
    <EmptyState message="No repositories configured." />
  {/if}
{/if}

<style>
  .tabs {
    display: flex;
    gap: 4px;
    margin-bottom: 16px;
  }
  .tabs button {
    background: var(--rq-panel);
    border: 1px solid var(--rq-border);
    color: var(--rq-dim);
    padding: 7px 16px;
    font-family: var(--rq-serif);
    font-size: 11px;
    letter-spacing: 1px;
    border-radius: var(--rq-radius);
    cursor: pointer;
  }
  .tabs button.active {
    color: var(--rq-gold);
    border-color: var(--rq-gold);
  }
  .repos {
    list-style: none;
    padding: 0;
    margin: 0;
  }
</style>
