<script lang="ts">
  // Login form (feature 008; restyled feature 016). Captures username/password and calls onSubmit; the
  // parent validates lazily (a repeated 401 ⇒ invalid) and passes `error` back to display. Borderless:
  // callers wrap it in a panel (the full-screen vault card, or the in-context prompt).
  let {
    onSubmit,
    onCancel,
    error = '',
    title = 'Log in',
  }: {
    onSubmit: (username: string, password: string) => void;
    onCancel?: () => void;
    error?: string;
    title?: string;
  } = $props();

  let username = $state('');
  let password = $state('');

  function submit(event: SubmitEvent) {
    event.preventDefault();
    onSubmit(username, password);
  }
</script>

<form class="login" onsubmit={submit} data-testid="login-form">
  <h3>{title}</h3>
  {#if error}
    <p class="error" data-testid="login-error">{error}</p>
  {/if}
  <label>
    Username
    <input
      class="rq-input"
      data-testid="login-username"
      bind:value={username}
      placeholder="admin"
      autocomplete="username"
    />
  </label>
  <label>
    Password
    <input
      class="rq-input"
      data-testid="login-password"
      type="password"
      bind:value={password}
      placeholder="••••••••"
      autocomplete="current-password"
    />
  </label>
  <div class="actions">
    <button type="submit" class="rq-btn rq-btn-primary submit" data-testid="login-submit">{title}</button>
    {#if onCancel}
      <button type="button" class="rq-btn" onclick={onCancel}>Cancel</button>
    {/if}
  </div>
</form>

<style>
  .login {
    padding: 26px 30px;
    display: grid;
    gap: 14px;
  }
  h3 {
    margin: 0;
    font-family: var(--rq-serif);
    font-size: 14px;
    letter-spacing: 1px;
    color: var(--rq-gold);
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
  .actions {
    display: flex;
    gap: 8px;
    margin-top: 4px;
  }
  .submit {
    flex: 1;
  }
  .error {
    margin: 0;
    color: var(--rq-danger);
    font-size: 12px;
  }
</style>
