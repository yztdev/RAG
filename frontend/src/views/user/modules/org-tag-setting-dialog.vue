<script setup lang="ts">
defineOptions({
  name: 'OrgTagSettingDialog'
});

const props = defineProps<{
  rowData: Api.User.Item;
}>();

const emit = defineEmits<{ submitted: [] }>();

const visible = defineModel<boolean>('visible', { default: false });
const loading = ref(false);
const { formRef, validate, restoreValidation } = useNaiveForm();

type Model = {
  orgTags: string[];
};

const model = ref<Model>(createDefaultModel());

function createDefaultModel(): Model {
  return {
    orgTags: []
  };
}

async function handleUpdateModelWhenEdit() {
  model.value = createDefaultModel();
  model.value.orgTags = props.rowData.orgTags.filter(tag => !tag.tagId!.startsWith('PRIVATE_')).map(tag => tag.tagId!);
}

function close() {
  visible.value = false;
}

async function handleSubmit() {
  await validate();
  loading.value = true;
  const res = await request({
    method: 'PUT',
    url: `/admin/users/${props.rowData.userId}/org-tags`,
    data: model.value
  });
  if (!res.error) {
    window.$message?.success('操作成功');
    close();
    emit('submitted');
  }
  loading.value = false;
}

watch(visible, () => {
  if (visible.value) {
    handleUpdateModelWhenEdit();
    restoreValidation();
  }
});
</script>

<template>
  <NModal
    v-model:show="visible"
    preset="dialog"
    title="组织标签设置"
    :show-icon="false"
    :mask-closable="false"
    class="w-500px!"
    @positive-click="handleSubmit"
  >
    <NForm ref="formRef" :model="model" label-placement="left" :label-width="100" mt-10>
      <NFormItem label="用户名" path="username">
        <NInput :value="rowData.username" readonly />
      </NFormItem>
      <NFormItem label="组织标签" path="orgTags">
        <OrgTagCascader v-model:value="model.orgTags" multiple exclude-private />
      </NFormItem>
      <div class="text-12px text-gray-500">私人空间由账户自动维护，无需分配；此处仅设置加入的组织。</div>
    </NForm>
    <template #action>
      <NSpace :size="16">
        <NButton @click="close">取消</NButton>
        <NButton type="primary" @click="handleSubmit">保存</NButton>
      </NSpace>
    </template>
  </NModal>
</template>

<style scoped></style>
