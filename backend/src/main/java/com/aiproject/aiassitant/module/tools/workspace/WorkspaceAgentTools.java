package com.aiproject.aiassitant.module.tools.workspace;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;
import java.util.List;

/** Schemas for the session-aware dispatcher; never execute without a validated session. */
@Component
public class WorkspaceAgentTools {
    @Tool("查看当前对话 Excel/CSV 的工作表、列名、行数、数值类型和样例。分析前必须先调用，第一行是表头。")
    public String inspectTable(@P("当前工具文件的真实 fileId")String fileId,@P("工作表名称；空字符串使用第一张表")String sheet){return dispatch();}
    @Tool("确定性表格分析，生成 Excel 和图表。先 inspectTable 获取真实列名。planJson 示例：{\"groupBy\":[\"类别\"],\"aggregations\":[{\"column\":\"金额\",\"op\":\"sum\",\"alias\":\"总支出\"}],\"sortBy\":\"总支出\",\"sortDirection\":\"desc\",\"chartType\":\"bar\"}。支持 filters:[{column,op,value}]，op 为 eq/ne/contains/gt/gte/lt/lte/empty/notEmpty；select:[列名]、deduplicate:true、limit；聚合 op 为 sum/avg/min/max/count/countNumeric；count 计非空值（包括非数字），count(*)计行数，有效金额笔数必须用 countNumeric；dateGroup:{column,unit:month/year/day}。chartType 为 bar/line/none。没有聚合时返回筛选后明细。空值和非数字不参与数值统计，错误数量会返回 audit。禁止自己估计数字。")
    public String analyzeTable(@P("真实 fileId")String fileId,@P("JSON 字符串分析方案，必须使用实际列名")String planJson){return dispatch();}
    @Tool("生成可下载的正式 Word/PDF 报告。markdown 是完整正文，支持标题、列表、代码和最多8列的表格。仅使用用户提供或已核实的事实；缺失的计划、风险、结论等省略或写‘未提供’，不得用‘暂无’‘无风险’‘正常’代替未知，建议必须标明。可嵌入本对话生成的 PNG 图表。")
    public String generateDocument(@P("报告标题")String title,@P("Markdown 报告正文，最多60000字")String markdown,@P("输出格式数组：docx、pdf 或两者")List<String>formats,@P("要嵌入的真实 PNG 图表 fileId 数组，不需要时为空数组")List<String>figureIds){return dispatch();}
    @Tool("按页读取当前对话 PDF/DOCX/TXT/MD 原文，返回原文位置、总页数和 nextPage。每次最多6份、每份最多6页、合计最多18页。PDF 使用既有增强解析并保留物理页码；其他格式为文本段而非页码。truncated=true 或 nextPage>0 时不能断言全文没有某信息。")
    public String readDocuments(@P("真实文件编号数组")List<String>fileIds,@P("起始页或文本段，从1开始")int pageStart,@P("每份读取页数，1到6，建议3")int maxPages){return dispatch();}
    @Tool("将多份文档指定字段汇总为 Excel，并逐项核验原文。recordsJson 格式：[{\"fileId\":\"真实编号\",\"values\":{\"字段名\":{\"value\":\"原文值\",\"page\":1,\"quote\":\"包含值的原文短句\"}}}]。必须先 readDocuments 获取证据；值逐字来自引文，quote 在该页。缺失字段用空字符串，不猜测。错误值留空并标记失败，遗漏文件仍输出空行。")
    public String exportExtractedFields(@P("需汇总的全部文件编号数组，最多12份")List<String>fileIds,@P("结果文件标题")String title,@P("字段名数组，最多12项")List<String>fields,@P("包含原文值、页码和引文的 JSON 数组字符串")String recordsJson){return dispatch();}
    @Tool("处理当前对话 PDF，生成下载文件。operation: MERGE 按文件顺序合并；EXTRACT 提取指定页；SPLIT 按页码组拆分；ROTATE 旋转指定页。每个输出最多500页。交互表单或签名PDF不支持；输出不含书签、附件和批注。")
    public String pdfTools(@P("MERGE、EXTRACT、SPLIT 或 ROTATE")String operation,@P("真实PDF编号数组，只有合并允许多份")List<String>fileIds,@P("页码从1开始，如1-3,5；SPLIT如1-3;4-6；空字符串表示全部页")String pages,@P("旋转角度90、180、270或-90；其他操作传90")int rotation){return dispatch();}
    private String dispatch(){throw new IllegalStateException("文件工具必须通过当前对话执行器调用");}
}
