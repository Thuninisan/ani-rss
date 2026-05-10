package ani.rss.service;

import ani.rss.commons.FileUtils;
import ani.rss.commons.GsonStatic;
import ani.rss.entity.TvdbSeasonInfo;
import ani.rss.util.basic.HttpReq;
import ani.rss.util.other.ConfigUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class TvdbMappingService {

    private static final String BANGUMI_DATA_URL = "https://unpkg.com/bangumi-data@0.3/dist/data.json";
    private static final String ANIME_IDS_URL = "https://cdn.jsdelivr.net/gh/Kometa-Team/Anime-IDs@master/anime_ids.json";
    private static final String BANGUMI_DATA_FILE = "bangumi_data.json";
    private static final String ANIME_IDS_FILE = "anime_ids.json";

    private volatile Map<String, TvdbSeasonInfo> bgmToTvdb = Map.of();
    private volatile Map<String, String> bgmToMikan = Map.of();

    // 反向索引，用于分割放送检测
    private volatile Map<String, String> bgmToAnidb = Map.of();
    private volatile Map<String, Set<String>> tvdbToAnidbs = Map.of();
    private volatile Map<String, List<String>> anidbToBgmList = Map.of();

    @PostConstruct
    public void init() {
        File configDir = ConfigUtil.getConfigDir();
        File bangumiFile = new File(configDir, BANGUMI_DATA_FILE);
        File animeIdsFile = new File(configDir, ANIME_IDS_FILE);
        if (bangumiFile.exists() && animeIdsFile.exists()) {
            rebuild();
        } else {
            log.info("映射数据文件不存在，正在下载...");
            try {
                syncFile(BANGUMI_DATA_URL, BANGUMI_DATA_FILE);
                syncFile(ANIME_IDS_URL, ANIME_IDS_FILE);
                rebuild();
            } catch (Exception e) {
                log.error("初始化映射数据失败", e);
            }
        }
    }

    @Scheduled(cron = "0 7 4 ? * 2")
    public void weeklySync() {
        log.info("开始同步映射数据...");
        try {
            syncFile(BANGUMI_DATA_URL, BANGUMI_DATA_FILE);
            syncFile(ANIME_IDS_URL, ANIME_IDS_FILE);
            rebuild();
            log.info("映射数据同步完成，共 {} 条映射", bgmToTvdb.size());
        } catch (Exception e) {
            log.error("映射数据同步失败", e);
        }
    }

    public synchronized void refresh() {
        try {
            syncFile(BANGUMI_DATA_URL, BANGUMI_DATA_FILE);
            syncFile(ANIME_IDS_URL, ANIME_IDS_FILE);
            rebuild();
            log.info("映射数据刷新完成，共 {} 条映射", bgmToTvdb.size());
        } catch (Exception e) {
            log.error("映射数据刷新失败", e);
            throw new RuntimeException(e);
        }
    }

    public Optional<TvdbSeasonInfo> getByBgmId(String bgmId) {
        if (StrUtil.isBlank(bgmId)) {
            return Optional.empty();
        }
        return Optional.ofNullable(bgmToTvdb.get(bgmId));
    }

    public Optional<String> getMikanIdByBgmId(String bgmId) {
        if (StrUtil.isBlank(bgmId)) {
            return Optional.empty();
        }
        return Optional.ofNullable(bgmToMikan.get(bgmId));
    }

    /**
     * 查询与给定 bgmId 同属一个 TVDB 剧集、同季的所有 bgmId (用于分割放送检测)
     *
     * @param bgmId Bangumi 条目 ID
     * @return season → [bgmId1, bgmId2, ...] (仅包含 size > 1 的季)
     */
    public Map<Integer, List<String>> getSameSeasonSiblings(String bgmId) {
        if (StrUtil.isBlank(bgmId)) {
            return Map.of();
        }
        String anidbId = bgmToAnidb.get(bgmId);
        if (anidbId == null) {
            return Map.of();
        }
        TvdbSeasonInfo selfInfo = bgmToTvdb.get(bgmId);
        if (selfInfo == null) {
            return Map.of();
        }
        String tvdbId = selfInfo.getTvdbId();
        Set<String> anidbIds = tvdbToAnidbs.get(tvdbId);
        if (anidbIds == null || anidbIds.size() <= 1) {
            // 如果只有一个 anidbId，检查该 anidbId 是否对应多个 bgmId (AniDB 未拆分但 Bangumi 拆了)
            List<String> bgmIds = anidbToBgmList.getOrDefault(anidbId, List.of());
            if (bgmIds.size() <= 1) {
                return Map.of();
            }
            Map<Integer, List<String>> result = new LinkedHashMap<>();
            for (String bid : bgmIds) {
                Integer season = bgmToTvdb.containsKey(bid)
                        ? bgmToTvdb.get(bid).getTvdbSeason() : 1;
                result.computeIfAbsent(season, k -> new ArrayList<>()).add(bid);
            }
            result.entrySet().removeIf(e -> e.getValue().size() <= 1);
            return result;
        }
        // 多个 anidbId: 收集所有 bgmId 并按 season 分组
        Map<Integer, List<String>> seasonToBgmIds = new LinkedHashMap<>();
        for (String aid : anidbIds) {
            List<String> bgmIds = anidbToBgmList.getOrDefault(aid, List.of());
            for (String bid : bgmIds) {
                TvdbSeasonInfo info = bgmToTvdb.get(bid);
                if (info == null) {
                    continue;
                }
                seasonToBgmIds.computeIfAbsent(info.getTvdbSeason(), k -> new ArrayList<>()).add(bid);
            }
        }
        seasonToBgmIds.entrySet().removeIf(e -> e.getValue().size() <= 1);
        return seasonToBgmIds;
    }

    public int size() {
        return bgmToTvdb.size();
    }

    public Map<String, Object> status() {
        File configDir = ConfigUtil.getConfigDir();
        Map<String, Object> map = new HashMap<>();
        map.put("bangumiDataExists", new File(configDir, BANGUMI_DATA_FILE).exists());
        map.put("animeIdsExists", new File(configDir, ANIME_IDS_FILE).exists());
        map.put("bgmToTvdbCount", bgmToTvdb.size());
        map.put("bgmToMikanCount", bgmToMikan.size());
        return map;
    }

    private void syncFile(String url, String fileName) {
        File configDir = ConfigUtil.getConfigDir();
        File file = new File(configDir, fileName);
        File temp = new File(configDir, fileName + ".tmp");

        log.info("下载 {} ...", url);
        HttpReq.get(url)
                .then(res -> {
                    FileUtil.del(temp);
                    FileUtil.writeFromStream(res.bodyStream(), temp, true);
                    FileUtils.move(temp.toPath(), file.toPath());
                });
        log.info("下载完成 {}", fileName);
    }

    private synchronized void rebuild() {
        File configDir = ConfigUtil.getConfigDir();
        File bangumiFile = new File(configDir, BANGUMI_DATA_FILE);
        File animeIdsFile = new File(configDir, ANIME_IDS_FILE);

        if (!bangumiFile.exists() || !animeIdsFile.exists()) {
            log.warn("映射数据文件缺失，跳过重建索引");
            return;
        }

        // 1. 解析 anime_ids.json: anidbId → TvdbSeasonInfo
        Map<String, TvdbSeasonInfo> anidbToTvdb = new HashMap<>();
        Map<String, Set<String>> newTvdbToAnidbs = new HashMap<>();
        String animeIdsJson = FileUtil.readUtf8String(animeIdsFile);
        Map<String, JsonObject> animeIdsMap = GsonStatic.GSON.fromJson(
                animeIdsJson,
                new TypeToken<Map<String, JsonObject>>() {}.getType()
        );
        if (animeIdsMap != null) {
            for (Map.Entry<String, JsonObject> entry : animeIdsMap.entrySet()) {
                String anidbId = entry.getKey();
                JsonObject obj = entry.getValue();
                JsonElement tvdbIdEl = obj.get("tvdb_id");
                JsonElement tvdbSeasonEl = obj.get("tvdb_season");
                JsonElement tvdbEpoffsetEl = obj.get("tvdb_epoffset");

                if (tvdbIdEl == null || tvdbIdEl.isJsonNull()) {
                    continue;
                }

                String tvdbId = tvdbIdEl.getAsString();
                TvdbSeasonInfo info = new TvdbSeasonInfo()
                        .setTvdbId(tvdbId)
                        .setTvdbSeason(tvdbSeasonEl != null && !tvdbSeasonEl.isJsonNull()
                                ? tvdbSeasonEl.getAsInt() : 1)
                        .setTvdbEpisodeOffset(tvdbEpoffsetEl != null && !tvdbEpoffsetEl.isJsonNull()
                                ? tvdbEpoffsetEl.getAsInt() : 0);
                anidbToTvdb.put(anidbId, info);
                newTvdbToAnidbs.computeIfAbsent(tvdbId, k -> new HashSet<>()).add(anidbId);
            }
        }
        log.debug("加载 anime_ids 映射: {} 条", anidbToTvdb.size());

        // 2. 解析 bangumi_data.json: bgmId → anidbId
        String bangumiJson = FileUtil.readUtf8String(bangumiFile);
        JsonObject bangumiRoot = GsonStatic.GSON.fromJson(bangumiJson, JsonObject.class);
        JsonArray bangumiArray = bangumiRoot.getAsJsonArray("items");

        Map<String, TvdbSeasonInfo> newMap = new HashMap<>();
        Map<String, String> newBgmToMikan = new HashMap<>();
        Map<String, String> newBgmToAnidb = new HashMap<>();
        Map<String, List<String>> newAnidbToBgmList = new HashMap<>();
        if (bangumiArray != null) {
            for (JsonElement element : bangumiArray) {
                JsonObject item = element.getAsJsonObject();
                JsonArray sites = item.getAsJsonArray("sites");
                if (sites == null) {
                    continue;
                }

                String bgmId = null;
                String anidbId = null;
                String mikanId = null;
                for (JsonElement siteEl : sites) {
                    JsonObject site = siteEl.getAsJsonObject();
                    JsonElement siteNameEl = site.get("site");
                    JsonElement idEl = site.get("id");
                    if (siteNameEl == null || idEl == null) {
                        continue;
                    }
                    String siteName = siteNameEl.getAsString();
                    String id = idEl.getAsString();
                    if ("bangumi".equals(siteName)) {
                        bgmId = id;
                    } else if ("anidb".equals(siteName)) {
                        anidbId = id;
                    } else if ("mikan".equals(siteName)) {
                        mikanId = id;
                    }
                }

                if (bgmId != null && mikanId != null) {
                    newBgmToMikan.put(bgmId, mikanId);
                }

                if (bgmId == null || anidbId == null) {
                    continue;
                }

                newBgmToAnidb.put(bgmId, anidbId);
                newAnidbToBgmList.computeIfAbsent(anidbId, k -> new ArrayList<>()).add(bgmId);

                TvdbSeasonInfo tvdbInfo = anidbToTvdb.get(anidbId);
                if (tvdbInfo != null) {
                    newMap.put(bgmId, tvdbInfo);
                }
            }
        }

        bgmToTvdb = Map.copyOf(newMap);
        bgmToMikan = Map.copyOf(newBgmToMikan);
        bgmToAnidb = Map.copyOf(newBgmToAnidb);
        tvdbToAnidbs = Collections.unmodifiableMap(
                newTvdbToAnidbs.entrySet().stream()
                        .collect(Collectors.toMap(Map.Entry::getKey, e -> Set.copyOf(e.getValue()))));
        anidbToBgmList = Collections.unmodifiableMap(
                newAnidbToBgmList.entrySet().stream()
                        .collect(Collectors.toMap(Map.Entry::getKey, e -> List.copyOf(e.getValue()))));
        log.info("重建映射索引完成: {} 条 bgmId → TvdbSeason, {} 条 bgmId → MikanId 映射", bgmToTvdb.size(), bgmToMikan.size());
    }
}
