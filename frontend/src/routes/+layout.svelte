<script lang="ts">
  import '$lib/theme/tokens.css';
  import { page } from '$app/stores';
  import { currentUser, login, logout } from '$lib/auth.svelte';
  import AppShell from '$lib/components/shell/AppShell.svelte';
  import LoginForm from '$lib/components/LoginForm.svelte';
  import Sigil from '$lib/components/shell/Sigil.svelte';

  let { children } = $props();
  let user = $derived(currentUser());
  let showLogin = $state(false);

  const titles: Array<[(p: string) => boolean, string]> = [
    [(p) => p === '/dashboard', 'Dashboard'],
    [(p) => p === '/' || p.startsWith('/r/'), 'Repositories'],
    [(p) => p.startsWith('/publish'), 'Publish'],
    [(p) => p.startsWith('/users'), 'Users & Tokens'],
    [(p) => p.startsWith('/settings'), 'Settings'],
  ];
  let pathname = $derived($page.url.pathname);
  let title = $derived(titles.find(([m]) => m(pathname))?.[1] ?? 'Repositories');

  function doLogin(username: string, password: string) {
    login(username, password);
    showLogin = false;
  }
</script>

<AppShell {pathname} {title} {user} onSignIn={() => (showLogin = true)} onSignOut={logout}>
  {@render children()}
</AppShell>

{#if showLogin}
  <div class="login-screen" data-testid="login-screen">
    <div class="grid"></div>
    <div class="card rq-panel">
      <div class="card-head">
        <Sigil size={48} />
        <div class="title">Relikquary</div>
        <div class="subtitle">Artifact Sanctuary</div>
      </div>
      <LoginForm onSubmit={doLogin} onCancel={() => (showLogin = false)} title="Enter the Vault" />
    </div>
  </div>
{/if}

<style>
  .login-screen {
    position: fixed;
    inset: 0;
    z-index: 50;
    background: var(--rq-bg);
    display: flex;
    align-items: center;
    justify-content: center;
    overflow: hidden;
  }
  .grid {
    position: absolute;
    inset: 0;
    background-image: radial-gradient(circle, #c9a22710 0.5px, transparent 0.5px);
    background-size: 22px 22px;
    pointer-events: none;
  }
  .card {
    position: relative;
    width: 390px;
    background: var(--rq-shell);
    border-color: var(--rq-border-strong);
  }
  .card-head {
    padding: 28px 32px;
    border-bottom: 1px solid var(--rq-border);
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 8px;
  }
  .title {
    font-family: var(--rq-serif);
    font-size: 22px;
    font-weight: 700;
    color: var(--rq-gold);
    letter-spacing: 3px;
  }
  .subtitle {
    font-family: var(--rq-serif);
    font-size: 10px;
    letter-spacing: 2.5px;
    color: var(--rq-muted);
    text-transform: uppercase;
  }
</style>
