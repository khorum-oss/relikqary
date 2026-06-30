<script lang="ts">
  import Sigil from './shell/Sigil.svelte';
  // A themed "coming in a later phase" panel for sections not yet wired to data (feature 016, Phase 1).
  let { title, note }: { title: string; note: string } = $props();
</script>

<div class="placeholder rq-panel" data-testid="placeholder">
  <Sigil size={40} />
  <div class="title">{title}</div>
  <p class="note">{note}</p>
</div>

<style>
  .placeholder {
    border-top: 2px solid var(--rq-border-strong);
    padding: 48px 32px;
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 10px;
    text-align: center;
  }
  .title {
    font-family: var(--rq-serif);
    font-size: 16px;
    letter-spacing: 1px;
    color: var(--rq-gold);
  }
  .note {
    margin: 0;
    max-width: 32rem;
    color: var(--rq-muted);
    font-size: 12px;
    line-height: 1.6;
  }
</style>
