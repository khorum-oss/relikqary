<script lang="ts">
  import type { Coordinate } from '$lib/api';

  // Copy-paste "consume" snippets for a coordinate (feature 011): Gradle Kotlin DSL, Gradle Groovy DSL,
  // and Maven XML, each pointing at this repository's URL.
  let { coordinate, repoUrl }: { coordinate: Coordinate; repoUrl: string } = $props();

  let tab = $state<'kotlin' | 'groovy' | 'maven'>('kotlin');
  let copied = $state(false);

  let ga = $derived(`${coordinate.group}:${coordinate.artifact}:${coordinate.version}`);
  let kotlin = $derived(
    `repositories {\n    maven { url = uri("${repoUrl}") }\n}\ndependencies {\n    implementation("${ga}")\n}`,
  );
  let groovy = $derived(
    `repositories {\n    maven { url '${repoUrl}' }\n}\ndependencies {\n    implementation '${ga}'\n}`,
  );
  let maven = $derived(
    `<repositories>\n  <repository>\n    <id>relikquary</id>\n    <url>${repoUrl}</url>\n  </repository>\n</repositories>\n` +
      `<dependency>\n  <groupId>${coordinate.group}</groupId>\n  <artifactId>${coordinate.artifact}</artifactId>\n` +
      `  <version>${coordinate.version}</version>\n</dependency>`,
  );
  let current = $derived(tab === 'kotlin' ? kotlin : tab === 'groovy' ? groovy : maven);

  async function copy() {
    await navigator.clipboard?.writeText(current);
    copied = true;
    setTimeout(() => (copied = false), 1200);
  }
</script>

<section class="snippets" data-testid="consume-snippets">
  <div class="tabs">
    <button class:active={tab === 'kotlin'} onclick={() => (tab = 'kotlin')} data-testid="snippet-kotlin">
      Gradle (Kotlin)
    </button>
    <button class:active={tab === 'groovy'} onclick={() => (tab = 'groovy')} data-testid="snippet-groovy">
      Gradle (Groovy)
    </button>
    <button class:active={tab === 'maven'} onclick={() => (tab = 'maven')} data-testid="snippet-maven">
      Maven
    </button>
  </div>
  <pre data-testid="snippet-body">{current}</pre>
  <button class="copy" onclick={copy} data-testid="snippet-copy">{copied ? 'Copied' : 'Copy'}</button>
</section>

<style>
  .snippets {
    border: 1px solid var(--rq-border);
    border-radius: var(--rq-radius);
    background: var(--rq-panel);
    padding: 0.7rem;
    margin: 0.6rem 0;
    position: relative;
  }
  .tabs {
    display: flex;
    gap: 0.3rem;
    margin-bottom: 0.5rem;
  }
  .tabs button {
    font-family: var(--rq-serif);
    font-size: 11px;
    letter-spacing: 1px;
    padding: 0.25rem 0.7rem;
    border: 1px solid var(--rq-border);
    border-radius: var(--rq-radius);
    background: var(--rq-inset);
    color: var(--rq-dim);
    cursor: pointer;
  }
  .tabs button.active {
    background: var(--rq-panel);
    color: var(--rq-gold);
    border-color: var(--rq-gold);
  }
  pre {
    background: var(--rq-inset);
    color: var(--rq-text);
    border: 1px solid var(--rq-border-subtle);
    padding: 0.8rem;
    border-radius: var(--rq-radius);
    overflow-x: auto;
    font-family: var(--rq-mono);
    font-size: 12px;
    line-height: 1.6;
    margin: 0;
  }
  .copy {
    position: absolute;
    top: 0.7rem;
    right: 0.7rem;
    font-family: var(--rq-serif);
    font-size: 10px;
    letter-spacing: 1px;
    padding: 0.25rem 0.7rem;
    border: 1px solid var(--rq-border-strong);
    border-radius: var(--rq-radius);
    background: var(--rq-panel);
    color: var(--rq-muted);
    cursor: pointer;
  }
  .copy:hover {
    color: var(--rq-gold);
  }
</style>
