<script setup lang="ts">
import DownloadProgress from '@/components/DownloadProgress.vue';
import DownloadSettings from '@/components/DownloadSettings.vue';
import FormatPanel from '@/components/FormatPanel.vue';
import PreviewDialog from '@/components/PreviewDialog.vue';
import TopBar from '@/components/TopBar.vue';
import { useDownloader } from '@/composables/useDownloader';

const {
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
} = useDownloader();
</script>

<template>
  <div class="shell">
    <TopBar
      v-model:url="url"
      :service-online="serviceOnline"
      @paste="pasteUrl"
      @preview="openPreview"
      @inspect="inspect"
    />
    <section class="hero">
      <div><div class="hero-kicker">媒体下载工作台</div><h1>把视频，<br>锻造成文件。</h1></div>
      <p>读取可用媒体流，精确选择视频与音频轨道，交给 ffmpeg 合并成 MP4。下载进度、速度与状态一目了然。</p>
    </section>
    <main class="workspace">
      <DownloadSettings
        v-model:cookie-mode="cookieMode"
        :url="url"
        :loading="loading"
        :error="error"
        @inspect="inspect"
      />
      <section class="content">
        <div v-if="!formats.length && !loading" class="panel empty"><div><div class="empty-icon">◌</div><h2>等待一段视频</h2><p>粘贴地址后，这里会出现它的画质与音频轨道。</p></div></div>
        <div v-if="loading" class="panel empty"><div><div class="empty-icon">◌</div><h2>正在探测媒体流</h2><p>正在获取格式并并行识别未标注音轨的语种，请稍候。</p></div></div>
        <FormatPanel
          v-if="formats.length"
          v-model:selected-video="selectedVideo"
          v-model:selected-audio="selectedAudio"
          :formats="formats"
          :videos="videos"
          :audios="audios"
        />
        <DownloadProgress
          v-if="formats.length"
          v-model:download-mode="downloadMode"
          :task="task"
          :downloading="downloading"
          :download-ready="downloadReady"
          :download-button-text="downloadButtonText"
          :result="result"
          @download="download"
          @pause="pause"
          @resume="resume"
          @open-target="openTarget"
        />
      </section>
    </main>
    <footer class="developer-credit">开发人员：吴洋</footer>
    <PreviewDialog
      :open="previewOpen"
      :video-id="previewVideoId"
      :embed-url="previewEmbedUrl"
      @close="closePreview"
    />
  </div>
</template>
