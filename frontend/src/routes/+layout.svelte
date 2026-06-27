<script lang="ts">
  let { children } = $props();
</script>

<header>
  <a href="/" class="brand">Relikquary</a>
</header>
<main>
  {@render children()}
</main>

<style>
  :global(body) {
    margin: 0;
    font-family: system-ui, sans-serif;
    color: #1a202c;
    background: #f7fafc;
  }
  header {
    background: #2d3748;
    padding: 0.75rem 1.25rem;
  }
  .brand {
    color: #fff;
    font-weight: 700;
    text-decoration: none;
    font-size: 1.1rem;
  }
  main {
    max-width: 900px;
    margin: 1.5rem auto;
    padding: 0 1.25rem;
  }
  :global(a) {
    color: #3182ce;
  }
</style>
