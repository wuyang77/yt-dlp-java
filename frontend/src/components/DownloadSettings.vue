<script setup lang="ts">
const cookieMode = defineModel<'FILE' | 'FIREFOX' | 'NONE'>('cookieMode', { required: true });

defineProps<{
  url: string;
  loading: boolean;
  error: string;
}>();
defineEmits<{ inspect: [] }>();
</script>

<template>
  <aside class="panel control">
    <div class="eyebrow">01 / 下载设置</div>
    <div class="hint" style="margin-top:10px">视频地址请在顶部搜索框输入</div>
    <label for="cookies">登录凭据</label>
    <select id="cookies" v-model="cookieMode"><option value="FILE">cookies.txt 文件</option><option value="FIREFOX">Firefox 登录态</option><option value="NONE">匿名访问</option></select>
    <button class="primary" :disabled="loading || !url" @click="$emit('inspect')">{{ loading ? '正在读取格式…' : '读取视频格式  →' }}</button>
    <p class="hint">建议使用 cookies 文件访问年龄限制或登录后的视频。文件最终会以 MP4 写入服务端配置的下载目录。</p>
    <div v-if="error" class="error">{{ error }}</div>
  </aside>
</template>
