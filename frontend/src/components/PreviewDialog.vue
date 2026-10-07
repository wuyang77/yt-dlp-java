<script setup lang="ts">
defineProps<{
  open: boolean;
  videoId: string;
  embedUrl: string;
}>();
defineEmits<{ close: [] }>();
</script>

<template>
  <div v-if="open" class="preview-overlay" role="dialog" aria-modal="true" aria-label="视频预览" @click.self="$emit('close')">
    <section class="preview-dialog">
      <button class="secondary preview-close" aria-label="关闭预览" title="关闭预览" @click="$emit('close')">×</button>
      <iframe v-if="videoId" class="preview-frame" :src="embedUrl" title="YouTube 视频播放器"
        allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share"
        referrerpolicy="strict-origin-when-cross-origin" allowfullscreen></iframe>
    </section>
  </div>
</template>
