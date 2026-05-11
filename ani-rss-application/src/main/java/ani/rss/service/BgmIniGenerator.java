package ani.rss.service;

import ani.rss.entity.Ani;
import ani.rss.util.other.BgmUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.Optional;

/**
 * bgm.ini 生成
 */
@Slf4j
@Service
public class BgmIniGenerator {

    /**
     * 生成 bgm.ini
     *
     * @param ani          订阅
     * @param downloadPath 下载目录
     * @param force        强制覆盖
     */
    public void generate(Ani ani, String downloadPath, Boolean force) {
        if (StrUtil.isBlank(downloadPath)) {
            return;
        }

        File iniFile = new File(downloadPath, "bangumi.ini");

        if (!Boolean.TRUE.equals(force) && iniFile.exists()) {
            return;
        }

        FileUtil.mkdir(iniFile.getParentFile());

        String bgmId = BgmUtil.getSubjectId(ani);
        int offset = Optional.ofNullable(ani.getOffset()).orElse(0);

        String iniContent = buildIniContent(bgmId, offset);

        FileUtil.writeUtf8String(iniContent, iniFile);
        log.info("已保存 bgm.ini {}", iniFile);
    }

    private String buildIniContent(String bgmId, int offset) {
        StringBuilder sb = new StringBuilder();
        sb.append("[Bangumi]\n");
        appendKey(sb, "id", bgmId);
        appendKey(sb, "offset", offset);
        return sb.toString();
    }

    /**
     * 添加键值对
     *
     * @param sb    StringBuilder
     * @param key   键
     * @param value 值
     */
    private void appendKey(StringBuilder sb, String key, Object value) {
        if (value == null) {
            return;
        }
        String s = value.toString();
        if (StrUtil.isBlank(s)) {
            return;
        }
        sb.append(key).append("=").append(s).append("\n");
    }
}
