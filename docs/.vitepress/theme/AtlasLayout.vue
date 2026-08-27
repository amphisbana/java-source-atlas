<script setup lang="ts">
import DefaultTheme from 'vitepress/theme'
import AtlasDocOutline from './AtlasDocOutline.vue'

const { Layout } = DefaultTheme
</script>

<template>
  <Layout>
    <template #aside-outline-before>
      <AtlasDocOutline />
    </template>
  </Layout>
</template>
