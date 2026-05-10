package ani.rss.controller;

import ani.rss.annotation.Auth;
import ani.rss.entity.web.Result;
import ani.rss.service.TvdbMappingService;
import cn.hutool.core.thread.ThreadUtil;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.annotation.Resource;
import java.util.Map;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TvdbMappingController extends BaseController {

    @Resource
    private TvdbMappingService tvdbMappingService;

    @Auth
    @Operation(summary = "映射数据状态")
    @PostMapping("/mappingStatus")
    public Result<Map<String, Object>> mappingStatus() {
        return Result.success(tvdbMappingService.status());
    }

    @Auth
    @Operation(summary = "刷新映射数据")
    @PostMapping("/refreshTvdbMapping")
    public Result<Void> refresh() {
        ThreadUtil.execute(() ->
                tvdbMappingService.refresh()
        );
        return Result.success("已开始刷新映射数据");
    }
}
