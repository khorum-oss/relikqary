<script lang="ts">
  // The Relikquary sigil (feature 016): a warded diamond vault mark, drawn from the design mockup.
  let { size = 24 }: { size?: number } = $props();
</script>

<svg width={size} height={size} viewBox="0 0 56 56" fill="none" aria-hidden="true">
  <circle cx="28" cy="28" r="25" fill="#0e0a04" stroke="#C9A227" stroke-width="2.5" />
  <circle cx="28" cy="28" r="20" fill="none" stroke="#C9A227" stroke-width="0.7" opacity="0.4" />
  <path d="M28,9 L43,28 L28,47 L13,28 Z" fill="none" stroke="#C9A227" stroke-width="2" />
  <line x1="13" y1="28" x2="43" y2="28" stroke="#C9A227" stroke-width="0.8" opacity="0.4" />
  <path d="M28,22 L34,28 L28,34 L22,28 Z" fill="#C9A227" />
</svg>
