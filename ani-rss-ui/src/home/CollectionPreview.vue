<template>
  <el-dialog v-model="dialogVisible"
             center
             class="el-dialog-auto-width"
             title="合集预览">
    <div v-loading="loading" style="max-height: 600px;">
      <el-scrollbar>
        <div v-if="episodes.length" style="margin-bottom: 12px;">
          <el-text tag="b" size="small">正片 ({{ episodes.length }})</el-text>
          <el-table :data="episodes" size="small" stripe>
            <el-table-column label="标题" min-width="400" prop="title"/>
            <el-table-column label="重命名" min-width="280" prop="reName"/>
            <el-table-column label="集数" prop="episode" width="70"/>
            <el-table-column label="大小" min-width="100" prop="size"/>
          </el-table>
        </div>
        <div v-if="extras.length">
          <el-text tag="b" size="small" type="info">Extra ({{ extras.length }})</el-text>
          <el-table :data="extras" size="small" stripe>
            <el-table-column label="标题" min-width="400" prop="title"/>
            <el-table-column label="重命名" min-width="280" prop="reName"/>
            <el-table-column label="大小" min-width="100" prop="size"/>
          </el-table>
        </div>
      </el-scrollbar>
    </div>
    <div v-if="subgroup !== props.data.ani.subgroup && subgroup" style="margin-top:12px;">
      <el-alert close-text="应用" show-icon @close="closeAlert">
        <template #title>
          <div class="flex" style="width:100%;justify-content: space-between;">
            <span>
              检测到字幕组为 {{ subgroup }}
            </span>
          </div>
        </template>
      </el-alert>
    </div>
    <div class="action">
      <div>
        <span>共 {{ list.length }} 项 (正片 {{ episodes.length }} + Extra {{ extras.length }})</span>
      </div>
      <el-button bg icon="Close" text @click="dialogVisible = false">关闭</el-button>
    </div>
  </el-dialog>
</template>

<script setup>
import {computed, ref} from "vue";
import * as http from "@/js/http.js";

let dialogVisible = ref(false)
let loading = ref(false)

let list = ref([])

let subgroup = ref('')

let episodes = computed(() => list.value.filter(item => item.episode != null))
let extras = computed(() => list.value.filter(item => item.episode == null))

let show = () => {
  subgroup.value = ''
  dialogVisible.value = true
  loading.value = true
  http.bdPreviewCollection(props.data)
      .then(res => {
        list.value = res.data
        subgroup.value = getSubgroup()
      })
      .finally(() => {
        loading.value = false
      })
}

let getSubgroup = () => {
  if (!list.value) {
    return ''
  }

  let subgroups = list.value
      .map(item => item['title'])
      .map(item => item.match(/^\[(.+?)]/))
      .filter(item => item)
      .map(item => item[1])

  if (subgroups) {
    return subgroups[0]
  }

  return ''
}

let closeAlert = () => {
  props.data.ani.subgroup = subgroup.value
  show()
}

defineExpose({show})

let props = defineProps(['data'])
</script>
<style scoped>
.action {
  margin-top: 12px;
  display: flex;
  justify-content: space-between;
}
</style>
