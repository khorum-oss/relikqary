<script lang="ts">
  import Sidebar from './Sidebar.svelte';
  import Topbar from './Topbar.svelte';

  // The application frame (feature 016): navigation rail + section topbar + scrolling content region.
  // The page content is provided as the default snippet.
  let {
    pathname,
    title,
    user,
    onSignIn,
    onSignOut,
    children,
  }: {
    pathname: string;
    title: string;
    user: string | null;
    onSignIn: () => void;
    onSignOut: () => void;
    children: import('svelte').Snippet;
  } = $props();
</script>

<div class="shell">
  <Sidebar {pathname} {user} {onSignIn} {onSignOut} />
  <div class="main">
    <Topbar {title} showSearch={pathname === '/'} />
    <div class="content">
      {@render children()}
    </div>
  </div>
</div>

<style>
  .shell {
    height: 100vh;
    display: flex;
    overflow: hidden;
    background: var(--rq-shell);
  }
  .main {
    flex: 1;
    min-width: 0;
    display: flex;
    flex-direction: column;
    overflow: hidden;
  }
  .content {
    flex: 1;
    overflow-y: auto;
    padding: 22px;
  }
</style>
