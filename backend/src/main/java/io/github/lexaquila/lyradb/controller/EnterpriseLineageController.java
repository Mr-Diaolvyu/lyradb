package io.github.lexaquila.lyradb.controller;

import io.github.lexaquila.lyradb.model.dto.ErDiagram;
import io.github.lexaquila.lyradb.service.EnterpriseLineageService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/ent/lineage")
public class EnterpriseLineageController {
    private final EnterpriseLineageService lineage;
    public EnterpriseLineageController(EnterpriseLineageService lineage) { this.lineage = lineage; }

    @PostMapping
    public ErDiagram explore(@RequestBody EnterpriseLineageService.Request request) throws Exception {
        return lineage.explore(request);
    }
}
