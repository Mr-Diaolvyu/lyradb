package io.github.lexaquila.lyradb.controller;

import io.github.lexaquila.lyradb.service.GrantService;
import io.github.lexaquila.lyradb.service.SecurityUtil;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 用户侧授权（逻辑数据源，不含连接信息）
 */
@RestController
@RequestMapping("/grants")
public class GrantController {

    private final GrantService grantService;
    private final SecurityUtil securityUtil;

    public GrantController(GrantService grantService, SecurityUtil securityUtil) {
        this.grantService = grantService;
        this.securityUtil = securityUtil;
    }

    /** 我被授权的数据源（逻辑视图，无 dataSourceId） */
    @GetMapping("/mine")
    public List<Map<String, Object>> mine() {
        String uid = securityUtil.currentUserId();
        if (uid == null) throw new RuntimeException("未登录");
        return grantService.listMine(uid);
    }
}
