<script lang="ts">
  import TokensPanel from '$lib/components/admin/TokensPanel.svelte';
  import Placeholder from '$lib/components/Placeholder.svelte';

  // Users & Tokens (feature 016, Phase 3). API tokens are live (US7); managed users arrive with US8, so
  // that tab is a placeholder for now.
  let tab = $state<'tokens' | 'users'>('tokens');
</script>

<div class="tabs">
  <button class:active={tab === 'tokens'} data-testid="tab-tokens" onclick={() => (tab = 'tokens')}>API Tokens</button>
  <button class:active={tab === 'users'} data-testid="tab-users" onclick={() => (tab = 'users')}>Users</button>
</div>

{#if tab === 'tokens'}
  <TokensPanel />
{:else}
  <Placeholder
    title="Users"
    note="Managed user accounts arrive with the next Phase 3 slice. Today, users are defined in server configuration; API tokens (left) already let you authenticate CI without sharing a password."
  />
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
