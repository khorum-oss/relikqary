// Topbar artifact search (feature 016, Phase 2). A tiny reactive module store so the Topbar's input and
// the Repositories catalog can share one query without prop-drilling through the shell.
let query = $state('');

export function searchQuery(): string {
  return query;
}

export function setSearchQuery(value: string): void {
  query = value;
}
