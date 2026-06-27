<script lang="ts">
  import { onMount } from 'svelte';
  import { listRepositories, type RepositorySummary } from '$lib/api';

  let repos = $state<RepositorySummary[]>([]);
  let error = $state('');

  onMount(async () => {
    try {
      repos = await listRepositories();
    } catch (e) {
      error = `Failed to load repositories (${e})`;
    }
  });
</script>

<h1>Repositories</h1>

{#if error}
  <p class="error" data-testid="error">{error}</p>
{/if}

<ul class="repos">
  {#each repos as repo (repo.name)}
    <li>
      <a href={`/r/${repo.name}/`} data-testid="repo-link">{repo.name}</a>
      <span class="type">{repo.type}</span>
    </li>
  {/each}
</ul>

{#if repos.length === 0 && !error}
  <p>No repositories configured.</p>
{/if}

<style>
  .repos {
    list-style: none;
    padding: 0;
  }
  .repos li {
    padding: 0.6rem 0.8rem;
    background: #fff;
    border: 1px solid #e2e8f0;
    border-radius: 6px;
    margin-bottom: 0.5rem;
    display: flex;
    justify-content: space-between;
  }
  .type {
    color: #718096;
    font-size: 0.8rem;
    text-transform: lowercase;
  }
  .error {
    color: #c53030;
  }
</style>
