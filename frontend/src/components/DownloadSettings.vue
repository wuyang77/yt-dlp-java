<script setup lang="ts">
const cookieMode = defineModel<'FILE' | 'FIREFOX' | 'NONE'>('cookieMode', { required: true });
const proxy = defineModel<string>('proxy', { required: true });

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
    <label for="proxy">下载代理</label>
    <input id="proxy" v-model="proxy" type="text" placeholder="http://127.0.0.1:7890" autocomplete="url">
    <p class="hint">支持 HTTP(S) 和 SOCKS 代理。设置会保存在当前浏览器；留空时使用服务端默认代理。</p>
    <button class="primary" :disabled="loading || !url" @click="$emit('inspect')">{{ loading ? '正在读取格式…' : '读取视频格式  →' }}</button>
    <p class="hint">建议使用 cookies 文件访问年龄限制或登录后的视频。文件最终会以 MP4 写入服务端配置的下载目录。</p>
    <div v-if="error" class="error">{{ error }}</div>
  </aside>
</template>
