<script lang="ts">
  import type { FileDetails } from '$lib/api';
  // File details + a Download action (feature 005/008; restyled 016). Download is delegated so the
  // parent can fetch with credentials and save a Blob (private repos).
  let { details, onDownload }: { details: FileDetails; onDownload: () => void } = $props();
</script>

<aside class="details rq-panel" data-testid="details">
  <h3>{details.path.split('/').pop()}</h3>
  <dl>
    <dt>Path</dt>
    <dd class="mono">{details.repository}/{details.path}</dd>
    <dt>Size</dt>
    <dd>{details.size} bytes</dd>
    {#if details.lastModified}
      <dt>Modified</dt>
      <dd>{new Date(details.lastModified).toLocaleString()}</dd>
    {/if}
    {#each Object.entries(details.checksums) as [algo, value]}
      <dt>{algo}</dt>
      <dd class="mono">{value}</dd>
    {/each}
  </dl>
  <button class="rq-btn rq-btn-primary" onclick={onDownload} data-testid="download">Download</button>
</aside>

<style>
  .details {
    margin-top: 1.25rem;
    padding: 18px;
    border-top: 2px solid var(--rq-gold);
  }
  h3 {
    margin: 0 0 0.75rem;
    font-family: var(--rq-serif);
    font-size: 14px;
    color: var(--rq-gold);
  }
  dl {
    display: grid;
    grid-template-columns: 7rem 1fr;
    gap: 0.3rem 0.75rem;
    margin: 0 0 0.9rem;
  }
  dt {
    font-family: var(--rq-serif);
    font-size: 10px;
    letter-spacing: 1px;
    text-transform: uppercase;
    color: var(--rq-muted);
  }
  dd {
    margin: 0;
    color: var(--rq-text);
    font-size: 12px;
  }
  .mono {
    font-family: var(--rq-mono);
    word-break: break-all;
    color: var(--rq-dim);
  }
</style>
