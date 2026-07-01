<script lang="ts">
  import { onMount } from 'svelte';
  import { listUsers, createUser, deleteUser, ApiError, type UserSummary } from '$lib/api';
  import ErrorBanner from '../ErrorBanner.svelte';
  import EmptyState from '../EmptyState.svelte';

  // Managed-user administration (feature 016, Phase 3, US8): list, create, delete. Managed users coexist
  // with static-config users (which are shown by the server only via config, not here). Requires the
  // PUBLISH (admin) role.
  let users = $state<UserSummary[]>([]);
  let loaded = $state(false);
  let error = $state('');

  let showCreate = $state(false);
  let username = $state('');
  let email = $state('');
  let password = $state('');
  let role = $state<'publisher' | 'viewer'>('viewer');
  let creating = $state(false);

  onMount(load);

  async function load() {
    error = '';
    try {
      users = await listUsers();
    } catch (e) {
      handle(e);
    } finally {
      loaded = true;
    }
  }

  function handle(e: unknown) {
    if (e instanceof ApiError && e.status === 401) {
      error = 'Sign in as an administrator (a user with the PUBLISH role) to manage users.';
    } else if (e instanceof ApiError && e.status === 403) {
      error = 'You need the PUBLISH role to manage users.';
    } else if (e instanceof ApiError && e.status === 409) {
      error = 'That username already exists (or matches a configured user).';
    } else if (e instanceof ApiError && e.status === 400) {
      error = 'A username and password are required.';
    } else {
      error = `Failed (${e})`;
    }
  }

  async function submit(event: SubmitEvent) {
    event.preventDefault();
    if (!username.trim() || !password) return;
    creating = true;
    error = '';
    try {
      await createUser(username.trim(), email.trim(), password, role === 'publisher' ? ['PUBLISH'] : []);
      showCreate = false;
      username = '';
      email = '';
      password = '';
      role = 'viewer';
      await load();
    } catch (e) {
      handle(e);
    } finally {
      creating = false;
    }
  }

  async function remove(user: UserSummary) {
    if (!confirm(`Delete user “${user.username}”?`)) return;
    try {
      await deleteUser(user.id);
      await load();
    } catch (e) {
      handle(e);
    }
  }

  function roleLabel(roles: string[]): string {
    return roles.includes('PUBLISH') ? 'Publisher' : 'Viewer';
  }

  function fmt(iso?: string | null): string {
    return iso ? new Date(iso).toLocaleDateString() : 'Never';
  }
</script>

<div class="panel" data-testid="users-panel">
  <div class="head">
    <span class="rq-uppercase">Managed Users</span>
    <button class="rq-btn rq-btn-primary" data-testid="user-create-open" onclick={() => (showCreate = !showCreate)}>
      + New
    </button>
  </div>

  {#if error}
    <ErrorBanner message={error} />
  {/if}

  {#if showCreate}
    <form class="create rq-panel" onsubmit={submit} data-testid="user-create-form">
      <label>
        Username
        <input class="rq-input" data-testid="user-username" bind:value={username} placeholder="alice" />
      </label>
      <label>
        Email <span class="opt">(optional)</span>
        <input class="rq-input" data-testid="user-email" bind:value={email} placeholder="alice@example.com" />
      </label>
      <label>
        Password
        <input class="rq-input" type="password" data-testid="user-password" bind:value={password} />
      </label>
      <label>
        Role
        <select class="rq-input" data-testid="user-role" bind:value={role}>
          <option value="viewer">Viewer — read only</option>
          <option value="publisher">Publisher — read + publish</option>
        </select>
      </label>
      <button type="submit" class="rq-btn rq-btn-primary" data-testid="user-create-submit" disabled={!username.trim() || !password || creating}>
        {creating ? 'Creating…' : 'Create user'}
      </button>
    </form>
  {/if}

  {#if loaded && users.length === 0 && !error}
    <EmptyState message="No managed users yet. Configured users still sign in as before." />
  {:else if users.length > 0}
    <table class="rq-panel" data-testid="users-table">
      <thead>
        <tr><th>Username</th><th>Email</th><th>Role</th><th>Last active</th><th></th></tr>
      </thead>
      <tbody>
        {#each users as user (user.id)}
          <tr data-testid="user-row">
            <td class="who"><span class="avatar">{user.username.charAt(0).toUpperCase()}</span>{user.username}</td>
            <td class="dim">{user.email ?? '—'}</td>
            <td><span class="role">{roleLabel(user.roles)}</span></td>
            <td class="dim">{fmt(user.lastActiveAt)}</td>
            <td class="right">
              <button class="link" data-testid="user-delete" onclick={() => remove(user)}>Delete</button>
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
  .opt {
    color: var(--rq-dim);
    letter-spacing: 0;
    text-transform: none;
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
  .who {
    display: flex;
    align-items: center;
    gap: 8px;
    color: var(--rq-gold);
    font-family: var(--rq-mono);
  }
  .avatar {
    width: 24px;
    height: 24px;
    flex-shrink: 0;
    border-radius: 50%;
    background: var(--rq-panel);
    border: 1px solid var(--rq-border-strong);
    display: flex;
    align-items: center;
    justify-content: center;
    font-family: var(--rq-serif);
    font-size: 11px;
    color: var(--rq-gold);
  }
  .dim {
    color: var(--rq-dim);
  }
  .role {
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
