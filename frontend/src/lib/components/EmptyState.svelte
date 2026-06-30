<script lang="ts">
  // An informational placeholder: an empty listing, or a group repo's aggregate note (feature 008).
  let { message, testid = 'empty-state' }: { message: string; testid?: string } = $props();
</script>

<p class="empty-state" data-testid={testid}>{message}</p>

<style>
  .empty-state {
    color: var(--rq-muted);
    background: var(--rq-panel);
    border: 1px dashed var(--rq-border-strong);
    border-radius: var(--rq-radius);
    padding: 1.5rem;
    text-align: center;
    font-size: 12px;
  }
</style>
