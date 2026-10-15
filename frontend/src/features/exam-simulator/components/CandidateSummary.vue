<script setup lang="ts">
import { UserFilled } from '@element-plus/icons-vue'
import type { ExamCandidate } from '../types'

defineProps<{
  candidate: ExamCandidate
  compact?: boolean
}>()
</script>

<template>
  <section class="candidate-summary" :class="{ 'candidate-summary--compact': compact }">
    <div class="candidate-summary__avatar" aria-label="默认考生头像">
      <el-icon><UserFilled /></el-icon>
    </div>
    <dl class="candidate-summary__fields">
      <div><dt>准考证号</dt><dd>{{ candidate.maskedAdmissionTicket }}</dd></div>
      <div><dt>姓名</dt><dd>{{ candidate.name }}</dd></div>
      <div><dt>性别</dt><dd>{{ candidate.gender }}</dd></div>
      <div><dt>考试资格</dt><dd>{{ candidate.qualification }}</dd></div>
      <div><dt>考试科目</dt><dd>{{ candidate.subject }}</dd></div>
      <div><dt>证件号码</dt><dd>{{ candidate.maskedIdentityNumber }}</dd></div>
      <div><dt>机位号</dt><dd>{{ candidate.stationNo }}</dd></div>
    </dl>
  </section>
</template>

<style scoped>
.candidate-summary {
  display: grid;
  grid-template-columns: 132px minmax(0, 1fr);
  gap: 28px;
  align-items: center;
}

.candidate-summary__avatar {
  width: 132px;
  height: 150px;
  display: grid;
  place-items: center;
  color: #fff;
  background: #d5d8dc;
  border: 1px solid #c4c8ce;
}

.candidate-summary__avatar .el-icon {
  font-size: 88px;
}

.candidate-summary__fields {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 13px 36px;
  margin: 0;
}

.candidate-summary__fields > div {
  display: grid;
  grid-template-columns: 90px minmax(0, 1fr);
  gap: 10px;
  min-width: 0;
}

dt {
  color: #697586;
}

dd {
  margin: 0;
  color: #0c78d4;
  font-weight: 600;
  overflow-wrap: anywhere;
}

.candidate-summary--compact {
  grid-template-columns: 90px minmax(0, 1fr);
  gap: 20px;
}

.candidate-summary--compact .candidate-summary__avatar {
  width: 90px;
  height: 104px;
}

.candidate-summary--compact .candidate-summary__avatar .el-icon {
  font-size: 60px;
}

.candidate-summary--compact .candidate-summary__fields {
  font-size: 14px;
  gap: 7px 24px;
}

@media (max-width: 1450px) {
  .candidate-summary {
    grid-template-columns: 104px minmax(0, 1fr);
    gap: 20px;
  }
  .candidate-summary__avatar {
    width: 104px;
    height: 122px;
  }
  .candidate-summary__avatar .el-icon {
    font-size: 70px;
  }
  .candidate-summary__fields {
    font-size: 14px;
    gap: 8px 24px;
  }
}
</style>
