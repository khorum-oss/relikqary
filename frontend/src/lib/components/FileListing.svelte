<script lang="ts">
  import type { ListingEntry } from '$lib/api';
  // The contents table for a folder (feature 005/008; restyled 016): folders link deeper, files open
  // details, each row has a delete action. Authorization is enforced by the backend on the requests.
  let {
    repo,
    path,
    entries,
    onOpen,
    onDelete,
  }: {
    repo: string;
    path: string;
    entries: ListingEntry[];
    onOpen: (name: string) => void;
    onDelete: (name: string) => void;
  } = $props();

  function join(base: string, name: string): string {
    return base ? `${base}/${name}` : name;
  }

  function fmtSize(n?: number): string {
    if (n == null) return '';
    if (n < 1024) return `${n} B`;
    const u = ['KB', 'MB', 'GB'];
    let v = n / 1024;
    let i = 0;
    while (v >= 1024 && i < u.length - 1) {
      v /= 1024;
      i++;
    }
    return `${v.toFixed(1)} ${u[i]}`;
  }
</script>

<table class="rq-panel" data-testid="listing">
  <thead>
    <tr><th>Name</th><th>Size</th><th>Modified</th><th></th></tr>
  </thead>
  <tbody>
    {#each entries as entry (entry.name)}
      <tr>
        <td class="name">
          {#if entry.kind === 'folder'}
            <span class="ico">▸</span>
            <a href={`/r/${repo}/${join(path, entry.name)}`}>{entry.name}/</a>
          {:else}
            <span class="ico">·</span>
            <button class="link" onclick={() => onOpen(entry.name)} data-testid="file">{entry.name}</button>
          {/if}
        </td>
        <td class="dim">{fmtSize(entry.size)}</td>
        <td class="dim">{entry.lastModified ? new Date(entry.lastModified).toLocaleString() : ''}</td>
        <td class="right">
          <button class="danger" onclick={() => onDelete(entry.name)} data-testid="delete">Delete</button>
        </td>
      </tr>
    {/each}
    {#if entries.length === 0}
      <tr><td colspan="4" class="empty">Empty</td></tr>
    {/if}
  </tbody>
</table>

<style>
  table {
    width: 100%;
    border-collapse: collapse;
    overflow: hidden;
  }
  th {
    text-align: left;
    padding: 7px 18px;
    border-bottom: 1px solid var(--rq-border);
    font-family: var(--rq-serif);
    font-size: 10px;
    letter-spacing: 1px;
    text-transform: uppercase;
    color: var(--rq-dim);
  }
  td {
    padding: 10px 18px;
    border-bottom: 1px solid var(--rq-border-subtle);
    font-size: 13px;
  }
  tr:last-child td {
    border-bottom: none;
  }
  tbody tr:hover {
    background: var(--rq-row-hover);
  }
  .name {
    font-family: var(--rq-mono);
  }
  .ico {
    color: var(--rq-dim);
    margin-right: 6px;
  }
  .name a {
    color: var(--rq-gold);
  }
  .dim {
    color: var(--rq-dim);
  }
  .right {
    text-align: right;
  }
  .empty {
    color: var(--rq-dim);
    text-align: center;
  }
  button.link {
    background: none;
    border: none;
    color: var(--rq-gold);
    cursor: pointer;
    padding: 0;
    font: inherit;
    font-family: var(--rq-mono);
  }
  button.link:hover {
    color: var(--rq-text);
  }
  button.danger {
    background: var(--rq-danger-bg);
    border: 1px solid var(--rq-danger);
    color: var(--rq-danger);
    border-radius: var(--rq-radius);
    padding: 3px 10px;
    cursor: pointer;
    font-family: var(--rq-serif);
    font-size: 10px;
    letter-spacing: 1px;
    text-transform: uppercase;
  }
  button.danger:hover {
    opacity: 0.82;
  }
</style>
