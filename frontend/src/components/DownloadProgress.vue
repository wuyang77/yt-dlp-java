<script setup lang="ts">
import type { DownloadMode, DownloadResponse, DownloadTask } from '@/types/api';

const downloadMode = defineModel<DownloadMode>('downloadMode', { required: true });

defineProps<{
  task: DownloadTask | null;
  downloading: boolean;
  downloadReady: boolean;
  downloadButtonText: string;
  result: DownloadResponse | null;
}>();
defineEmits<{
  download: [];
  pause: [];
  resume: [];
  openTarget: [path: string, directory?: boolean];
}>();
</script>

<template>
  <div class="panel progress-panel">
    <div class="progress-row"><div><div class="eyebrow">02 / 开始下载</div><div class="stage">{{ task ? task.stage : '选择轨道后开始下载' }}</div><div class="speed">速度：{{ task ? task.speed : '-' }}</div></div><strong>{{ Number(task ? task.progress : 0).toFixed(2) }}%</strong></div>
    <div class="progress-track"><div class="progress-bar" :style="{width: (task ? task.progress : 0) + '%'}"></div></div>
    <label class="download-mode" for="download-mode">下载方式</label>
    <select id="download-mode" v-model="downloadMode">
      <option value="CUSTOM">视频 + 音频（合并为 MP4）</option>
      <option value="VIDEO_ONLY">仅下载视频</option>
      <option value="AUDIO_ONLY">仅下载音频</option>
    </select>
    <button class="primary" :disabled="downloading || !downloadReady" @click="$emit('download')">{{ downloading ? '正在下载…' : downloadButtonText + '  →' }}</button>
    <button v-if="task && task.status === 'RUNNING'" class="secondary" @click="$emit('pause')">暂停</button>
    <button v-if="task && task.status === 'PAUSED'" class="secondary" @click="$emit('resume')">继续下载</button>
    <div v-if="result" class="result"><div class="result-title">下载完成，文件已经就位。</div><span class="path">{{ result.filePath }}</span><div class="actions"><button class="secondary" @click="$emit('openTarget', result.filePath || '')">⌗ 打开文件</button><button class="secondary" @click="$emit('openTarget', result.filePath || '', true)">□ 打开所在目录</button></div></div>
  </div>
</template>
