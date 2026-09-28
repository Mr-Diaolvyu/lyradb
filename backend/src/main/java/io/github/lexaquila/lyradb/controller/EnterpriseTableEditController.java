package io.github.lexaquila.lyradb.controller;

import io.github.lexaquila.lyradb.model.dto.TableEditRequest;
import io.github.lexaquila.lyradb.service.EnterpriseTableEditService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/ent/table-edits")
public class EnterpriseTableEditController {
    private final EnterpriseTableEditService service;

    public EnterpriseTableEditController(EnterpriseTableEditService service) {
        this.service = service;
    }

    @GetMapping("/snapshot")
    public Map<String, Object> snapshot(@RequestParam String grantedSourceName,
                                        @RequestParam String schema,
                                        @RequestParam String table) throws Exception {
        return service.snapshot(grantedSourceName, schema, table);
    }

    @PostMapping("/requests")
    public Map<String, Object> request(@RequestBody TableEditRequest request) throws Exception {
        return service.request(request);
    }

    @PostMapping("/{approvalId}/execute")
    public Map<String, Object> execute(@PathVariable String approvalId) throws Exception {
        return service.execute(approvalId);
    }
}
