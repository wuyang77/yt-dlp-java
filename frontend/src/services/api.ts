import type { CookieMode, DownloadTask, FormatListResponse, StartDownloadRequest } from '@/types/api';

async function readJson<T>(response: Response): Promise<T> {
  const body: unknown = await response.json().catch(() => ({}));
  if (!response.ok) {
    const error = body as { message?: string; error?: string };
    throw new Error(error.message || error.error || `请求失败（HTTP ${response.status}）`);
  }
  return body as T;
}

export async function checkHealth(): Promise<boolean> {
  try {
    return (await fetch('/api/health')).ok;
  } catch {
    return false;
  }
}

export async function fetchFormats(url: string, cookieMode: CookieMode): Promise<FormatListResponse> {
  const params = new URLSearchParams({ url, cookieMode });
  const response = await fetch(`/api/formats?${params}`);
  return readJson<FormatListResponse>(response);
}

export async function startDownload(request: StartDownloadRequest): Promise<DownloadTask> {
  const response = await fetch('/api/download/async', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(request),
  });
  return readJson<DownloadTask>(response);
}

export async function fetchDownloadTask(taskId: string): Promise<DownloadTask> {
  const response = await fetch(`/api/download/tasks/${encodeURIComponent(taskId)}`);
  return readJson<DownloadTask>(response);
}

export async function pauseDownload(taskId: string): Promise<DownloadTask> {
  const response = await fetch(`/api/download/tasks/${encodeURIComponent(taskId)}/pause`, { method: 'POST' });
  return readJson<DownloadTask>(response);
}

export async function resumeDownload(taskId: string): Promise<DownloadTask> {
  const response = await fetch(`/api/download/tasks/${encodeURIComponent(taskId)}/resume`, { method: 'POST' });
  return readJson<DownloadTask>(response);
}

export async function openDownloadedFile(path: string, directory: boolean): Promise<void> {
  const params = new URLSearchParams({ path, directory: String(directory) });
  const response = await fetch(`/api/files/open?${params}`, { method: 'POST' });
  const result = await readJson<{ success: boolean; message: string }>(response);
  if (!result.success) {
    throw new Error(result.message || '无法打开目标');
  }
}
