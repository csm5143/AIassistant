package com.aiproject.aiassitant.module.documentqa.controller;

import com.aiproject.aiassitant.common.*;
import com.aiproject.aiassitant.module.documentqa.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/document-qa/sessions/{id}/statistics")
@RequiredArgsConstructor
public class DocumentStatisticsController {
    private final DocumentQaService service;
    @GetMapping("/tables")
    public R<Map<String,Object>> tables(@PathVariable String id) {
        var session=service.getSessionOrThrow(id);List<Map<String,Object>> tables=new ArrayList<>();
        for(var t:DocumentTableStatistics.tables(session)){Map<String,Object> item=new LinkedHashMap<>();item.put("id",t.id());item.put("title",t.title());item.put("headers",t.headers());item.put("rowCount",t.rows().size());item.put("malformedRows",t.malformedRows());item.put("issues",t.issues());item.put("sample",t.rows().stream().limit(3).map(DocumentTableStatistics.Row::cells).toList());tables.add(item);}
        return R.ok(Map.of("sourceVersion",session.sourceVersion,"tables",tables,"scope","selectedParsedTables"));
    }
    @PostMapping
    public R<DocumentTableStatistics.Result> calculate(@PathVariable String id,@RequestBody DocumentTableStatistics.Plan plan) {
        return R.ok(DocumentTableStatistics.calculate(service.getSessionOrThrow(id),plan));
    }
    @GetMapping("/rows/{tableId}/{row}")
    public R<Map<String,Object>> source(@PathVariable String id,@PathVariable String tableId,@PathVariable int row,@RequestParam String version) {
        var session=service.getSessionOrThrow(id);if(!session.sourceVersion.equals(version))throw new BizException(409,"文档版本已改变");
        var table=DocumentTableStatistics.tables(session).stream().filter(t->t.id().equals(tableId)).findFirst().orElseThrow(()->BizException.notFound("表格不存在"));
        var record=table.rows().stream().filter(r->r.number()==row).findFirst().orElseThrow(()->BizException.notFound("记录不存在"));
        Map<String,Object> source=new LinkedHashMap<>();source.put("fileName",session.fileName);source.put("tableId",tableId);source.put("row",row);source.put("page",record.page());source.put("headers",table.headers());source.put("cells",record.cells());source.put("text",session.source.text().substring(record.start(),record.end()));source.put("startOffset",record.start());source.put("endOffset",record.end());source.put("sourceVersion",version);return R.ok(source);
    }
}
