import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import {
  checkHealth,
  fetchDownloadTask,
  fetchFormats,
  openDownloadedFile,
  pauseDownload,
  resumeDownload,
  startDownload,
} from '@/services/api';
import type { CookieMode, DownloadMode, DownloadResponse, DownloadTask, MediaFormat } from '@/types/api';

function errorMessage(error: unknown, fallback: string): string {
  return error instanceof Error ? error.message : fallback;
}

export function useDownloader() {
  const url = ref('');
  const cookieMode = ref<CookieMode>('FILE');
  const formats = ref<MediaFormat[]>([]);
  const selectedVideo = ref('');
  const selectedAudio = ref('');
  const downloadMode = ref<DownloadMode>('CUSTOM');
  const loading = ref(false);
  const downloading = ref(false);
  const task = ref<DownloadTask | null>(null);
  const result = ref<DownloadResponse | null>(null);
  const error = ref('');
  const serviceOnline = ref(false);
  const previewOpen = ref(false);
  const previewVideoId = ref('');
  let pollTimer: ReturnType<typeof setTimeout> | undefined;

  const videos = computed(() => formats.value.filter((item) => !item.audioOnly));
  const audios = computed(() => formats.value.filter((item) => item.audioOnly));
  const previewEmbedUrl = computed(() => previewVideoId.value
    ? `https://www.youtube.com/embed/${previewVideoId.value}?autoplay=1&controls=1&playsinline=1&hl=zh-CN&cc_lang_pref=zh-Hans`
    : '');
  const downloadReady = computed(() => downloadMode.value === 'VIDEO_ONLY'
    ? Boolean(selectedVideo.value)
    : downloadMode.value === 'AUDIO_ONLY'
      ? Boolean(selectedAudio.value)
      : Boolean(selectedVideo.value && selectedAudio.value));
  const downloadButtonText = computed(() => downloadMode.value === 'VIDEO_ONLY'
    ? '仅下载视频'
    : downloadMode.value === 'AUDIO_ONLY'
      ? '仅下载音频'
      : '下载并合并为 MP4');

  async function pasteUrl(): Promise<void> {
    try {
      url.value = (await navigator.clipboard.readText()).trim();
      error.value = '';
    } catch {
      error.value = '无法读取剪贴板，请检查浏览器权限后重试';
    }
  }

  function extractYouTubeVideoId(value: string): string {
    try {
      const parsed = new URL(value);
      const host = parsed.hostname.toLowerCase().replace(/^www\./, '');
      let id = '';
      if (host === 'youtu.be') {
        id = parsed.pathname.split('/').filter(Boolean)[0] || '';
      } else if (host === 'youtube.com' || host.endsWith('.youtube.com')) {
        id = parsed.searchParams.get('v')
          || parsed.pathname.match(/^\/(?:embed|shorts|live|v)\/([^/?]+)/)?.[1]
          || '';
      }
      return /^[A-Za-z0-9_-]{11}$/.test(id) ? id : '';
    } catch {
      return '';
    }
  }

  function openPreview(): void {
    if (!url.value.trim()) return;
    const videoId = extractYouTubeVideoId(url.value);
    if (!videoId) {
      error.value = '无法识别该 YouTube 视频链接';
      return;
    }
    error.value = '';
    previewOpen.value = true;
    previewVideoId.value = videoId;
  }

  function closePreview(): void {
    previewOpen.value = false;
    previewVideoId.value = '';
  }

  async function inspect(): Promise<void> {
    loading.value = true;
    error.value = '';
    formats.value = [];
    task.value = null;
    result.value = null;
    try {
      const data = await fetchFormats(url.value, cookieMode.value);
      if (!data.success) throw new Error(data.message || '读取格式失败');
      formats.value = data.formats || [];
      selectedVideo.value = videos.value[0]?.id || '';
      selectedAudio.value = audios.value[0]?.id || '';
    } catch (cause) {
      error.value = errorMessage(cause, '读取格式失败');
    } finally {
      loading.value = false;
    }
  }

  function poll(): void {
    if (!task.value) return;
    clearTimeout(pollTimer);
    pollTimer = setTimeout(async () => {
      if (!task.value) return;
      try {
        task.value = await fetchDownloadTask(task.value.taskId);
        if (task.value.status === 'COMPLETED') {
          result.value = task.value.result;
          downloading.value = false;
          return;
        }
        if (task.value.status === 'FAILED') {
          error.value = task.value.message || '下载失败';
          downloading.value = false;
          return;
        }
        if (task.value.status === 'PAUSED') {
          downloading.value = false;
          return;
        }
        poll();
      } catch (cause) {
        error.value = errorMessage(cause, '读取下载进度失败');
        downloading.value = false;
      }
    }, 700);
  }

  async function download(): Promise<void> {
    downloading.value = true;
    error.value = '';
    result.value = null;
    const formatId = downloadMode.value === 'VIDEO_ONLY'
      ? selectedVideo.value
      : downloadMode.value === 'AUDIO_ONLY'
        ? selectedAudio.value
        : `${selectedVideo.value}+${selectedAudio.value}`;
    try {
      task.value = await startDownload({
        url: url.value,
        mode: downloadMode.value,
        cookieMode: cookieMode.value,
        formatId,
      });
      poll();
    } catch (cause) {
      error.value = errorMessage(cause, '无法创建下载任务');
      downloading.value = false;
    }
  }

  async function pause(): Promise<void> {
    if (!task.value) return;
    try {
      task.value = await pauseDownload(task.value.taskId);
      poll();
    } catch (cause) {
      error.value = errorMessage(cause, '暂停下载失败');
    }
  }

  async function resume(): Promise<void> {
    if (!task.value) return;
    try {
      task.value = await resumeDownload(task.value.taskId);
      downloading.value = true;
      poll();
    } catch (cause) {
      error.value = errorMessage(cause, '继续下载失败');
    }
  }

  async function openTarget(path: string, directory = false): Promise<void> {
    error.value = '';
    try {
      await openDownloadedFile(path, directory);
    } catch (cause) {
      error.value = errorMessage(cause, '无法打开目标');
    }
  }

  onMounted(async () => {
    serviceOnline.value = await checkHealth();
  });
  onBeforeUnmount(() => clearTimeout(pollTimer));

  return {
    url,
    cookieMode,
    formats,
    videos,
    audios,
    selectedVideo,
    selectedAudio,
    downloadMode,
    loading,
    downloading,
    task,
    result,
    error,
    serviceOnline,
    previewOpen,
    previewVideoId,
    previewEmbedUrl,
    downloadReady,
    downloadButtonText,
    pasteUrl,
    inspect,
    openPreview,
    closePreview,
    download,
    pause,
    resume,
    openTarget,
  };
}
