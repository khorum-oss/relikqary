<script lang="ts">
  import Sigil from './Sigil.svelte';

  // The persistent navigation rail (feature 016). Active section derives from the current pathname;
  // the footer holds the session affordance (sign in, or current user + sign out). Anonymous use is
  // supported, so signing in is optional — it is not a gate.
  let {
    pathname,
    user,
    onSignIn,
    onSignOut,
  }: {
    pathname: string;
    user: string | null;
    onSignIn: () => void;
    onSignOut: () => void;
  } = $props();

  const items = [
    { label: 'Dashboard', href: '/dashboard', match: (p: string) => p === '/dashboard' },
    { label: 'Repositories', href: '/', match: (p: string) => p === '/' || p.startsWith('/r/') },
    { label: 'Publish', href: '/publish', match: (p: string) => p.startsWith('/publish') },
    { label: 'Users & Tokens', href: '/users', match: (p: string) => p.startsWith('/users') },
    { label: 'Settings', href: '/settings', match: (p: string) => p.startsWith('/settings') },
  ];

  let initial = $derived((user ?? 'g').charAt(0).toUpperCase());
</script>

<aside class="rail">
  <a class="brand" href="/">
    <Sigil size={24} />
    <span>Relikquary</span>
  </a>

  <nav>
    {#each items as item (item.href)}
      <a class="nav" class:active={item.match(pathname)} href={item.href}>{item.label}</a>
    {/each}
  </nav>

  <div class="session">
    {#if user}
      <div class="who">
        <span class="avatar">{initial}</span>
        <span class="name" data-testid="current-user">{user}</span>
      </div>
      <button class="rq-btn" data-testid="logout-button" onclick={onSignOut}>Sign Out</button>
    {:else}
      <button class="rq-btn rq-btn-primary" data-testid="login-button" onclick={onSignIn}>Sign In</button>
    {/if}
  </div>
</aside>

<style>
  .rail {
    width: 216px;
    min-width: 216px;
    background: var(--rq-rail);
    border-right: 1px solid var(--rq-border);
    display: flex;
    flex-direction: column;
    overflow: hidden;
  }
  .brand {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 16px;
    border-bottom: 1px solid var(--rq-border);
    font-family: var(--rq-serif);
    font-size: 13px;
    font-weight: 700;
    color: var(--rq-gold);
    letter-spacing: 1px;
  }
  .brand:hover {
    color: var(--rq-gold);
  }
  nav {
    flex: 1;
    padding: 8px 0;
    overflow-y: auto;
  }
  .nav {
    display: block;
    padding: 10px 16px;
    color: var(--rq-dim);
    border-left: 2px solid transparent;
    font-size: 13px;
  }
  .nav:hover {
    background: var(--rq-nav-hover);
    color: var(--rq-text);
  }
  .nav.active {
    color: var(--rq-gold);
    background: var(--rq-row-hover);
    border-left-color: var(--rq-gold);
  }
  .session {
    padding: 12px 14px;
    border-top: 1px solid var(--rq-border);
    display: grid;
    gap: 10px;
  }
  .who {
    display: flex;
    align-items: center;
    gap: 8px;
  }
  .avatar {
    width: 26px;
    height: 26px;
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
  .name {
    font-family: var(--rq-serif);
    font-size: 12px;
    color: var(--rq-gold);
  }
</style>
