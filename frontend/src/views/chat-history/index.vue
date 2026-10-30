<script setup lang="ts">
import type { NScrollbar } from 'naive-ui';
import { VueMarkdownItProvider } from '@/vendor/vue-markdown-shiki';
import ChatMessage from '../chat/modules/chat-message.vue';

defineOptions({
  name: 'ChatHistory'
});

const scrollbarRef = ref<InstanceType<typeof NScrollbar>>();

const list = ref<Api.Chat.Message[]>([]);
const loading = ref(false);

watch(() => [...list.value], scrollToBottom);

function scrollToBottom() {
  setTimeout(() => {
    scrollbarRef.value?.scrollBy({
      top: 999999999999999,
      behavior: 'auto'
    });
  }, 100);
}

const range = ref<[number, number] | null>(null);
const userId = ref<number | null>(null);

const params = computed(() => {
  const query: {
    userid?: number;
    start_date?: string;
    end_date?: string;
  } = {};

  if (userId.value !== null) {
    query.userid = userId.value;
  }

  if (range.value) {
    query.start_date = dayjs(range.value[0]).format('YYYY-MM-DD');
    query.end_date = dayjs(range.value[1]).format('YYYY-MM-DD');
  }

  return query;
});

const appliedParams = ref<{ userid?: number; start_date?: string; end_date?: string }>({});

async function searchHistory() {
  appliedParams.value = { ...params.value };
  await getList();
}

async function resetFilters() {
  userId.value = null;
  range.value = null;
  await searchHistory();
}

onMounted(getList);

async function getList() {
  loading.value = true;
  const { error, data } = await request<Api.Chat.Message[]>({
    url: 'admin/conversation',
    params: appliedParams.value
  });
  if (!error) {
    list.value = data;
    scrollToBottom();
  }
  loading.value = false;
}
</script>

<template>
  <div class="workspace-page h-full flex-col overflow-hidden">
    <NCard
      title="聊天记录"
      :bordered="false"
      class="page-section min-h-0 flex-1"
      content-class="flex-col min-h-0 flex-1"
    >
      <NForm
        class="page-filters"
        :model="params"
        label-placement="left"
        :show-feedback="false"
        inline
        @submit.prevent="searchHistory"
      >
        <NFormItem label="用户">
          <TheSelect
            v-model:value="userId"
            url="admin/users/list"
            :params="{ page: 1, size: 999 }"
            key-field="content"
            value-field="userId"
            label-field="username"
            class="w-200px!"
            placeholder="全部用户"
          />
        </NFormItem>
        <NFormItem label="时间">
          <NDatePicker v-model:value="range" type="daterange" clearable />
        </NFormItem>
        <div class="filter-actions">
          <NButton type="primary" attr-type="submit" :loading="loading">查询</NButton>
          <NButton :disabled="loading" @click="resetFilters">重置</NButton>
        </div>
      </NForm>

      <NScrollbar ref="scrollbarRef" class="min-h-0 flex-1">
        <NSpin :show="loading" class="h-full">
          <div class="history-messages">
            <VueMarkdownItProvider>
              <ChatMessage v-for="(item, index) in list" :key="index" :msg="item" />
            </VueMarkdownItProvider>
          </div>
          <NEmpty v-if="!list.length" description="暂无数据" class="py-24" />
        </NSpin>
      </NScrollbar>
    </NCard>
  </div>
</template>

<style scoped lang="scss">
.history-messages {
  max-width: 920px;
  margin: 0 auto;
  padding: 12px 0;
}
</style>
