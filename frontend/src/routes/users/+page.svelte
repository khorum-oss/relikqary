<script lang="ts">
  import TokensPanel from '$lib/components/admin/TokensPanel.svelte';
  import UsersPanel from '$lib/components/admin/UsersPanel.svelte';

  // Users & Tokens (feature 016, Phase 3): managed users (US8) and API tokens (US7).
  let tab = $state<'users' | 'tokens'>('users');
</script>

<div class="tabs">
  <button class:active={tab === 'users'} data-testid="tab-users" onclick={() => (tab = 'users')}>Users</button>
  <button class:active={tab === 'tokens'} data-testid="tab-tokens" onclick={() => (tab = 'tokens')}>API Tokens</button>
</div>

{#if tab === 'users'}
  <UsersPanel />
{:else}
  <TokensPanel />
{/if}

<style>
  .tabs {
    display: flex;
    gap: 4px;
    margin-bottom: 16px;
  }
  .tabs button {
    background: var(--rq-panel);
    border: 1px solid var(--rq-border);
    color: var(--rq-dim);
    padding: 7px 16px;
    font-family: var(--rq-serif);
    font-size: 11px;
    letter-spacing: 1px;
    border-radius: var(--rq-radius);
    cursor: pointer;
  }
  .tabs button.active {
    color: var(--rq-gold);
    border-color: var(--rq-gold);
  }
</style>
