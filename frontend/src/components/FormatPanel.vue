<script setup lang="ts">
import type { MediaFormat } from '@/types/api';

const selectedVideo = defineModel<string>('selectedVideo', { required: true });
const selectedAudio = defineModel<string>('selectedAudio', { required: true });

const languageNames = new Intl.DisplayNames(['zh-CN'], { type: 'language' });

function displayLanguage(item: MediaFormat): string {
  if (!item.language) {
    switch (item.languageStatus) {
      case 'not-configured': return '未配置本地识别';
      case 'unrecognized': return '无法判断语种';
      case 'failed': return '识别失败';
      default: return '未标注';
    }
  }

  try {
    const name = `${languageNames.of(item.language) ?? item.language} (${item.language})`;
    return item.languageStatus === 'detected' ? `${name} · 本地识别` : name;
  } catch {
    return item.language;
  }
}

defineProps<{
  formats: MediaFormat[];
  videos: MediaFormat[];
  audios: MediaFormat[];
}>();
</script>

<template>
  <div class="panel format-panel">
    <div class="format-head"><h2>可用格式</h2><span class="pill">已找到 {{ formats.length }} 条轨道</span></div>
    <div class="picks">
      <div class="pick active"><div class="pick-top"><span class="pick-title">视频轨道</span><strong>▣</strong></div><select v-model="selectedVideo"><option v-for="item in videos" :key="item.id" :value="item.id">{{ item.id }} · {{ item.res }} · {{ item.tbr }}</option></select></div>
      <div class="pick active"><div class="pick-top"><span class="pick-title">音频轨道</span><strong>◖</strong></div><select v-model="selectedAudio"><option v-for="item in audios" :key="item.id" :value="item.id">{{ item.id }} · {{ displayLanguage(item) }} · {{ item.tbr }} · {{ item.acodec }}</option></select></div>
    </div>
    <div class="table-wrap"><table><thead><tr><th>类型</th><th>编号</th><th>语言</th><th>容器</th><th>分辨率</th><th>码率</th><th>编码</th><th>大小</th></tr></thead><tbody><tr v-for="item in formats" :key="item.id + item.ext" :class="{chosen: item.id === selectedVideo || item.id === selectedAudio}"><td><span class="tag" :class="{video: !item.audioOnly}">{{ item.audioOnly ? '音频' : '视频' }}</span></td><td>{{ item.id }}</td><td>{{ item.audioOnly ? displayLanguage(item) : '—' }}</td><td>{{ item.ext }}</td><td>{{ item.res }}</td><td>{{ item.audioOnly ? item.abr : item.tbr }}</td><td>{{ item.audioOnly ? item.acodec : item.vcodec }}</td><td>{{ item.size }}</td></tr></tbody></table></div>
  </div>
</template>
