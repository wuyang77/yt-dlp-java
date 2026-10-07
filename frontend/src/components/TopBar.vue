<script setup lang="ts">
const url = defineModel<string>('url', { required: true });

defineProps<{ serviceOnline: boolean }>();
defineEmits<{
  paste: [];
  preview: [];
  inspect: [];
}>();
</script>

<template>
  <header class="topbar">
    <div class="brand"><span class="brand-mark">▶</span><span>视频探测台</span><span class="brand-sub">下载工作室</span></div>
    <div class="top-search"><input v-model.trim="url" aria-label="视频地址" placeholder="搜索或粘贴视频链接" @keyup.enter="$emit('inspect')"><button class="paste-button" aria-label="粘贴视频链接" @click="$emit('paste')">粘贴</button><button class="preview-button" :disabled="!url" aria-label="预览视频" @click="$emit('preview')">预览</button><button class="inspect-button" aria-label="读取视频" @click="$emit('inspect')">⌕</button></div>
    <div class="status"><span class="status-dot"></span>{{ serviceOnline ? '服务在线' : '正在连接' }}</div>
  </header>
</template>
