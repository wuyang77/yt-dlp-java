export interface MediaFormat {
  id: string;
  ext: string;
  res: string;
  fps: string;
  size: string;
  tbr: string;
  vcodec: string;
  acodec: string;
  abr: string;
  language: string;
  languageStatus: 'metadata' | 'detected' | 'not-configured' | 'unrecognized' | 'failed' | 'unavailable' | 'not-applicable';
  audioOnly: boolean;
}

export interface FormatListResponse {
  success: boolean;
  message: string;
  formats: MediaFormat[];
}

export interface DownloadResponse {
  success: boolean;
  message: string;
  filePath: string | null;
  fileName: string | null;
  bestVideo: string | null;
  bestAudio: string | null;
  diagnostics: string[];
}

export interface DownloadTask {
  taskId: string;
  status: 'RUNNING' | 'PAUSED' | 'COMPLETED' | 'FAILED' | string;
  progress: number;
  speed: string;
  stage: string;
  message: string;
  result: DownloadResponse | null;
  logs: string[];
}

export type CookieMode = 'FILE' | 'FIREFOX' | 'NONE';
export type DownloadMode = 'CUSTOM' | 'VIDEO_ONLY' | 'AUDIO_ONLY';

export interface StartDownloadRequest {
  url: string;
  mode: DownloadMode;
  cookieMode: CookieMode;
  formatId: string;
}
