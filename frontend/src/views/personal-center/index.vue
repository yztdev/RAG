<script setup lang="ts">
import { computed, h, onMounted, ref } from 'vue';
import { NTag } from 'naive-ui';
const { userInfo } = storeToRefs(useAuthStore());

const tags = ref<Api.OrgTag.Mine>({
  orgTags: [],
  primaryOrg: '',
  orgTagDetails: []
});

const usage = ref<Api.User.UsageSnapshot>({
  day: '',
  chatRequestCount: 0,
  llm: {
    enabled: false,
    usedTokens: 0,
    limitTokens: 0,
    remainingTokens: 0,
    requestCount: 0
  },
  embedding: {
    enabled: false,
    usedTokens: 0,
    limitTokens: 0,
    remainingTokens: 0,
    requestCount: 0
  }
});

const loading = ref(false);

// Token 记录相关变量
const tokenRecords = ref<Api.User.TokenRecord[]>([]);
const tokenRecordLoading = ref(false);
const pagination = ref({
  page: 1,
  pageSize: 10,
  total: 0,
  pageCount: 0
});
const getPersonalData = async () => {
  loading.value = true;
  const [{ error: orgError, data: orgData }, { error: usageError, data: usageData }] = await Promise.all([
    request<Api.OrgTag.Mine>({
      url: '/users/org-tags'
    }),
    request<Api.User.UsageSnapshot>({
      url: '/users/usage'
    })
  ]);

  if (!orgError) {
    tags.value = orgData;
  }

  if (!usageError) {
    usage.value = usageData;
  }

  // 获取 Token 记录
  getTokenRecords();

  loading.value = false;
};

const getOrgTags = async () => {
  const { error, data } = await request<Api.OrgTag.Mine>({
    url: '/users/org-tags'
  });
  if (!error) {
    tags.value = data;
  }
};

onMounted(() => {
  getPersonalData();
});

const visible = ref(false);
const currentTagId = ref('');
const showModal = (tagId: string) => {
  if (tagId === tags.value.primaryOrg) return;
  visible.value = true;
  currentTagId.value = tagId;
};
const submitLoading = ref(false);
const setPrimaryOrg = async () => {
  submitLoading.value = true;
  const { error } = await request({
    url: '/users/primary-org',
    method: 'PUT',
    data: { primaryOrg: currentTagId.value, userId: userInfo.value.id }
  });
  if (!error) {
    visible.value = false;
    getOrgTags();
  }
  submitLoading.value = false;
};

// Token 记录相关方法
const getTokenRecords = async () => {
  tokenRecordLoading.value = true;
  try {
    const { error, data } = await request({
      url: '/users/token-records',
      method: 'GET',
      params: {
        page: pagination.value.page - 1,
        size: pagination.value.pageSize
      }
    });

    if (!error && data) {
      tokenRecords.value = data.content || [];
      pagination.value.total = data.totalElements || 0;
      pagination.value.pageCount = data.totalPages || 0;
    }
  } finally {
    tokenRecordLoading.value = false;
  }
};

const handlePageChange = (page: number) => {
  pagination.value.page = page;
  getTokenRecords();
};

// Token 记录表格列定义
const tokenRecordColumns = computed(() => [
  {
    title: '日期',
    key: 'recordDate',
    width: 130,
    render: (row: Api.User.TokenRecord) => row.recordDate
  },
  {
    title: 'Token 类型',
    key: 'tokenType',
    width: 140,
    render: (row: Api.User.TokenRecord) => {
      const typeMap: Record<string, { text: string; type: any }> = {
        LLM: { text: 'LLM', type: 'info' },
        EMBEDDING: { text: 'Embedding', type: 'success' }
      };
      const type = typeMap[row.tokenType] || { text: row.tokenType, type: 'default' };
      return h(NTag, { type: type.type, bordered: false }, () => type.text);
    }
  },
  {
    title: '变动类型',
    key: 'changeType',
    width: 100,
    render: (row: Api.User.TokenRecord) => {
      const typeMap: Record<string, { text: string; type: any }> = {
        INCREASE: { text: '充值', type: 'success' },
        CONSUME: { text: '消耗', type: 'warning' }
      };
      const type = typeMap[row.changeType] || { text: row.changeType, type: 'default' };
      return h(NTag, { type: type.type, bordered: false }, () => type.text);
    }
  },
  {
    title: '变动数量',
    key: 'amount',
    width: 120,
    render: (row: Api.User.TokenRecord) => {
      const sign = row.changeType === 'INCREASE' ? '+' : '-';
      return `${sign}${row.amount.toLocaleString()}`;
    }
  },
  {
    title: '变动前余额',
    key: 'balanceBefore',
    width: 120,
    render: (row: Api.User.TokenRecord) => row.balanceBefore?.toLocaleString() || '-'
  },
  {
    title: '变动后余额',
    key: 'balanceAfter',
    width: 120,
    render: (row: Api.User.TokenRecord) => row.balanceAfter?.toLocaleString() || '-'
  },
  {
    title: '原因',
    key: 'reason',
    minWidth: 100,
    ellipsis: { tooltip: true },
    render: (row: Api.User.TokenRecord) => row.reason || '-'
  },
  {
    title: '请求次数',
    key: 'requestCount',
    width: 80,
    render: (row: Api.User.TokenRecord) => row.requestCount?.toLocaleString() || '0'
  },
  {
    title: '创建时间',
    key: 'createdAt',
    width: 180,
    render: (row: Api.User.TokenRecord) => new Date(row.createdAt).toLocaleString('zh-CN')
  }
]);
</script>

<template>
  <div class="workspace-page profile-page overflow-auto">
    <NSpin :show="loading">
      <NCard :bordered="false" class="page-section profile-section">
        <template #header>
          <div class="flex items-center gap-4">
            <NAvatar size="large"><icon-solar:user-circle-linear class="text-icon-large" /></NAvatar>
            <div>
              <div>{{ userInfo.username }}</div>
              <div class="text-muted mt-1 text-xs">今日额度 · {{ usage.day || '未统计' }}</div>
            </div>
          </div>
        </template>
        <div class="quota-grid">
          <section
            v-for="(quota, scope) in { llm: usage.llm, embedding: usage.embedding }"
            :key="scope"
            class="quota-block"
          >
            <h2>{{ scope === 'llm' ? 'LLM Token' : 'Embedding Token' }}</h2>
            <template v-if="quota.enabled">
              <div class="quota-value">
                {{ quota.remainingTokens.toLocaleString() }}
                <span>剩余</span>
              </div>
              <NProgress
                type="line"
                :percentage="
                  Math.min(100, Math.max(0, quota.limitTokens > 0 ? (quota.usedTokens / quota.limitTokens) * 100 : 0))
                "
                :show-indicator="false"
                :height="4"
              />
              <div class="text-muted mt-3 flex flex-wrap justify-between gap-2 text-xs">
                <span>已用 {{ quota.usedTokens.toLocaleString() }} / {{ quota.limitTokens.toLocaleString() }}</span>
                <span>请求 {{ quota.requestCount.toLocaleString() }} 次</span>
              </div>
            </template>
            <div v-else class="text-muted mt-4">当前未启用配额</div>
          </section>
        </div>

        <section class="profile-tags">
          <h2>组织标签</h2>
          <div class="org-tag-grid">
            <button
              v-for="tag in tags.orgTagDetails"
              :key="tag.tagId"
              type="button"
              class="org-tag-option"
              @click="showModal(tag.tagId)"
            >
              <div class="flex items-center justify-between gap-3">
                <span>{{ tag.name }}</span>
                <NTag v-if="tag.tagId === tags.primaryOrg" type="primary" size="small" :bordered="false">主标签</NTag>
              </div>
              <p class="text-muted mt-2 text-xs">{{ tag.description }}</p>
            </button>
          </div>
        </section>

        <section class="profile-records">
          <h2>Token 变动记录</h2>
          <NSpin :show="tokenRecordLoading">
            <NDataTable
              v-if="tokenRecords.length > 0"
              :bordered="false"
              :columns="tokenRecordColumns"
              :data="tokenRecords"
              :loading="tokenRecordLoading"
              :pagination="{
                page: pagination.page,
                pageSize: pagination.pageSize,
                itemCount: pagination.total,
                onChange: handlePageChange
              }"
              :scroll-x="1200"
              size="small"
            />
            <NEmpty v-else description="暂无记录" />
          </NSpin>
        </section>
      </NCard>
    </NSpin>
    <NModal
      v-model:show="visible"
      :loading="submitLoading"
      preset="dialog"
      title="设置主标签"
      content="确定将当前标签设置为主标签吗？"
      positive-text="确认"
      negative-text="取消"
      @positive-click="setPrimaryOrg"
      @negative-click="visible = false"
    />
  </div>
</template>

<style scoped lang="scss">
.profile-section {
  max-width: 1120px;
  margin: 0 auto;
}
.quota-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 48px;
  padding: 12px 0 32px;
}
h2 {
  margin: 0 0 20px;
  font-size: 15px;
  font-weight: 600;
}
.quota-value {
  margin: 16px 0;
  font-size: 30px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}
.quota-value span {
  margin-left: 12px;
  font-size: 12px;
  font-weight: 400;
  color: var(--app-muted);
}
.profile-tags,
.profile-records {
  border-top: 1px solid var(--app-border);
  padding-top: 28px;
  margin-top: 8px;
}
.profile-tags {
  padding-bottom: 28px;
}
.org-tag-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
  gap: 16px;
}
.org-tag-option {
  padding: 16px;
  border: 0;
  border-radius: 8px;
  text-align: left;
  background: var(--app-subtle);
  color: var(--app-text);
  cursor: pointer;
}
.org-tag-option:focus-visible {
  outline: 2px solid rgb(var(--primary-color));
}
@media (max-width: 640px) {
  .quota-grid {
    grid-template-columns: minmax(0, 1fr);
    gap: 32px;
  }
  .quota-value {
    font-size: 26px;
  }
  .org-tag-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
