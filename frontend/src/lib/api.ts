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
