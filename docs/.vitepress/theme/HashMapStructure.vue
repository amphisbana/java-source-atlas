<template>
  <section class="hash-structure" aria-label="HashMap 数组、链表和红黑树结构示意图">
    <div class="hash-structure__label">空桶</div>
    <div class="hash-structure__row">
      <div class="hash-structure__bucket">table[0]</div>
      <div class="hash-structure__arrow">→</div>
      <span>空</span>
    </div>

    <div class="hash-structure__label">链表桶</div>
    <div class="hash-structure__row">
      <div class="hash-structure__bucket">table[3]</div>
      <div class="hash-structure__arrow">→</div>
      <div class="hash-structure__node">Node A</div>
      <div class="hash-structure__arrow">→</div>
      <div class="hash-structure__node">Node B</div>
    </div>

    <div class="hash-structure__label">树桶</div>
    <div class="hash-structure__row">
      <div class="hash-structure__bucket">table[7]</div>
      <div class="hash-structure__arrow">→</div>
      <div class="hash-structure__tree" aria-label="红黑树节点示意">
        <div class="hash-structure__node">M</div>
        <div class="hash-structure__node">G</div>
        <div class="hash-structure__node">T</div>
      </div>
    </div>
  </section>
</template>

