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
    border: 1px solid #e2e8f0;
    border-radius: 6px;
    padding: 0.6rem;
    margin: 0.6rem 0;
  }
  .tabs {
    display: flex;
    gap: 0.3rem;
    margin-bottom: 0.4rem;
  }
  .tabs button {
    font: inherit;
    font-size: 0.8rem;
    padding: 0.2rem 0.6rem;
    border: 1px solid #cbd5e0;
    border-radius: 4px;
    background: #f7fafc;
    cursor: pointer;
  }
  .tabs button.active {
    background: #3182ce;
    color: #fff;
    border-color: #3182ce;
  }
  pre {
    background: #1a202c;
    color: #e2e8f0;
    padding: 0.6rem;
    border-radius: 4px;
    overflow-x: auto;
    font-size: 0.8rem;
  }
  .copy {
    font: inherit;
    font-size: 0.8rem;
    padding: 0.2rem 0.6rem;
    border: none;
    border-radius: 4px;
    background: #3182ce;
    color: #fff;
    cursor: pointer;
  }
</style>
