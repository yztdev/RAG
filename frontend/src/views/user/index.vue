<script setup lang="tsx">
import { ref } from 'vue';
import { NButton, NTag } from 'naive-ui';
import UserSearch from './modules/user-search.vue';
import OrgTagSettingDialog from './modules/org-tag-setting-dialog.vue';
import TokenQuotaDialog from './modules/token-quota-dialog.vue';

const appStore = useAppStore();
const authStore = useAuthStore();

function apiFn(params: Api.User.SearchParams) {
  return request<Api.User.List>({ url: '/admin/users/list', params });
}

const { columns, columnChecks, data, getData, loading, mobilePagination, updateSearchParams, getDataByPage } = useTable(
  {
    apiFn,
    apiParams: {
      keyword: null,
      orgTag: null,
      status: null
    },
    columns: () => [
      {
        key: 'index',
        title: '序号',
        width: 64
      },
      {
        key: 'username',
        title: '用户名',
        minWidth: 150,
        ellipsis: { tooltip: true }
      },
      {
        key: 'orgTags',
        title: '标签',
        render: row => (
          <div class="flex flex-wrap gap-2">
            {row.orgTags.map(tag => (
              <NTag bordered={false} key={tag.tagId} type={tag.tagId === row.primaryOrg ? 'primary' : 'default'}>
                {tag.name}
              </NTag>
            ))}
          </div>
        )
      },
      {
        key: 'status',
        title: '是否启用',
        width: 100,
        render: row => (
          <NTag bordered={false} type={row.status ? 'success' : 'warning'}>
            {row.status ? '已启用' : '已禁用'}
          </NTag>
        )
      },
      {
        key: 'createdAt',
        title: '创建时间',
        width: 200,
        render: row => dayjs(row.createdAt).format('YYYY-MM-DD HH:mm:ss')
      },
      {
        key: 'chatUsage',
        title: '聊天次数',
        width: 130,
        render: row => (
          <div class="flex flex-col gap-1 text-xs">
            <span>{Number(row.usage?.chatRequestCount || 0).toLocaleString()} 次</span>
            <span class="text-muted">今日消息数</span>
          </div>
        )
      },
      {
        key: 'llmUsage',
        title: 'LLM额度',
        width: 220,
        render: row => {
          const quota = row.usage?.llm;
          if (!quota?.enabled) {
            return <span class="text-muted">未启用</span>;
          }
          return (
            <div class="flex flex-col gap-1 text-xs">
              <span>
                {Number(quota.usedTokens || 0).toLocaleString()} / {Number(quota.limitTokens || 0).toLocaleString()}
              </span>
              <span class="text-muted">
                剩余 {Number(quota.remainingTokens || 0).toLocaleString()} · {quota.requestCount} 次
              </span>
            </div>
          );
        }
      },
      {
        key: 'embeddingUsage',
        title: 'Embedding额度',
        width: 220,
        render: row => {
          const quota = row.usage?.embedding;
          if (!quota?.enabled) {
            return <span class="text-muted">未启用</span>;
          }
          return (
            <div class="flex flex-col gap-1 text-xs">
              <span>
                {Number(quota.usedTokens || 0).toLocaleString()} / {Number(quota.limitTokens || 0).toLocaleString()}
              </span>
              <span class="text-muted">
                剩余 {Number(quota.remainingTokens || 0).toLocaleString()} · {quota.requestCount} 次
              </span>
            </div>
          );
        }
      },
      {
        key: 'operate',
        title: '操作',
        width: 230,
        render: row => (
          <div class="flex gap-2">
            <NButton type="primary" quaternary size="small" onClick={() => handleOrgTag(row)}>
              分配组织标签
            </NButton>
            {authStore.isAdmin ? (
              <NButton type="warning" quaternary size="small" onClick={() => handleTokenQuota(row)}>
                追加 Token
              </NButton>
            ) : null}
          </div>
        )
      }
    ]
  }
);

const visible = ref(false);
const editingData = ref<Api.User.Item | null>(null);
const tokenVisible = ref(false);
const tokenEditingData = ref<Api.User.Item | null>(null);

function handleOrgTag(row: Api.User.Item) {
  editingData.value = row;
  visible.value = true;
}

const filterDraft = ref<Api.User.SearchParams>({ keyword: null, orgTag: null, status: null });

async function searchUsers() {
  updateSearchParams({ ...filterDraft.value });
  await getDataByPage(1);
}

async function resetFilters() {
  filterDraft.value = { keyword: null, orgTag: null, status: null };
  await searchUsers();
}

function handleTokenQuota(row: Api.User.Item) {
  tokenEditingData.value = row;
  tokenVisible.value = true;
}
</script>

<template>
  <div class="workspace-page min-h-500px flex-col-stretch gap-16px overflow-auto">
    <NCard title="用户列表" :bordered="false" size="small" class="page-section sm:flex-1-hidden">
      <template #header-extra>
        <TableHeaderOperation v-model:columns="columnChecks" :addable="false" :loading="loading" @refresh="getData" />
      </template>
      <UserSearch v-model:model="filterDraft" :loading="loading" @reset="resetFilters" @search="searchUsers" />
      <NDataTable
        :bordered="false"
        :columns="columns"
        :data="data"
        size="small"
        :flex-height="!appStore.isMobile"
        :scroll-x="1400"
        :loading="loading"
        remote
        :row-key="row => row.userId"
        :pagination="mobilePagination"
        class="sm:h-full"
      />
    </NCard>

    <OrgTagSettingDialog v-model:visible="visible" :row-data="editingData!" @submitted="getData" />
    <TokenQuotaDialog v-model:visible="tokenVisible" :row-data="tokenEditingData!" @submitted="getData" />
  </div>
</template>

<style scoped lang="scss"></style>
