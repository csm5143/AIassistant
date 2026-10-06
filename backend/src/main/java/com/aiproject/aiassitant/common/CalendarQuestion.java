package com.aiproject.aiassitant.common;

import java.util.regex.Pattern;

/** Standalone calendar questions. Document dates, mixed tasks and weather are not included. */
public final class CalendarQuestion {
    private CalendarQuestion() {}
    private static final String START="(?:请问|请告诉我|告诉我)?\\s*(今天|今日|现在|当前|明天|明日|昨天|昨日)";
    private static final String END="(?:[？?。!！]|呢|呀|啊|\\s)*";
    private static final Pattern DATE=Pattern.compile("(?i)^"+START+"(?:是)?(?:几月几[号日]|几[号日]|星期几|周几|什么日期|日期|(?:什么|啥)日子)"+END+"$");
    private static final Pattern ENGLISH=Pattern.compile("(?i)^(what(?:'s| is) (?:the )?date(?: today)?|what day is (?:it|today))(?:[?.!])?$");
    private static final Pattern CALENDAR=Pattern.compile("(?i)^"+START+"(?:是)?(?:(?:什么|啥)(?:节日|节假日|纪念日)|农历(?:几月几[日号]|几[日号]|日期|多少)|(?:的)?农历日期(?:是什么)?)"+END+"$");
    public static boolean systemDate(String question){return question!=null&&(DATE.matcher(question.trim()).matches()||ENGLISH.matcher(question.trim()).matches());}
    public static boolean ordinary(String question){return systemDate(question)||question!=null&&CALENDAR.matcher(question.trim()).matches();}
    public static int offset(String question){
        var match=DATE.matcher(question==null?"":question.trim());if(!match.matches())return 0;
        return switch(match.group(1)){case "明天","明日"->1;case "昨天","昨日"->-1;default->0;};
    }
}
