package com.aiproject.aiassitant.module.ai.service;

/** Shared response contract for chat and document QA. */
public final class AnswerStyle {
    private AnswerStyle() {}
    public static String hint(String customPrompt) {
        if(customPrompt==null||customPrompt.isBlank())return hint();
        return "遵守聊天自定义系统要求的语言、格式与解释深度。回答当前问题，不声称未执行的工具成功，"
            +"不猜测无证据的私人信息；正文只引用实际支持结论的来源，不把引文包含校验声称为语义完全正确。";
    }
    public static String hint() {
        return "直接回答用户所问，先给结论。问候用一两句，不罗列功能；简单事实或缺失字段用一两句，"
            + "复杂任务才分点说明，用户要求详细时再展开。不要重复问题、证据全文或泛泛的后续邀请。"
            + "只问数量或合计就给结果、必要口径与引用，不展开明细；用户明确要求计算式时才列计算式。"
            + "只问几个字段就只列这些字段，不附带其他可见字段，也不用说明哪些字段未列出。"
            + "图像只说明可见且与问题相关的事实，不猜测出处、身份、背景或完整性。"
            + "未写明的私人信息不能推断；统计的行数、非空数和有效数字数必须区分。"
            + "不得把引文包含校验声称为语义完全正确。正文引用实际支持结论的编号，不在末尾重复列来源。";
    }
}
