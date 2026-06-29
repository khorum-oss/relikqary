<script lang="ts">
  import type { ModuleMetadata } from '$lib/api';

  // Renders a Gradle module's variants from the backend-parsed metadata (feature 011): each variant's
  // attributes, capabilities, dependencies, and files. Degrades gracefully when the metadata is unparseable.
  let { module }: { module: ModuleMetadata } = $props();

  function gav(o: { group?: string; name?: string; module?: string; version?: string }): string {
    return [o.group, o.name ?? o.module, o.version].filter(Boolean).join(':');
  }
</script>

{#if !module.parseable}
  <p class="muted" data-testid="module-unparseable">This module metadata could not be parsed.</p>
{:else}
  <section class="module" data-testid="module-detail">
    {#each module.variants as variant (variant.name)}
      <div class="variant" data-testid="variant">
        <h4>{variant.name}</h4>
        {#if Object.keys(variant.attributes).length}
          <dl class="attrs">
            {#each Object.entries(variant.attributes) as [key, value]}
              <dt>{key}</dt>
              <dd>{value}</dd>
            {/each}
          </dl>
        {/if}
        {#if variant.capabilities.length}
          <p><strong>Capabilities:</strong> {variant.capabilities.map(gav).join(', ')}</p>
        {/if}
        {#if variant.dependencies.length}
          <div>
            <strong>Dependencies:</strong>
            <ul>
              {#each variant.dependencies as dep}<li>{gav(dep)}</li>{/each}
            </ul>
          </div>
        {/if}
        {#if variant.files.length}
          <div>
            <strong>Files:</strong>
            <ul>
              {#each variant.files as file}<li>{file.name}</li>{/each}
            </ul>
          </div>
        {/if}
      </div>
    {/each}
  </section>
{/if}

<style>
  .module {
    margin: 0.6rem 0;
  }
  .variant {
    border: 1px solid #e2e8f0;
    border-radius: 6px;
    padding: 0.5rem 0.7rem;
    margin-bottom: 0.5rem;
  }
  .variant h4 {
    margin: 0 0 0.3rem;
  }
  .attrs {
    display: grid;
    grid-template-columns: auto 1fr;
    gap: 0.1rem 0.6rem;
    font-size: 0.85rem;
    margin: 0.2rem 0;
  }
  .attrs dt {
    color: #4a5568;
  }
  ul {
    margin: 0.2rem 0;
    padding-left: 1.2rem;
  }
  .muted {
    color: #718096;
  }
</style>
