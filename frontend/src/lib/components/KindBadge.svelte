<script lang="ts">
  // A small badge showing a repository's kind (hosted / proxy / group), feature 006/008; restyled 016.
  let { kind }: { kind: string } = $props();
  let label = $derived(kind.toLowerCase());
</script>

<span class="badge {label}" data-testid="repo-kind">{label}</span>

<style>
  .badge {
    font-family: var(--rq-serif);
    font-size: 0.7rem;
    text-transform: uppercase;
    letter-spacing: 0.08em;
    padding: 0.1rem 0.5rem;
    border-radius: 999px;
    border: 1px solid currentColor;
    color: var(--rq-muted);
  }
  .hosted {
    color: var(--rq-gold);
  }
  .proxy {
    color: var(--rq-gold-soft);
  }
  .group {
    color: var(--rq-muted);
  }
</style>
