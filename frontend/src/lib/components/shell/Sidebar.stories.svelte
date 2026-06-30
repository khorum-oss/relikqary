<script module lang="ts">
  import { defineMeta } from '@storybook/addon-svelte-csf';
  import Sidebar from './Sidebar.svelte';

  const { Story } = defineMeta({
    title: 'Shell/Sidebar',
    component: Sidebar,
    args: {
      onSignIn: () => {},
      onSignOut: () => {},
    },
  });
</script>

<Story name="Anonymous (Repositories active)" args={{ pathname: '/', user: null }} />
<Story name="Signed in (Publish active)" args={{ pathname: '/publish', user: 'admin' }} />
<Story name="Settings active" args={{ pathname: '/settings', user: 'alice' }} />
