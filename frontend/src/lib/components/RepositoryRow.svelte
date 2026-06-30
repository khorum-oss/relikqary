<script lang="ts">
  import KindBadge from './KindBadge.svelte';
  import type { RepositorySummary } from '$lib/api';
  // One repository in the list: a link to browse it plus its kind badge (feature 008; restyled 016).
  let { repo }: { repo: RepositorySummary } = $props();
</script>

<li class="row" data-testid="repo-row">
  <a href={`/r/${repo.name}/`} data-testid="repo-link">{repo.name}</a>
  <span class="meta">
    <span class="type">{repo.type.toLowerCase()}</span>
    <KindBadge kind={repo.kind} />
  </span>
</li>

<style>
  .row {
    padding: 12px 18px;
    background: var(--rq-panel);
    border: 1px solid var(--rq-border);
    border-radius: var(--rq-radius);
    margin-bottom: 8px;
    display: flex;
    justify-content: space-between;
    align-items: center;
  }
  .row:hover {
    background: var(--rq-row-hover);
  }
  .row a {
    font-family: var(--rq-mono);
    font-size: 13px;
    color: var(--rq-gold);
  }
  .meta {
    display: flex;
    align-items: center;
    gap: 0.7rem;
  }
  .type {
    color: var(--rq-dim);
    font-size: 0.8rem;
  }
</style>
