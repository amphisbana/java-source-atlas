<script setup lang="ts">
import { computed, ref } from 'vue'

const rawHash = ref(42)
const capacity = ref(16)

/**
 * 按 HashMap.hash 的核心规则混合高 16 位和低 16 位。
 */
function spreadHash(value: number): number {
  return (value ^ (value >>> 16)) >>> 0
}

/**
 * 使用容量掩码计算元素所在的桶下标。
 */
function bucketIndex(hash: number, tableCapacity: number): number {
  return hash & (tableCapacity - 1)
}

/**
 * 将 32 位数值格式化为便于观察高低位的二进制文本。
 */
function formatBinary(value: number): string {
  const bits = (value >>> 0).toString(2).padStart(32, '0')
  return `${bits.slice(0, 16)} ${bits.slice(16)}`
}

const mixedHash = computed(() => spreadHash(Number(rawHash.value) || 0))
const index = computed(() => bucketIndex(mixedHash.value, Number(capacity.value)))
const binaryHash = computed(() => formatBinary(mixedHash.value))
</script>

<template>
  <section class="hash-calculator" aria-label="HashMap 桶下标计算器">
    <div class="hash-calculator__controls">
      <label>
        原始 hashCode（十进制）
        <input v-model.number="rawHash" type="number" aria-label="原始 hashCode">
      </label>
      <label>
        table 容量
        <select v-model.number="capacity" aria-label="table 容量">
          <option :value="16">16</option>
          <option :value="32">32</option>
          <option :value="64">64</option>
          <option :value="128">128</option>
        </select>
      </label>
    </div>
    <div class="hash-calculator__result">
      <div class="hash-calculator__metric">
        <span>扰动后 hash</span>
        <strong>{{ mixedHash }}</strong>
      </div>
      <div class="hash-calculator__metric">
        <span>容量掩码</span>
        <strong>{{ capacity - 1 }}</strong>
      </div>
      <div class="hash-calculator__metric">
        <span>桶下标</span>
        <strong>{{ index }}</strong>
      </div>
    </div>
    <p class="hash-calculator__binary">扰动后二进制：{{ binaryHash }}</p>
  </section>
</template>

