<script lang="ts">
  import { page } from '$app/stores';
  import {
    listRepositories,
    listContents,
    fileDetails,
    deleteEntry,
    download,
    upload,
    moduleMetadata,
    ApiError,
    type ContentsResponse,
    type FileDetails,
    type ModuleMetadata,
  } from '$lib/api';
  import { login } from '$lib/auth.svelte';
  import Breadcrumbs from '$lib/components/Breadcrumbs.svelte';
  import FileListing from '$lib/components/FileListing.svelte';
  import FileDetailsPanel from '$lib/components/FileDetailsPanel.svelte';
  import ConsumeSnippets from '$lib/components/ConsumeSnippets.svelte';
  import GradleModuleBadge from '$lib/components/GradleModuleBadge.svelte';
  import ModuleDetail from '$lib/components/ModuleDetail.svelte';
  import EmptyState from '$lib/components/EmptyState.svelte';
  import ErrorBanner from '$lib/components/ErrorBanner.svelte';
  import LoginForm from '$lib/components/LoginForm.svelte';
  import UploadForm from '$lib/components/UploadForm.svelte';

  let repo = $derived($page.params.repo ?? '');
  let path = $derived($page.params.path ?? '');

  let kind = $state('HOSTED');
  let contents = $state<ContentsResponse | null>(null);
  let selected = $state<FileDetails | null>(null);
  let moduleMeta = $state<ModuleMetadata | null>(null);
  let repoUrl = $derived(typeof window !== 'undefined' ? `${window.location.origin}/${repo}` : `/${repo}`);
  let error = $state('');
  let forbidden = $state('');
  let needLogin = $state(false);
  let loginError = $state('');
  let showUpload = $state(false);
  let pendingRetry: (() => Promise<void>) | null = null;

  function join(base: string, name: string): string {
    return base ? `${base}/${name}` : name;
  }

  /** Centralised error handling: 401 prompts login (with a retry), 403 is forbidden, others are shown. */
  function handle(e: unknown, retry: () => Promise<void>) {
    if (e instanceof ApiError) {
      if (e.status === 401) {
        pendingRetry = retry;
        needLogin = true;
      } else if (e.status === 403) {
        forbidden = 'You are not permitted to access this repository.';
      } else if (e.status === 404) {
        error = 'Not found';
      } else {
        error = `Failed (${e.status})`;
      }
    } else {
      error = `Failed (${e})`;
    }
  }

  async function load(r: string, p: string) {
    error = '';
    forbidden = '';
    selected = null;
    moduleMeta = null;
    showUpload = false;
    try {
      const repos = await listRepositories();
      kind = repos.find((x) => x.name === r)?.kind ?? 'HOSTED';
      if (kind === 'GROUP') {
        contents = null;
        return;
      }
      contents = await listContents(r, p);
    } catch (e) {
      contents = null;
      handle(e, () => load(r, p));
    }
  }

  $effect(() => {
    load(repo, path);
  });

  async function openFile(name: string) {
    try {
      selected = await fileDetails(repo, join(path, name));
    } catch (e) {
      handle(e, () => openFile(name));
    }
  }

  async function openModule() {
    const ref = contents?.module;
    if (!ref) return;
    try {
      moduleMeta = await moduleMetadata(repo, ref.path);
    } catch (e) {
      handle(e, openModule);
    }
  }

  async function remove(name: string) {
    if (!confirm(`Delete ${name}?`)) return;
    const target = join(path, name);
    try {
      await deleteEntry(repo, target);
      await load(repo, path);
    } catch (e) {
      handle(e, () => remove(name));
    }
  }

  async function doDownload() {
    if (!selected) return;
    const current = selected;
    try {
      const blob = await download(current.repository, current.path);
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = current.path.split('/').pop() ?? 'download';
      a.click();
      URL.revokeObjectURL(url);
    } catch (e) {
      handle(e, doDownload);
    }
  }

  /** Returns an error message on failure, or null on success (the form clears + listing refreshes). */
  async function doUpload(targetPath: string, file: File): Promise<string | null> {
    const status = await upload(repo, targetPath, file);
    if (status === 200 || status === 201) {
      await load(repo, path);
      showUpload = false;
      return null;
    }
    if (status === 401) {
      needLogin = true;
      return 'Log in to upload.';
    }
    if (status === 403) return 'You are not permitted to publish here.';
    if (status === 409) return 'Conflict: this release already exists and is immutable.';
    if (status === 405) return 'This repository does not accept uploads.';
    return `Upload failed (${status}).`;
  }

  async function onLogin(username: string, password: string) {
    login(username, password);
    needLogin = false;
    loginError = '';
    const retry = pendingRetry;
    pendingRetry = null;
    if (!retry) return;
    try {
      await retry();
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) {
        needLogin = true;
        loginError = 'Invalid credentials.';
      } else {
        handle(e, retry);
      }
    }
  }
</script>

<Breadcrumbs {repo} {path} />

{#if needLogin}
  <div class="login-inline rq-panel">
    <LoginForm onSubmit={onLogin} onCancel={() => (needLogin = false)} error={loginError}
      title="Log in to continue" />
  </div>
{/if}

{#if forbidden}
  <ErrorBanner message={forbidden} />
{/if}
{#if error}
  <ErrorBanner message={error} />
{/if}

{#if kind === 'GROUP'}
  <EmptyState testid="group-aggregate"
    message={`'${repo}' is a group repository — an aggregate of its member repositories. Browse its members directly.`} />
{:else if contents}
  {#if kind === 'HOSTED'}
    <div class="toolbar">
      <button data-testid="upload-toggle" onclick={() => (showUpload = !showUpload)}>
        {showUpload ? 'Close upload' : 'Upload…'}
      </button>
    </div>
    {#if showUpload}
      <UploadForm {repo} basePath={path} onUpload={doUpload} />
    {/if}
  {/if}

  <FileListing {repo} {path} entries={contents.entries} onOpen={openFile} onDelete={remove} />

  {#if contents.coordinate}
    <section class="coordinate" data-testid="coordinate-panel">
      <div class="coordinate-head">
        <strong>{contents.coordinate.group}:{contents.coordinate.artifact}:{contents.coordinate.version}</strong>
        {#if contents.module}<GradleModuleBadge />{/if}
      </div>
      <ConsumeSnippets coordinate={contents.coordinate} {repoUrl} />
      {#if contents.module}
        {#if moduleMeta}
          <ModuleDetail module={moduleMeta} />
        {:else}
          <button class="view-module" onclick={openModule} data-testid="view-module">View module metadata</button>
        {/if}
      {/if}
    </section>
  {/if}
{/if}

{#if selected}
  <FileDetailsPanel details={selected} onDownload={doDownload} />
{/if}

<style>
  .login-inline {
    max-width: 24rem;
    margin-bottom: 1rem;
  }
  .toolbar {
    display: flex;
    justify-content: flex-end;
    margin-bottom: 0.6rem;
  }
  .toolbar button {
    background: var(--rq-panel);
    color: var(--rq-text);
    border: 1px solid var(--rq-border-strong);
    border-radius: var(--rq-radius);
    padding: 0.4rem 0.9rem;
    cursor: pointer;
    font-family: var(--rq-serif);
    font-size: 11px;
    letter-spacing: 1px;
    text-transform: uppercase;
  }
  .toolbar button:hover {
    opacity: 0.82;
  }
  .coordinate {
    margin-top: 1rem;
    border-top: 1px solid var(--rq-border);
    padding-top: 0.9rem;
  }
  .coordinate-head {
    display: flex;
    align-items: center;
    gap: 0.6rem;
    margin-bottom: 0.5rem;
    font-family: var(--rq-mono);
    color: var(--rq-gold);
  }
  .view-module {
    font-family: var(--rq-serif);
    font-size: 11px;
    letter-spacing: 1px;
    padding: 0.3rem 0.8rem;
    border: 1px solid var(--rq-border-strong);
    border-radius: var(--rq-radius);
    background: var(--rq-panel);
    color: var(--rq-muted);
    cursor: pointer;
  }
  .view-module:hover {
    color: var(--rq-gold);
  }
</style>
