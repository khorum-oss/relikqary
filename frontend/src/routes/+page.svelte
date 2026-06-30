<script lang="ts">
  import { onMount } from 'svelte';
  import { listRepositories, type RepositorySummary } from '$lib/api';
  import RepositoryRow from '$lib/components/RepositoryRow.svelte';
  import ErrorBanner from '$lib/components/ErrorBanner.svelte';
  import EmptyState from '$lib/components/EmptyState.svelte';

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

<style>
  .repos {
    list-style: none;
    padding: 0;
    margin: 0;
  }
</style>
