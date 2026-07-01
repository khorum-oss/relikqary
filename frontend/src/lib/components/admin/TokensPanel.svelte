<script lang="ts">
  import { onMount } from 'svelte';
  import { listTokens, createToken, revokeToken, ApiError, type TokenSummary, type CreatedToken } from '$lib/api';
  import ErrorBanner from '../ErrorBanner.svelte';
  import EmptyState from '../EmptyState.svelte';

  // API token management (feature 016, Phase 3): list, create (secret shown once), revoke. Requires an
  // administrator session (the PUBLISH role); unauthenticated/forbidden states are surfaced clearly.
  let tokens = $state<TokenSummary[]>([]);
  let loaded = $state(false);
  let error = $state('');

  let showCreate = $state(false);
  let name = $state('');
  let scope = $state<'read' | 'publish'>('read');
  let creating = $state(false);
  let created = $state<CreatedToken | null>(null);
  let copied = $state(false);

  onMount(load);

  async function load() {
    error = '';
    try {
      tokens = await listTokens();
    } catch (e) {
      handle(e);
    } finally {
      loaded = true;
    }
  }

  function handle(e: unknown) {
    if (e instanceof ApiError && e.status === 401) {
      error = 'Sign in as an administrator (a user with the PUBLISH role) to manage tokens.';
    } else if (e instanceof ApiError && e.status === 403) {
      error = 'You need the PUBLISH role to manage API tokens.';
    } else {
      error = `Failed (${e})`;
    }
  }

  async function submit(event: SubmitEvent) {
    event.preventDefault();
    if (!name.trim()) return;
    creating = true;
    error = '';
    try {
      created = await createToken(name.trim(), scope);
      showCreate = false;
      name = '';
      scope = 'read';
      await load();
    } catch (e) {
      handle(e);
    } finally {
      creating = false;
    }
  }

  async function copySecret() {
    if (!created) return;
    await navigator.clipboard?.writeText(created.secret);
    copied = true;
    setTimeout(() => (copied = false), 1400);
  }

  async function revoke(token: TokenSummary) {
    if (!confirm(`Revoke token “${token.name}”? This cannot be undone.`)) return;
    try {
      await revokeToken(token.id);
      await load();
    } catch (e) {
      handle(e);
    }
  }

  function fmt(iso?: string | null): string {
    return iso ? new Date(iso).toLocaleDateString() : 'Never';
  }
</script>

<div class="panel" data-testid="tokens-panel">
  <div class="head">
    <span class="rq-uppercase">API Tokens</span>
    <button class="rq-btn rq-btn-primary" data-testid="token-create-open" onclick={() => (showCreate = !showCreate)}>
      + New
    </button>
  </div>

  {#if error}
    <ErrorBanner message={error} />
  {/if}

  {#if created}
    <!-- The secret is only ever shown here, once. -->
    <div class="reveal rq-panel" data-testid="token-created">
      <div class="reveal-title">Token “{created.name}” created — copy it now, it won't be shown again.</div>
      <div class="secret-row">
        <code class="secret" data-testid="token-secret">{created.secret}</code>
        <button class="rq-btn" onclick={copySecret}>{copied ? 'Copied' : 'Copy'}</button>
        <button class="rq-btn" data-testid="token-created-dismiss" onclick={() => (created = null)}>Done</button>
      </div>
    </div>
  {/if}

  {#if showCreate}
    <form class="create rq-panel" onsubmit={submit} data-testid="token-create-form">
      <label>
        Name
        <input class="rq-input" data-testid="token-name" bind:value={name} placeholder="ci-deploy" />
      </label>
      <label>
        Scope
        <select class="rq-input" data-testid="token-scope" bind:value={scope}>
          <option value="read">read — resolve only</option>
          <option value="publish">publish — resolve + publish</option>
        </select>
      </label>
      <button type="submit" class="rq-btn rq-btn-primary" data-testid="token-create-submit" disabled={!name.trim() || creating}>
        {creating ? 'Creating…' : 'Create token'}
      </button>
    </form>
  {/if}

  {#if loaded && tokens.length === 0 && !error}
    <EmptyState message="No API tokens yet. Create one to authenticate CI without sharing a password." />
  {:else if tokens.length > 0}
    <table class="rq-panel" data-testid="tokens-table">
      <thead>
        <tr><th>Name</th><th>Owner</th><th>Scope</th><th>Created</th><th>Last used</th><th></th></tr>
      </thead>
      <tbody>
        {#each tokens as token (token.id)}
          <tr data-testid="token-row" class:revoked={token.revoked}>
            <td class="mono">{token.name}</td>
            <td class="dim">{token.owner}</td>
            <td><span class="scope">{token.scope}</span></td>
            <td class="dim">{fmt(token.createdAt)}</td>
            <td class="dim">{fmt(token.lastUsedAt)}</td>
            <td class="right">
              {#if token.revoked}
                <span class="dim">revoked</span>
              {:else}
                <button class="link" data-testid="token-revoke" onclick={() => revoke(token)}>Revoke</button>
              {/if}
            </td>
          </tr>
        {/each}
      </tbody>
    </table>
  {/if}
</div>

<style>
  .panel {
    display: grid;
    gap: 14px;
  }
  .head {
    display: flex;
    align-items: center;
    justify-content: space-between;
  }
  .reveal {
    border-top: 2px solid var(--rq-gold);
    padding: 14px 18px;
  }
  .reveal-title {
    font-size: 12px;
    color: var(--rq-gold);
    margin-bottom: 10px;
  }
  .secret-row {
    display: flex;
    gap: 8px;
    align-items: center;
  }
  .secret {
    flex: 1;
    font-family: var(--rq-mono);
    font-size: 12px;
    color: var(--rq-text);
    background: var(--rq-inset);
    border: 1px solid var(--rq-border-subtle);
    border-radius: var(--rq-radius);
    padding: 9px 12px;
    word-break: break-all;
  }
  .create {
    padding: 16px 18px;
    display: grid;
    gap: 12px;
    max-width: 26rem;
  }
  label {
    display: grid;
    gap: 6px;
    font-family: var(--rq-serif);
    font-size: 10px;
    letter-spacing: 1.5px;
    text-transform: uppercase;
    color: var(--rq-dim);
  }
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
    padding: 11px 18px;
    border-bottom: 1px solid var(--rq-border-subtle);
    font-size: 13px;
  }
  tr:last-child td {
    border-bottom: none;
  }
  tr.revoked {
    opacity: 0.55;
  }
  .mono {
    font-family: var(--rq-mono);
    color: var(--rq-gold);
  }
  .dim {
    color: var(--rq-dim);
  }
  .scope {
    font-family: var(--rq-serif);
    font-size: 10px;
    letter-spacing: 1px;
    text-transform: uppercase;
    color: var(--rq-muted);
    border: 1px solid var(--rq-border-strong);
    border-radius: var(--rq-radius);
    padding: 2px 8px;
  }
  .right {
    text-align: right;
  }
  button.link {
    background: none;
    border: none;
    color: var(--rq-muted);
    cursor: pointer;
    font-family: var(--rq-serif);
    font-size: 11px;
    letter-spacing: 1px;
    padding: 0;
  }
  button.link:hover {
    color: var(--rq-gold);
  }
</style>
