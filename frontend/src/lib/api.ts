// Typed client for the Relikquary browse/manage API + Maven download/upload (features 005–008).
// Credentials from the login session (auth.svelte.ts) are attached to every request; failures throw
// ApiError carrying the HTTP status so callers can drive 401→login / 403→forbidden / 409→conflict UX.
import { authHeader } from './auth.svelte';

/** Thrown on a non-OK response; `status` is the HTTP status code. */
export class ApiError extends Error {
  constructor(public readonly status: number, message?: string) {
    super(message ?? `HTTP ${status}`);
    this.name = 'ApiError';
  }
}

export interface RepositorySummary {
  name: string;
  /** Hosted acceptance/mutability policy (release/snapshot/mixed). */
  type: string;
  /** How the repository resolves: HOSTED, PROXY, or GROUP (feature 006). */
  kind: string;
}

export interface ListingEntry {
  name: string;
  kind: 'folder' | 'file';
  size?: number;
  lastModified?: string;
}

/** An artifact coordinate derived from a version-directory browse path (feature 011). */
export interface Coordinate {
  group: string;
  artifact: string;
  version: string;
}

/** A reference to a recognized Gradle Module Metadata file for the browsed coordinate (feature 011). */
export interface ModuleRef {
  path: string;
}

export interface ContentsResponse {
  repository: string;
  path: string;
  entries: ListingEntry[];
  /** Present when the path is a coordinate's version directory (feature 011). */
  coordinate?: Coordinate | null;
  /** Present when that directory contains a recognized `.module` (feature 011). */
  module?: ModuleRef | null;
}

export interface Capability {
  group?: string;
  name?: string;
  version?: string;
}

export interface Dependency {
  group?: string;
  module?: string;
  version?: string;
}

export interface ModuleFile {
  name?: string;
  url?: string;
  size?: number;
  sha256?: string;
}

export interface Variant {
  name: string;
  attributes: Record<string, string>;
  capabilities: Capability[];
  dependencies: Dependency[];
  files: ModuleFile[];
}

/** Parsed Gradle Module Metadata; `parseable` is false when the `.module` could not be parsed. */
export interface ModuleMetadata {
  repository: string;
  path: string;
  parseable: boolean;
  component?: { group?: string; module?: string; version?: string } | null;
  variants: Variant[];
}

export interface FileDetails {
  repository: string;
  path: string;
  size: number;
  lastModified?: string;
  checksums: Record<string, string>;
  downloadUrl: string;
}

/** Merge the session Authorization header into a request init. The X-Requested-With marker tells the
 * backend to omit the Basic-auth challenge on 401, so the browser shows our login form instead of its
 * native auth dialog. */
function authed(init: RequestInit = {}): RequestInit {
  const headers = new Headers(init.headers);
  headers.set('X-Requested-With', 'XMLHttpRequest');
  const header = authHeader();
  if (header) headers.set('Authorization', header);
  return { ...init, headers };
}

async function getJson<T>(url: string): Promise<T> {
  const res = await fetch(url, authed());
  if (!res.ok) throw new ApiError(res.status);
  return (await res.json()) as T;
}

/** Dashboard summary figures (feature 016, Phase 2). */
export interface Stats {
  repositories: number;
  artifacts: number;
  storageBytes: number;
}

/** One aggregated `group:artifact` row in the cross-repo catalog (feature 016, Phase 2). */
export interface CatalogEntry {
  repository: string;
  group: string;
  artifact: string;
  latestVersion: string;
  versionCount: number;
  sizeBytes: number;
}

export interface CatalogResponse {
  entries: CatalogEntry[];
  page: number;
  pageSize: number;
  total: number;
  truncated: boolean;
}

/** An API token as listed — never carries the secret (feature 016, Phase 3). */
export interface TokenSummary {
  id: string;
  name: string;
  owner: string;
  scope: 'read' | 'publish';
  createdAt: string;
  lastUsedAt?: string | null;
  revoked: boolean;
}

/** The one-time response to creating a token — `secret` is shown only here. */
export interface CreatedToken {
  id: string;
  name: string;
  scope: string;
  createdAt: string;
  secret: string;
}

/** A managed user account (feature 016, Phase 3). Never carries the password. */
export interface UserSummary {
  id: string;
  username: string;
  email?: string | null;
  roles: string[];
  lastActiveAt?: string | null;
  createdAt: string;
}

export function listRepositories(): Promise<RepositorySummary[]> {
  return getJson('/api/repositories');
}

/** Lists managed users (admin; requires the PUBLISH role). */
export function listUsers(): Promise<UserSummary[]> {
  return getJson('/api/admin/users');
}

/** Creates a managed user. `roles` are role names (e.g. ["PUBLISH"]). Throws [ApiError] on failure. */
export async function createUser(
  username: string,
  email: string,
  password: string,
  roles: string[],
): Promise<void> {
  const res = await fetch(
    '/api/admin/users',
    authed({
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, email: email || null, password, roles }),
    }),
  );
  if (!res.ok) throw new ApiError(res.status);
}

/** Deletes a managed user by id. Throws [ApiError] on failure. */
export async function deleteUser(id: string): Promise<void> {
  const res = await fetch(`/api/admin/users/${id}`, authed({ method: 'DELETE' }));
  if (!res.ok) throw new ApiError(res.status);
}

/** Lists all API tokens (admin; requires the PUBLISH role). */
export function listTokens(): Promise<TokenSummary[]> {
  return getJson('/api/admin/tokens');
}

/** Creates a token; the response's `secret` is returned only once. Throws [ApiError] on failure. */
export async function createToken(name: string, scope: 'read' | 'publish'): Promise<CreatedToken> {
  const res = await fetch(
    '/api/admin/tokens',
    authed({ method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ name, scope }) }),
  );
  if (!res.ok) throw new ApiError(res.status);
  return (await res.json()) as CreatedToken;
}

/** Revokes a token by id. Throws [ApiError] on failure. */
export async function revokeToken(id: string): Promise<void> {
  const res = await fetch(`/api/admin/tokens/${id}`, authed({ method: 'DELETE' }));
  if (!res.ok) throw new ApiError(res.status);
}

/** Read-only Dashboard stats (repository count, total objects, storage bytes). */
export function getStats(): Promise<Stats> {
  return getJson('/api/stats');
}

/** Aggregated artifact catalog; `pageSize` is capped server-side. */
export function getCatalog(pageSize = 500): Promise<CatalogResponse> {
  return getJson(`/api/catalog?pageSize=${pageSize}`);
}

export function listContents(repo: string, path: string): Promise<ContentsResponse> {
  const suffix = path ? `/${path}` : '';
  return getJson(`/api/repositories/${repo}/contents${suffix}`);
}

export function fileDetails(repo: string, path: string): Promise<FileDetails> {
  return getJson(`/api/repositories/${repo}/file/${path}`);
}

/** Fetches parsed Gradle Module Metadata for a recognized `.module` path (feature 011). */
export function moduleMetadata(repo: string, path: string): Promise<ModuleMetadata> {
  return getJson(`/api/repositories/${repo}/module/${path}`);
}

/** Deletes a file or folder prefix using the session credentials. Throws [ApiError] on failure. */
export async function deleteEntry(repo: string, path: string): Promise<void> {
  const res = await fetch(`/api/repositories/${repo}/${path}`, authed({ method: 'DELETE' }));
  if (!res.ok) throw new ApiError(res.status);
}

/** Downloads an artifact's bytes with the session credentials (so private repos work). */
export async function download(repo: string, path: string): Promise<Blob> {
  const res = await fetch(`/${repo}/${path}`, authed());
  if (!res.ok) throw new ApiError(res.status);
  return res.blob();
}

/** Uploads a file to a hosted repo path (Maven PUT). Returns the HTTP status for the caller to branch. */
export async function upload(repo: string, path: string, file: File): Promise<number> {
  const res = await fetch(`/${repo}/${path}`, authed({ method: 'PUT', body: file }));
  return res.status;
}

export function basicAuth(user: string, password: string): string {
  return 'Basic ' + btoa(`${user}:${password}`);
}
