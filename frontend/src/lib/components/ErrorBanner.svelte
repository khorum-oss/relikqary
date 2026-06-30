<script lang="ts">
  // A clear inline error message (feature 008). Used for 403 (forbidden), 404 (not found), 409
  // (conflict) and generic failures — 401 is handled by the login prompt, not this banner.
  let { message }: { message: string } = $props();
</script>

<p class="error" data-testid="error">{message}</p>

<style>
  .error {
    color: var(--rq-danger);
    background: var(--rq-danger-bg);
    border: 1px solid var(--rq-danger);
    border-radius: var(--rq-radius);
    padding: 0.7rem 0.9rem;
    font-size: 12px;
  }
</style>
