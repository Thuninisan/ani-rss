package ani.rss.util.other;

import ani.rss.commons.FileUtils;
import ani.rss.commons.GsonStatic;
import ani.rss.dto.RssToAniDTO;
import com.google.gson.JsonObject;
import ani.rss.entity.*;
import ani.rss.service.ClearService;
import ani.rss.service.DownloadService;
import ani.rss.service.MikanService;
import ani.rss.service.TvdbMappingService;
import ani.rss.util.basic.HttpReq;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.date.DatePattern;
import cn.hutool.core.date.DateTime;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.resource.ResourceUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.core.util.URLUtil;
import cn.hutool.crypto.SecureUtil;
import cn.hutool.extra.spring.SpringUtil;
import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import wushuo.tmdb.api.entity.Tmdb;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
public class AniUtil {

    public static final List<Ani> ANI_LIST = new CopyOnWriteArrayList<>();
    public static final String FILE_NAME = "ani.v2.json";

    /**
     * 获取订阅配置文件
     *
     * @return
     */
    public static File getAniFile() {
        File configDir = ConfigUtil.getConfigDir();
        return new File(configDir + File.separator + FILE_NAME);
    }

    /**
     * 加载订阅
     */
    public static void load() {
        File configFile = getAniFile();

        if (!configFile.exists()) {
            FileUtil.writeUtf8String(GsonStatic.toJson(ANI_LIST), configFile);
        }
        String s = FileUtil.readUtf8String(configFile);
        List<Ani> anis = GsonStatic.fromJsonList(s, Ani.class);

        CopyOptions copyOptions = CopyOptions
                .create()
                .setIgnoreNullValue(true)
                .setOverride(false);

        ANI_LIST.clear();
        for (Ani ani : anis) {
            Date releaseDate = ani.getReleaseDate();
            if (Objects.isNull(releaseDate)) {
                releaseDate = new Date();
                // 处理旧的日期数据
                try {
                    Integer year = ani.getYear();
                    Integer month = ani.getMonth();
                    Integer date = ani.getDate();
                    String format = StrUtil.format("{}-{}-{}", year, month, date);
                    releaseDate = DateUtil.parse(format, DatePattern.NORM_DATE_PATTERN);
                } catch (Exception ignored) {
                }
                ani.setReleaseDate(releaseDate);
            }

            // 自动修补缺失的封面
            String image = ani.getImage();
            saveJpg(image);

            Ani newAni = AniUtil.createAni();
            BeanUtil.copyProperties(newAni, ani, copyOptions);
            ANI_LIST.add(ani);
        }
        log.debug("加载订阅 共{}项", ANI_LIST.size());
    }

    /**
     * 将订阅配置保存到磁盘
     */
    public static synchronized void sync() {
        File configFile = getAniFile();
        log.debug("保存订阅 {}", configFile);
        try {
            String json = GsonStatic.toJson(ANI_LIST);
            File temp = new File(configFile + ".temp");
            FileUtil.del(temp);
            FileUtil.writeUtf8String(json, temp);
            FileUtils.move(temp.toPath(), configFile.toPath());
            log.debug("保存成功 {}", configFile);
        } catch (Exception e) {
            log.error("保存失败 {}", configFile);
            log.error(e.getMessage(), e);
        }
    }

    /**
     * 获取动漫信息
     *
     * @param dto
     * @return
     */
    public static Ani getAni(RssToAniDTO dto) {
        String url = dto.getUrl();
        String type = dto.getType();

        Assert.notBlank(url, "RSS地址 不能为空");

        type = StrUtil.blankToDefault(type, "mikan");
        url = URLUtil.decode(url.trim(), StandardCharsets.UTF_8);

        Ani ani = AniUtil.createAni();
        ani.setUrl(url);

        Map<String, String> paramMap = HttpUtil.decodeParamMap(url, StandardCharsets.UTF_8);

        switch (type) {
            case "mikan":
                try {
                    String subgroupId = MikanService.getSubgroupId(url);
                    MikanService.getMikanInfo(ani, subgroupId);
                } catch (Exception e) {
                    throw new RuntimeException("获取失败");
                }
                break;
            case "ani-bt":
                if (paramMap.containsKey("bgmId")) {
                    String bgmUrl = "https://bgm.tv/subject/" + paramMap.get("bgmId");
                    ani.setBgmUrl(bgmUrl);
                }

                String subgroup = dto.getSubgroup();
                if (paramMap.containsKey("groupSlug") && StrUtil.isBlank(subgroup)) {
                    subgroup = paramMap.get("groupSlug");
                }
                ani.setSubgroup(subgroup);
                break;
            case "anime-garden":
                if (paramMap.containsKey("subject")) {
                    String bgmUrl = "https://bgm.tv/subject/" + paramMap.get("subject");
                    ani.setBgmUrl(bgmUrl);
                }
                if (paramMap.containsKey("fansub")) {
                    ani.setSubgroup(paramMap.get("fansub"));
                }
                break;
            default:
                String bgmUrl = dto.getBgmUrl();
                ani.setBgmUrl(bgmUrl);
        }

        // DTO 提供了 bgmId 时覆盖 Mikan 返回的 bgmUrl，确保分割放送检测使用正确的 Bangumi ID
        if (StrUtil.isNotBlank(dto.getBgmId())) {
            ani.setBgmUrl("https://bgm.tv/subject/" + dto.getBgmId());
        }

        String bgmUrl = ani.getBgmUrl();
        String subgroup = ani.getSubgroup();

        Assert.notBlank(bgmUrl, "bgmUrl 不能为空");

        BgmInfo bgmInfo = BgmUtil.getBgmInfo(ani, true);

        BgmUtil.toAni(bgmInfo, ani);

        // 用 TVDB 的 season 覆盖 Bangumi 解析的 season
        SpringUtil.getBean(TvdbMappingService.class)
                .getByBgmId(bgmInfo.getId())
                .ifPresent(tvdb -> ani.setSeason(tvdb.getTvdbSeason()));

        

        Config config = ConfigUtil.CONFIG;

        // 只下载最新集
        Boolean downloadNew = config.getDownloadNew();
        // 默认启用全局排除
        Boolean enabledExclude = config.getEnabledExclude();
        // 默认导入全局排除
        Boolean importExclude = config.getImportExclude();
        // 全局排除
        List<String> exclude = config.getExclude();

        // 默认导入全局排除
        if (importExclude) {
            exclude = new ArrayList<>(exclude);
            exclude.addAll(ani.getExclude());
            exclude = exclude.stream().distinct().toList();
            ani.setExclude(exclude);
        }

        ani
                // 只下载最新集
                .setDownloadNew(downloadNew)
                // 是否启用全局排除
                .setGlobalExclude(enabledExclude)
                // type mikan or other
                .setType(type);

        subgroup = StrUtil.blankToDefault(subgroup, "未知字幕组");

        if (subgroup.equals("未知字幕组")) {
            List<Item> items = ItemsUtil.getItems(ani, url, subgroup);
            subgroup = ItemsUtil.getSubgroup(items);
        }

        ani.setSubgroup(subgroup);

        List<StandbyRss> standbyRssList = ani.getStandbyRssList();

        boolean copyMasterToStandby = config.getCopyMasterToStandby();
        boolean standbyRss = config.getStandbyRss();
        if (copyMasterToStandby && standbyRss) {
            StandbyRss copyStandbyRss = new StandbyRss()
                    .setUrl(url.trim())
                    .setOffset(0)
                    .setLabel(ani.getSubgroup());
            standbyRssList.add(copyStandbyRss);
        }

        log.debug("获取到动漫信息 {}", JSONUtil.formatJsonStr(GsonStatic.toJson(ani)));
        if (ani.getOva()) {
            return ani;
        }

        // 检测分割放送
        detectSplitCour(ani, bgmInfo.getId());

        return ani;
    }


    public static String saveJpg(String coverUrl) {
        return saveJpg(coverUrl, false);
    }

    /**
     * 保存图片
     *
     * @param coverUrl
     * @param isOverride 是否覆盖
     * @return
     */
    public static String saveJpg(String coverUrl, Boolean isOverride) {
        File configDir = ConfigUtil.getConfigDir();
        FileUtil.mkdir(configDir + "/files/");

        // 默认空图片
        String cover = "cover.png";
        if (!FileUtil.exist(configDir + "/files/" + cover)) {
            byte[] bytes = ResourceUtil.readBytes("image/cover.png");
            FileUtil.writeBytes(bytes, configDir + "/files/" + cover);
        }
        if (StrUtil.isBlank(coverUrl)) {
            return cover;
        }
        String filename = SecureUtil.md5(coverUrl);
        filename = filename.charAt(0) + "/" + filename + "." + FileUtil.extName(URLUtil.getPath(coverUrl));
        FileUtil.mkdir(configDir + "/files/" + filename.charAt(0));
        File file = new File(configDir + "/files/" + filename);
        if (file.exists() && !isOverride) {
            return filename;
        }
        FileUtil.del(file);
        try {
            HttpReq.get(coverUrl)
                    .then(res -> FileUtil.writeFromStream(res.bodyStream(), file));
            return filename;
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return cover;
        }
    }

    /**
     * 校验参数
     *
     * @param ani
     */
    public static void verify(Ani ani) {
        String url = ani.getUrl();
        List<String> exclude = ani.getExclude();
        Integer season = ani.getSeason();
        Integer offset = ani.getOffset();
        String title = ani.getTitle();
        Assert.notBlank(url, "RSS URL 不能为空");
        if (Objects.isNull(exclude)) {
            ani.setExclude(new ArrayList<>());
        }
        Assert.notNull(season, "季不能为空");
        Assert.notBlank(title, "标题不能为空");
        Assert.notNull(offset, "集数偏移不能为空");
    }


    /**
     * 获取蜜柑的bangumiId
     *
     * @param ani
     * @return
     */
    public static String getBangumiId(Ani ani) {
        String url = ani.getUrl();
        if (StrUtil.isBlank(url)) {
            return "";
        }
        Map<String, String> decodeParamMap = HttpUtil.decodeParamMap(url, StandardCharsets.UTF_8);
        return decodeParamMap.get("bangumiId");
    }


    /**
     * 订阅完结迁移
     *
     * @param ani
     */
    public static void completed(Ani ani) {
        ani = ObjectUtil.clone(ani);

        String title = ani.getTitle();
        Boolean completed = ani.getCompleted();
        boolean ova = ani.getOva();
        boolean enable = ani.getEnable();
        int currentEpisodeNumber = ani.getCurrentEpisodeNumber();
        int totalEpisodeNumber = ani.getTotalEpisodeNumber();

        if (!completed) {
            // 未开启
            return;
        }

        if (totalEpisodeNumber < 1) {
            // 总集数为空
            return;
        }

        if (currentEpisodeNumber < totalEpisodeNumber) {
            // 未完结
            return;
        }

        if (enable) {
            // 仍是启用的话 主RSS仍未完结
            return;
        }

        if (ova) {
            // 剧场版不进行迁移
            return;
        }

        Config config = ObjectUtil.clone(ConfigUtil.CONFIG);

        boolean autoDisabled = config.getAutoDisabled();
        if (!autoDisabled) {
            // 未开启自动禁用订阅
            return;
        }

        completed = config.getCompleted();
        if (!completed) {
            // 未开启
            return;
        }

        String completedPathTemplate = config.getCompletedPathTemplate();

        Boolean customCompleted = ani.getCustomCompleted();
        if (customCompleted) {
            // 自定义完结迁移
            completedPathTemplate = ani.getCustomCompletedPathTemplate();
        }

        if (StrUtil.isBlank(completedPathTemplate)) {
            // 路径为空
            return;
        }

        // 旧文件路径
        String oldPath = SpringUtil.getBean(DownloadService.class).getDownloadPath(ani, config);

        config.setDownloadPathTemplate(completedPathTemplate);
        // 因为临时修改下载位置模版以获取对应下载位置, 要关闭自定义下载位置
        ani.setCustomDownloadPath(false);

        // 新文件路径
        String newPath = SpringUtil.getBean(DownloadService.class).getDownloadPath(ani, config);

        if (!FileUtil.exist(oldPath)) {
            // 旧文件不存在
            return;
        }

        FileUtil.mkdir(newPath);

        List<TorrentsInfo> torrentsInfos = TorrentUtil.getTorrentsInfos();

        for (TorrentsInfo torrentsInfo : torrentsInfos) {
            String downloadDir = torrentsInfo.getDownloadDir();
            if (!downloadDir.equals(oldPath)) {
                // 旧位置不相同
                continue;
            }
            // 修改保存位置
            TorrentUtil.setSavePath(torrentsInfo, newPath);
        }

        if (!torrentsInfos.isEmpty()) {
            ThreadUtil.sleep(3000);
        }

        File[] files = FileUtils.listFiles(oldPath);

        log.info("订阅已完结 {}, 移动已完结文件共 {} 个", title, files.length);

        for (File file : files) {
            if (!file.exists()) {
                continue;
            }
            // 移动文件
            log.info("移动 {} ==> {}", file, newPath);
            FileUtil.move(file, new File(newPath), true);
            // 清理残留文件夹
            SpringUtil.getBean(ClearService.class).clearParentFile(file);
        }
    }

    public static Ani createAni() {
        Ani newAni = new Ani();
        Config config = ConfigUtil.CONFIG;
        return newAni
                .setId(UUID.randomUUID().toString())
                .setMikanTitle("")
                .setStandbyRssList(new ArrayList<>())
                .setOffset(0)
                .setReleaseDate(new DateTime())
                .setEnable(true)
                .setOva(false)
                .setScore(0.0)
                .setLastDownloadTime(0L)
                .setImage("")
                .setThemoviedbName("")
                .setCustomDownloadPath(false)
                .setDownloadPath("")
                .setGlobalExclude(false)
                .setCurrentEpisodeNumber(0)
                .setTotalEpisodeNumber(0)
                .setMatch(List.of())
                .setExclude(List.of("720[Pp]", "\\d-\\d", "合集", "特别篇"))
                .setBgmUrl("")
                .setSubgroup("")
                .setCustomEpisode(config.getCustomEpisode())
                .setCustomEpisodeStr(config.getCustomEpisodeStr())
                .setCustomEpisodeGroupIndex(config.getCustomEpisodeGroupIndex())
                .setOmit(true)
                .setDownloadNew(false)
                .setNotDownload(new ArrayList<>())
                .setTmdb(
                        new Tmdb()
                                .setId("")
                                .setName("")
                                .setDate(new Date())
                )
                .setUpload(config.getUpload())
                .setProcrastinating(true)
                .setCustomRenameTemplate(config.getRenameTemplate())
                .setCustomRenameTemplateEnable(false)
                .setCustomPriorityKeywordsEnable(false)
                .setCustomPriorityKeywords(new ArrayList<>())
                .setMessage(true)
                .setCustomUploadPathTarget("")
                .setCustomUploadEnable(false)
                .setCompleted(true)
                .setCustomCompleted(false)
                .setCustomCompletedPathTemplate("")
                .setCustomTags(new ArrayList<>())
                .setCustomTagsEnable(false);
    }

    /**
     * 自动推断剧集偏移
     * 通过对比 RSS 集号与 Bangumi 的 ep/sort 范围判断字幕组使用的编号方式
     */
    public static void autoDetectOffset(Ani ani) {
        autoDetectOffset(ani, BgmUtil.getSubjectId(ani));
    }

    private static void autoDetectOffset(Ani ani, String referenceBgmId) {
        Config config = ConfigUtil.CONFIG;
        if (!config.getOffset()) {
            return;
        }
        String url = ani.getUrl();
        if (StrUtil.isBlank(url)) {
            return;
        }
        int savedOffset = ani.getOffset();
        ani.setOffset(0);
        List<Item> items = ItemsUtil.getItems(ani);
        ani.setOffset(savedOffset);
        if (items.isEmpty()) {
            return;
        }
        int offset = determineOffset(ani, items, referenceBgmId);
        log.debug("自动获取到剧集偏移为 {}", offset);
        ani.setOffset(offset);
        ani.setBangumiOffset(offset);
        for (StandbyRss rss : ani.getStandbyRssList()) {
            rss.setOffset(offset);
        }
    }

    private static int determineOffset(Ani ani, List<Item> items, String referenceBgmId) {
        if (StrUtil.isBlank(referenceBgmId)) {
            return 0;
        }
        try {
            List<JsonObject> bgmEpisodes = BgmUtil.getEpisodes(referenceBgmId, 0);
            if (bgmEpisodes.isEmpty()) {
                return 0;
            }
            JsonObject first = bgmEpisodes.get(0);
            int ep = first.get("ep").getAsInt();
            int sort = first.get("sort").getAsInt();
            int offset = Math.min(ep - sort, 0);

            double minEpisode = items.stream()
                    .mapToDouble(Item::getEpisode)
                    .min()
                    .orElse(1);

            if (minEpisode + offset <= 0) {
                return 0;
            }
            return offset;
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return 0;
        }
    }

    /**
     * 检测分割放送并设置 offset: 通过 TVDB 聚类找到同季的多个 Bangumi 条目,
     * 按 Bangumi 最小绝对集号 (sort) 排序确定 Part 号,
     * 始终以 sort 最小的 Part1 作为参考调用 autoDetectOffset 进行 RSS 比对
     */
    private static void detectSplitCour(Ani ani, String bgmId) {
        TvdbMappingService tvdbMappingService = SpringUtil.getBean(TvdbMappingService.class);
        Map<Integer, List<String>> siblings = tvdbMappingService.getSameSeasonSiblings(bgmId);

        String part1BgmId = bgmId;

        if (!siblings.isEmpty()) {
            List<String> sameSeasonSiblings = siblings.get(ani.getSeason());
            if (sameSeasonSiblings != null && sameSeasonSiblings.size() > 1) {
                record SiblingInfo(String bgmId, int firstSort, int firstEp) {}
                List<SiblingInfo> infoList = new ArrayList<>();
                for (String siblingBgmId : sameSeasonSiblings) {
                    try {
                        List<JsonObject> episodes = BgmUtil.getEpisodes(siblingBgmId, 0);
                        if (!episodes.isEmpty()) {
                            JsonObject first = episodes.stream()
                                    .min(Comparator.comparingInt(e ->
                                            e.has("sort") ? e.get("sort").getAsInt() : e.get("ep").getAsInt()))
                                    .orElse(null);
                            if (first == null) {
                                continue;
                            }
                            int firstSort = first.has("sort") ? first.get("sort").getAsInt() : first.get("ep").getAsInt();
                            int firstEp = first.get("ep").getAsInt();
                            infoList.add(new SiblingInfo(siblingBgmId, firstSort, firstEp));
                        }
                    } catch (Exception e) {
                        log.error("获取 Bangumi {} 集数失败", siblingBgmId, e);
                    }
                }

                if (infoList.size() > 1) {
                    infoList.sort(Comparator.comparingInt(SiblingInfo::firstSort));
                    part1BgmId = infoList.get(0).bgmId;

                    for (int i = 0; i < infoList.size(); i++) {
                        if (infoList.get(i).bgmId.equals(bgmId)) {
                            int part = i + 1;
                            ani.setPart(part);
                            log.info("检测到分割放送: {} S{} Part{} (共{}个Part)",
                                    ani.getTitle(), ani.getSeason(), part, infoList.size());
                            break;
                        }
                    }
                }
            }
        }

        autoDetectOffset(ani, part1BgmId);
    }

}
