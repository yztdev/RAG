<script setup lang="ts">
import { enableStatusOptions } from '@/constants/common';

defineOptions({
  name: 'UserSearch'
});

const emit = defineEmits<{
  search: [];
  reset: [];
}>();

defineProps<{ loading: boolean }>();

const { formRef } = useNaiveForm();

const model = defineModel<Api.User.SearchParams>('model', { required: true });
</script>

<template>
  <NForm
    ref="formRef"
    class="page-filters"
    :model="model"
    label-placement="left"
    :show-feedback="false"
    inline
    @submit.prevent="emit('search')"
  >
    <NFormItem label="关键词" path="keyword">
      <NInput v-model:value="model.keyword" placeholder="请输入关键词" clearable />
    </NFormItem>
    <NFormItem label="组织标签" path="orgTag">
      <OrgTagCascader v-model:value="model.orgTag" clearable class="w-200px!" />
    </NFormItem>
    <NFormItem label="启用状态" path="status">
      <NSelect
        v-model:value="model.status"
        placeholder="请选择启用状态"
        :options="enableStatusOptions"
        clearable
        class="w-200px!"
      />
    </NFormItem>
    <div class="filter-actions">
      <NButton type="primary" attr-type="submit" :loading="loading">查询</NButton>
      <NButton :disabled="loading" @click="emit('reset')">重置</NButton>
    </div>
  </NForm>
</template>

<style scoped></style>
