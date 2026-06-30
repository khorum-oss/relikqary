<script lang="ts">
  // Path navigation for a repository (feature 005/008; restyled 016).
  let { repo, path }: { repo: string; path: string } = $props();
  let crumbs = $derived(path ? path.split('/') : []);
</script>

<nav class="crumbs" data-testid="breadcrumbs">
  <a href="/">repositories</a>
  <span>›</span>
  <a href={`/r/${repo}/`}>{repo}</a>
  {#each crumbs as seg, i}
    <span>›</span>
    <a href={`/r/${repo}/${crumbs.slice(0, i + 1).join('/')}`}>{seg}</a>
  {/each}
</nav>

<style>
  .crumbs {
    margin-bottom: 16px;
    font-family: var(--rq-mono);
    font-size: 12px;
  }
  .crumbs a {
    color: var(--rq-muted);
  }
  .crumbs a:hover {
    color: var(--rq-gold);
  }
  .crumbs span {
    color: var(--rq-dim);
    margin: 0 0.3rem;
  }
</style>
