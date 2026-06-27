// Typed client for the Relikquary browse/manage API (see specs/005-frontend-ui/contracts/browse-api.md).

export interface RepositorySummary {
  name: string;
  type: string;
}

export interface ListingEntry {
  name: string;
  kind: 'folder' | 'file';
  size?: number;
  lastModified?: string;
}

export interface ContentsResponse {
  repository: string;
  path: string;
  entries: ListingEntry[];
}

export interface FileDetails {
  repository: string;
  path: string;
  size: number;
  lastModified?: string;
  checksums: Record<string, string>;
  downloadUrl: string;
}

async function getJson<T>(url: string): Promise<T> {
  const res = await fetch(url);
  if (!res.ok) throw new Error(`${res.status}`);
  return (await res.json()) as T;
}

export function listRepositories(): Promise<RepositorySummary[]> {
  return getJson('/api/repositories');
}

export function listContents(repo: string, path: string): Promise<ContentsResponse> {
  const suffix = path ? `/${path}` : '';
  return getJson(`/api/repositories/${repo}/contents${suffix}`);
}

export function fileDetails(repo: string, path: string): Promise<FileDetails> {
  return getJson(`/api/repositories/${repo}/file/${path}`);
}

/** Deletes a file or folder prefix. Returns false (without throwing) when credentials are required. */
export async function deleteEntry(repo: string, path: string, auth?: string): Promise<boolean> {
  const headers: Record<string, string> = {};
  if (auth) headers['Authorization'] = auth;
  const res = await fetch(`/api/repositories/${repo}/${path}`, { method: 'DELETE', headers });
  if (res.status === 401) return false;
  if (!res.ok && res.status !== 404) throw new Error(`${res.status}`);
  return true;
}

export function basicAuth(user: string, password: string): string {
  return 'Basic ' + btoa(`${user}:${password}`);
}
