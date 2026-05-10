<template>
  <el-dialog v-model="dialogVisible" center title="快速订阅"
             :close-on-click-modal="!loading"
             :close-on-press-escape="!loading"
             :show-close="!loading"
             width="700"
  >
    <div v-if="step === 1">
      <el-form label-width="auto">
        <el-form-item label="Bangumi ID">
          <el-input v-model="bgmId" placeholder="输入条目 ID 如 544109" clearable
                    @keyup.enter="submit"/>
        </el-form-item>
      </el-form>
      <div class="mapping-status">
        <div style="display: flex; justify-content: space-between; align-items: center;">
          <span style="font-size: 13px; color: #999;">映射数据状态</span>
          <el-button size="small" text @click="refreshMapping" :loading="mapRefreshing">
            <el-icon><Refresh /></el-icon>
          </el-button>
        </div>
        <div style="margin-top: 6px; font-size: 12px; line-height: 1.8;">
          <div>
            <span :style="{ color: mapStatus.bangumiDataExists ? '#67c23a' : '#f56c6c' }">
              {{ mapStatus.bangumiDataExists ? '✓' : '✗' }}
            </span>
            bangumi_data.json
            <span style="color: #999;">(bgm→mikan: {{ mapStatus.bgmToMikanCount || 0 }})</span>
          </div>
          <div>
            <span :style="{ color: mapStatus.animeIdsExists ? '#67c23a' : '#f56c6c' }">
              {{ mapStatus.animeIdsExists ? '✓' : '✗' }}
            </span>
            anime_ids.json
            <span style="color: #999;">(bgm→tvdb: {{ mapStatus.bgmToTvdbCount || 0 }})</span>
          </div>
        </div>
      </div>
      <div class="action">
        <el-button @click="submit" :loading="loading" text bg type="primary">确定</el-button>
      </div>
    </div>

    <div v-if="step === 2" style="min-height: 200px;">
      <div v-if="groups.length" v-loading="groupLoading">
        <div v-if="rssList.length" style="margin-bottom: 12px; text-align: right;">
          <el-button @click="batchAdd" text bg type="primary">
            批量添加 ({{ rssList.length }})
          </el-button>
        </div>
        <el-checkbox-group v-model="rssList">
          <div v-for="group in groups" class="group-card">
            <div style="display: flex; justify-content: space-between; align-items: center;">
              <el-checkbox :label="JSON.stringify(group)" style="margin-right: 12px;">
                <strong>{{ group.label }}</strong>
                <span style="margin-left: 8px; color: #999; font-size: 12px;">{{ group.updateDay }}</span>
              </el-checkbox>
              <el-button @click="selectGroup(group)" text bg type="primary" icon="Plus">
                添加
              </el-button>
            </div>
            <div v-if="group.tags?.size" style="margin-top: 6px; margin-left: 28px;">
              <el-tag v-for="tag in group.tags" size="small" style="margin: 2px;">{{ tag }}</el-tag>
            </div>
          </div>
        </el-checkbox-group>
      </div>
      <div v-else-if="!groupLoading" style="text-align: center; color: #999; padding: 40px;">
        未能获取到字幕组列表，请检查映射或手动输入 RSS 地址
      </div>
    </div>

    <el-dialog v-model="matchDialogVisible" title="选择匹配规则" width="auto" append-to-body>
      <div style="max-width: 500px; min-width: 200px; margin-bottom: 4px;">
        <el-radio-group v-model="selectedMatchIndex">
          <div v-for="(regexItems, index) in regexList" style="margin: 8px 0;">
            <el-radio :value="index">
              <el-tag v-if="regexItems.length" v-for="item in regexItems" size="small"
                      style="margin-right: 4px;">
                {{ item.label }}
              </el-tag>
              <el-tag v-else type="success">全部</el-tag>
            </el-radio>
          </div>
        </el-radio-group>
      </div>
      <div class="action">
        <el-button @click="confirmMatch" text bg type="primary" icon="Check">确定</el-button>
      </div>
    </el-dialog>

    <div v-if="step === 3">
      <Ani v-model:ani="ani" @callback="addAni"/>
    </div>
  </el-dialog>
</template>

<script setup>
import {ref} from "vue";
import {ElMessage} from "element-plus";
import {Refresh} from "@element-plus/icons-vue";
import * as http from "@/js/http.js";
import Ani from "./Ani.vue";
import {aniData} from "@/js/ani.js";

const dialogVisible = ref(false);
const loading = ref(false);
const groupLoading = ref(false);
const mapRefreshing = ref(false);
const step = ref(1);

const mapStatus = ref({
  bangumiDataExists: false,
  animeIdsExists: false,
  bgmToTvdbCount: 0,
  bgmToMikanCount: 0
});

const bgmId = ref("");
const ani = ref(aniData);
const groups = ref([]);

const rssList = ref([]);
const matchDialogVisible = ref(false);
const selectedMatchIndex = ref(0);
const regexList = ref([]);
const selectedGroup = ref(null);

let loadMappingStatus = () => {
  http.mappingStatus()
      .then(res => {
        mapStatus.value = res.data;
      })
      .catch(() => {});
};

let refreshMapping = () => {
  mapRefreshing.value = true;
  http.refreshTvdbMapping()
      .then(() => {
        ElMessage.success("正在刷新映射数据");
      })
      .finally(() => {
        mapRefreshing.value = false;
        setTimeout(() => loadMappingStatus(), 3000);
      });
};

let show = () => {
  bgmId.value = "";
  groups.value = [];
  rssList.value = [];
  step.value = 1;
  dialogVisible.value = true;
  loadMappingStatus();
};

let submit = () => {
  if (!bgmId.value) {
    ElMessage.error("请输入 Bangumi ID");
    return;
  }
  loading.value = true;
  http.quickSubscribe(bgmId.value)
      .then(res => {
        let detailUrl = res.data;
        if (detailUrl) {
          groupLoading.value = true;
          http.mikanGroup(detailUrl)
              .then(r => {
                groups.value = r.data || [];
              })
              .catch(e => ElMessage.error(e))
              .finally(() => groupLoading.value = false);
        }
        step.value = 2;
      })
      .catch(e => {
        ElMessage.error(e);
      })
      .finally(() => {
        loading.value = false;
      });
};

let selectGroup = (group) => {
  selectedGroup.value = group;
  regexList.value = JSON.parse(JSON.stringify(group.regexList || []));
  regexList.value.push([]);
  selectedMatchIndex.value = regexList.value.length - 1;
  matchDialogVisible.value = true;
};

let batchAdd = () => {
  if (!rssList.value.length) return;
  let selected = rssList.value.map(s => JSON.parse(s));
  let first = selected[0];
  let rest = selected.slice(1);

  loading.value = true;
  let newAni = {
    ...aniData,
    url: first.rss,
    type: 'mikan',
    bgmId: bgmId.value,
    subgroup: first.label,
    match: [],
    standbyRssList: rest.length ? rest.map(o => ({
      label: o.label,
      url: o.rss,
      offset: 0
    })) : []
  };
  http.rssToAni(newAni)
      .then(res => {
        ani.value = res.data;
        step.value = 3;
      })
      .catch(e => ElMessage.error(e))
      .finally(() => loading.value = false);
};

let confirmMatch = () => {
  let group = selectedGroup.value;
  let match = regexList.value[selectedMatchIndex.value];
  loading.value = true;
  let newAni = {
    ...aniData,
    url: group.rss,
    type: 'mikan',
    bgmId: bgmId.value,
    match: JSON.parse(JSON.stringify(match)).map(s => `{{${group.label}}}:${s}`)
  };
  http.rssToAni(newAni)
      .then(res => {
        ani.value = res.data;
        ani.value.subgroup = group.label;
        matchDialogVisible.value = false;
        step.value = 3;
      })
      .catch(e => ElMessage.error(e))
      .finally(() => loading.value = false);
};

let addAni = (fun) => {
  http.addAni(ani.value)
      .then(res => {
        ElMessage.success(res.message);
        window.$reLoadList();
        dialogVisible.value = false;
      })
      .finally(fun);
};

defineExpose({show});
</script>

<style scoped>
.action {
  width: 100%;
  display: flex;
  justify-content: end;
  margin-top: 10px;
}

.mapping-status {
  margin: 12px 0;
  padding: 10px 14px;
  background: var(--el-fill-color-light);
  border-radius: var(--el-border-radius-base);
}

.group-card {
  border: 1px solid var(--el-border-color);
  border-radius: var(--el-border-radius-base);
  padding: 12px;
  margin-bottom: 8px;
}
</style>
