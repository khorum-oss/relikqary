<script lang="ts">
  import { page } from '$app/stores';
  import {
    listContents,
    fileDetails,
    deleteEntry,
    basicAuth,
    type ContentsResponse,
    type FileDetails,
  } from '$lib/api';

  let repo = $derived($page.params.repo);
  let path = $derived($page.params.path ?? '');

  let contents = $state<ContentsResponse | null>(null);
  let selected = $state<FileDetails | null>(null);
  let error = $state('');

  function join(base: string, name: string): string {
    return base ? `${base}/${name}` : name;
  }

  async function load(r: string, p: string) {
    error = '';
    selected = null;
    try {
      contents = await listContents(r, p);
    } catch (e) {
      contents = null;
      error = e instanceof Error && e.message === '404' ? 'Not found' : `Failed to load (${e})`;
    }
  }

  $effect(() => {
    load(repo, path);
  });

  async function openFile(name: string) {
    selected = await fileDetails(repo, join(path, name));
  }

  async function remove(name: string) {
    if (!confirm(`Delete ${name}?`)) return;
    const target = join(path, name);
    let ok = await deleteEntry(repo, target);
    if (!ok) {
      const creds = prompt('Credentials required to delete (username:password)');
      if (!creds) return;
      const idx = creds.indexOf(':');
      ok = await deleteEntry(repo, target, basicAuth(creds.slice(0, idx), creds.slice(idx + 1)));
    }
    if (ok) await load(repo, path);
  }

  let crumbs = $derived(path ? path.split('/') : []);
</script>

<nav class="crumbs" data-testid="breadcrumbs">
  <a href="/">repositories</a>
  <span>/</span>
  <a href={`/r/${repo}/`}>{repo}</a>
  {#each crumbs as seg, i}
    <span>/</span>
    <a href={`/r/${repo}/${crumbs.slice(0, i + 1).join('/')}`}>{seg}</a>
  {/each}
</nav>

{#if error}
  <p class="error" data-testid="error">{error}</p>
{/if}

{#if contents}
  <table data-testid="listing">
    <thead><tr><th>Name</th><th>Size</th><th>Modified</th><th></th></tr></thead>
    <tbody>
      {#each contents.entries as entry (entry.name)}
        <tr>
          <td>
            {#if entry.kind === 'folder'}
              📁 <a href={`/r/${repo}/${join(path, entry.name)}`}>{entry.name}/</a>
            {:else}
              📄 <button class="link" onclick={() => openFile(entry.name)} data-testid="file">{entry.name}</button>
            {/if}
          </td>
          <td>{entry.size ?? ''}</td>
          <td>{entry.lastModified ? new Date(entry.lastModified).toLocaleString() : ''}</td>
          <td><button class="danger" onclick={() => remove(entry.name)} data-testid="delete">Delete</button></td>
        </tr>
      {/each}
      {#if contents.entries.length === 0}
        <tr><td colspan="4" class="empty">Empty</td></tr>
      {/if}
    </tbody>
  </table>
{/if}

{#if selected}
  <aside class="details" data-testid="details">
    <h3>{selected.path.split('/').pop()}</h3>
    <dl>
      <dt>Path</dt><dd>{selected.repository}/{selected.path}</dd>
      <dt>Size</dt><dd>{selected.size} bytes</dd>
      {#if selected.lastModified}<dt>Modified</dt><dd>{new Date(selected.lastModified).toLocaleString()}</dd>{/if}
      {#each Object.entries(selected.checksums) as [algo, value]}
        <dt>{algo}</dt><dd class="mono">{value}</dd>
      {/each}
    </dl>
    <a class="button" href={selected.downloadUrl} data-testid="download">Download</a>
  </aside>
{/if}

<style>
  .crumbs {
    margin-bottom: 1rem;
    font-size: 0.9rem;
  }
  .crumbs span {
    color: #a0aec0;
    margin: 0 0.25rem;
  }
  table {
    width: 100%;
    border-collapse: collapse;
    background: #fff;
    border: 1px solid #e2e8f0;
    border-radius: 6px;
  }
  th, td {
    text-align: left;
    padding: 0.5rem 0.75rem;
    border-bottom: 1px solid #edf2f7;
    font-size: 0.9rem;
  }
  .empty {
    color: #a0aec0;
    text-align: center;
  }
  button.link {
    background: none;
    border: none;
    color: #3182ce;
    cursor: pointer;
    padding: 0;
    font: inherit;
  }
  button.danger {
    background: #fff5f5;
    border: 1px solid #feb2b2;
    color: #c53030;
    border-radius: 4px;
    cursor: pointer;
    font-size: 0.8rem;
  }
  .details {
    margin-top: 1.25rem;
    background: #fff;
    border: 1px solid #e2e8f0;
    border-radius: 6px;
    padding: 1rem;
  }
  .details dl {
    display: grid;
    grid-template-columns: 7rem 1fr;
    gap: 0.25rem 0.75rem;
  }
  .details dt {
    color: #718096;
  }
  .mono {
    font-family: monospace;
    word-break: break-all;
  }
  .button {
    display: inline-block;
    margin-top: 0.75rem;
    background: #3182ce;
    color: #fff;
    padding: 0.4rem 0.9rem;
    border-radius: 4px;
    text-decoration: none;
  }
  .error {
    color: #c53030;
  }
</style>
