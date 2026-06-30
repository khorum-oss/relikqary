<script lang="ts">
  import { onMount } from 'svelte';
  import { getStats, type Stats } from '$lib/api';

  // The Dashboard summary cards (feature 016, Phase 2): live repository, artifact, and storage figures
  // from /api/stats. Degrades to skeletons when the feed is momentarily unavailable, never a broken page.
  let stats = $state<Stats | null>(null);
  let failed = $state(false);

  onMount(async () => {
    try {
      stats = await getStats();
    } catch {
      failed = true;
    }
  });

  function fmtBytes(n: number): string {
    if (n < 1024) return `${n} B`;
    const u = ['KB', 'MB', 'GB', 'TB'];
    let v = n / 1024;
    let i = 0;
    while (v >= 1024 && i < u.length - 1) {
      v /= 1024;
      i++;
    }
    return `${v.toFixed(1)} ${u[i]}`;
  }

  let cards = $derived(
    stats
      ? [
          { label: 'Total Artifacts', value: stats.artifacts.toLocaleString(), accent: 'var(--rq-gold)' },
          { label: 'Repositories', value: `${stats.repositories}`, accent: 'var(--rq-muted)' },
          { label: 'Storage Used', value: fmtBytes(stats.storageBytes), accent: 'var(--rq-border-strong)' },
        ]
      : [],
  );
</script>

<div class="cards" data-testid="stat-cards">
  {#if stats}
    {#each cards as card (card.label)}
      <div class="card rq-panel" style="border-top-color:{card.accent}">
        <div class="rq-uppercase">{card.label}</div>
        <div class="value">{card.value}</div>
      </div>
    {/each}
  {:else if failed}
    <div class="card rq-panel"><div class="rq-uppercase">Stats unavailable</div></div>
  {:else}
    {#each [0, 1, 2] as i (i)}
      <div class="card rq-panel skeleton"><div class="bar"></div></div>
    {/each}
  {/if}
</div>

<style>
  .cards {
    display: grid;
    grid-template-columns: repeat(3, 1fr);
    gap: 14px;
  }
  .card {
    padding: 18px 20px;
    border-top: 2px solid var(--rq-border-strong);
  }
  .value {
    font-family: var(--rq-serif);
    font-size: 34px;
    font-weight: 700;
    color: var(--rq-gold);
    margin-top: 8px;
  }
  .skeleton .bar {
    height: 34px;
    margin-top: 18px;
    background: var(--rq-inset);
    border-radius: var(--rq-radius);
    opacity: 0.6;
  }
</style>
