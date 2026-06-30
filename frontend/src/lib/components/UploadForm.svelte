<script lang="ts">
  // Upload a single file to a hosted repo path (feature 008; restyled + drop zone in 016). The target
  // path is prefilled from the current folder and confirmable. Files can be dropped onto the zone or
  // picked; submitting delegates to onUpload, which performs the PUT and reports an outcome message.
  let {
    repo,
    basePath,
    onUpload,
  }: {
    repo: string;
    basePath: string;
    onUpload: (path: string, file: File) => Promise<string | null>;
  } = $props();

  let file = $state<File | null>(null);
  let targetPath = $state('');
  let uploading = $state(false);
  let message = $state('');
  let dragging = $state(false);
  let fileInput: HTMLInputElement;

  function accept(picked: File | null) {
    file = picked;
    if (picked) targetPath = basePath ? `${basePath}/${picked.name}` : picked.name;
  }

  function onPick(event: Event) {
    accept((event.target as HTMLInputElement).files?.[0] ?? null);
  }

  function onDrop(event: DragEvent) {
    event.preventDefault();
    dragging = false;
    accept(event.dataTransfer?.files?.[0] ?? null);
  }

  async function submit(event: SubmitEvent) {
    event.preventDefault();
    if (!file || !targetPath) return;
    uploading = true;
    message = '';
    const error = await onUpload(targetPath, file);
    uploading = false;
    message = error ?? '';
    if (!error) {
      file = null;
      targetPath = '';
    }
  }
</script>

<form class="upload rq-panel" onsubmit={submit} data-testid="upload-form">
  <div class="head">
    <div class="title">Manual Upload to {repo}</div>
    <div class="hint">Drop a JAR, AAR, POM, or other artifact — or click to browse.</div>
  </div>

  <button
    type="button"
    class="dropzone"
    class:dragging
    onclick={() => fileInput.click()}
    ondragover={(e) => {
      e.preventDefault();
      dragging = true;
    }}
    ondragleave={() => (dragging = false)}
    ondrop={onDrop}
  >
    {#if file}
      <span class="picked">{file.name}</span>
    {:else}
      <span class="drop-title">Drop files here</span>
      <span class="drop-sub">or click to browse</span>
    {/if}
  </button>

  <input
    class="hidden-file"
    bind:this={fileInput}
    type="file"
    onchange={onPick}
    data-testid="upload-file"
  />

  <label>
    Target path
    <input class="rq-input" bind:value={targetPath} data-testid="upload-path" placeholder="group/artifact/version/file" />
  </label>

  <button type="submit" class="rq-btn rq-btn-primary submit" disabled={!file || !targetPath || uploading} data-testid="upload-submit">
    {uploading ? 'Uploading…' : 'Upload'}
  </button>

  {#if message}
    <p class="error" data-testid="upload-error">{message}</p>
  {/if}
</form>

<style>
  .upload {
    padding: 18px;
    margin-top: 1.25rem;
    display: grid;
    gap: 14px;
    max-width: 30rem;
  }
  .title {
    font-family: var(--rq-serif);
    font-size: 13px;
    color: var(--rq-gold);
  }
  .hint {
    font-size: 11px;
    color: var(--rq-muted);
    margin-top: 2px;
  }
  .dropzone {
    border: 1px dashed var(--rq-border-strong);
    border-radius: var(--rq-radius);
    background: var(--rq-inset);
    padding: 32px;
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 4px;
    cursor: pointer;
    width: 100%;
    font: inherit;
  }
  .dropzone.dragging {
    border-color: var(--rq-gold);
    background: var(--rq-row-hover);
  }
  .drop-title {
    font-family: var(--rq-serif);
    font-size: 13px;
    letter-spacing: 1px;
    color: var(--rq-muted);
  }
  .drop-sub,
  .picked {
    font-size: 11px;
    color: var(--rq-dim);
  }
  .picked {
    font-family: var(--rq-mono);
    color: var(--rq-gold);
    font-size: 12px;
  }
  .hidden-file {
    display: none;
  }
  label {
    display: grid;
    gap: 6px;
    font-family: var(--rq-serif);
    font-size: 10px;
    letter-spacing: 1.5px;
    text-transform: uppercase;
    color: var(--rq-dim);
  }
  .submit {
    justify-self: start;
  }
  .submit:disabled {
    opacity: 0.45;
    cursor: default;
  }
  .error {
    color: var(--rq-danger);
    margin: 0;
    font-size: 12px;
  }
</style>
