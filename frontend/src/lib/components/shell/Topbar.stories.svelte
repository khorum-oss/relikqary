<script module lang="ts">
  import { defineMeta } from '@storybook/addon-svelte-csf';
  import Topbar from './Topbar.svelte';

  const { Story } = defineMeta({
    title: 'Shell/Topbar',
    component: Topbar,
  });
</script>

<Story name="Repositories" args={{ title: 'Repositories' }} />
<Story name="Publish" args={{ title: 'Publish' }} />
