package com.aiproject.aiassitant.module.ai.service;

import com.aiproject.aiassitant.common.BizException;
import com.aiproject.aiassitant.module.chat.entity.ChatMessage;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.*;

/** Local, source-grounded explicit facts; never treats assistant output as confirmed memory. */
public final class ConversationMemoryEngine {
    private ConversationMemoryEngine() {}
    public static final int HISTORY_LIMIT=20_000, ACTIVE_LIMIT=128, CONTEXT_BUDGET=3600;
    public static class State {
        public int extractionVersion;
        public LinkedHashSet<String> ambiguousMessages=new LinkedHashSet<>();
        public boolean enabled=true;
        public LinkedHashSet<String> seen=new LinkedHashSet<>(),blockedMessages=new LinkedHashSet<>(),suppressedKeys=new LinkedHashSet<>(),ignoredRecall=new LinkedHashSet<>();
        public List<Item> items=new ArrayList<>();
        public long sequence;
        public boolean historyTruncated,capacityReached;
        public Long scanCursor;
        public int indexedMessages;
    }
    public static class Item {
        public String id,key,label,value,category,sourceMessageId,quote,origin,status="ACTIVE",supersededBy;
        public boolean pinned;
        public long sequence;
        public LocalDateTime sourceTime;
    }
    public record Extracted(String label,String value,String category,String key,String quote) {}
    public record Context(String text,List<Item> entries,List<ChatMessage> recalled,int chars,boolean truncated) {}
    private static final Pattern UNSAFE=Pattern.compile("(?i)api[ _-]?key|password|密码|密钥|secret|token\\s*[:=]|sk-[a-z0-9]{12,}");
    private static final Pattern UNCONFIRMED=Pattern.compile("(?i)假设|假如|例如|举例|示例|如果|可能|也许|大概|预计|待确认|暂定|不确定|引用|原文|文档写|文档内容|\\b(if|suppose|example|quoted|document says|maybe|might|perhaps)\\b|[?？]|```|[“”\"]");
    private static final Pattern QUESTION=Pattern.compile("(?i)回忆|请问|是什么|有哪些|多少|能否|是否|记得|记不记得|告诉我|吗[。！!]?\\s*$|\\b(what|which|why|how|recall|remind)\\b");
    private static final String VALUE="((?:[^。；;\\n，,]|(?<=\\d),(?=\\d))+)";
    private static final Pattern ASSIGN=Pattern.compile("(?:^|[。；;\\n，,])\\s*(?:请记住[：:]?\\s*|更正[：:]?\\s*|更新[：:]?\\s*)?([\\p{L}\\p{N}_ -]{1,32})\\s*[：:=]\\s*"+VALUE);
    private static final Pattern KNOWN_LABEL=Pattern.compile("(?i).*(?:预算|负责人|代号|编号|目标|约束|要求|已完成|下一步|待解决|待办|偏好|技术选型|budget|owner|project code|ticket).*",Pattern.CASE_INSENSITIVE);
    private static final String[][] NATURAL={
        {"预算","预算|budget","FACT"},{"负责人","负责人|owner","FACT"},{"项目代号","项目代号|项目编号|代号|project code","FACT"},
        {"工单编号","工单编号|工单|ticket","FACT"},{"技术选型","我们(?:这次)?(?:采用|使用)|技术选型(?:是|为)?|we (?:use|adopt)","FACT"},
        {"目标","(?:我们的|本次|这次)?目标","TASK"},{"下一步","下一步|接下来","TASK"},{"已完成","已完成|已经完成","TASK"},
        {"待解决","待解决|尚未解决","TASK"},{"回答偏好","回答偏好","PREFERENCE"}
    };
    private record NaturalRule(String label,Pattern pattern) {}
    private static final Pattern PROJECT=Pattern.compile("((?:(?!项目)[\\p{L}\\p{N}_-]){1,24}项目)(?!代号|编号)");
    private static final Map<String,List<String>> ALIASES=Map.of(
        "预算",List.of("预算","budget","经费上限","预算上限","花费上限","费用上限"),
        "负责人",List.of("负责人","owner","谁牵头","谁负责","由谁负责","牵头人"),
        "技术选型",List.of("技术选型","开发栈","技术栈","jdk"),
        "下一步",List.of("下一步","后续安排","接下来做什么"),
        "已完成",List.of("已完成","已经完成"),"待解决",List.of("待解决","尚未解决"));
    private static String cleanScope(String value){
        value=value.replaceFirst("^(?:(?:请)?记住|更正|更新|请问|请|回忆|之前|关于|我想知道|告诉我|我们(?:的)?|本聊天(?:的)?|比较|对比|分别|以及|与|和|及)+","");
        return Set.of("本项目","这个项目","那个项目","当前项目","本次项目","本聊天项目","本会话项目").contains(value)?"":value;
    }
    static String scope(String label){Matcher m=PROJECT.matcher(label);return m.find()&&m.start()==0?cleanScope(m.group(1)):"";}
    static String field(String label){String s=scope(label);return s.isEmpty()?key(label):key(label.substring(s.length()).replaceFirst("^的", ""));}
    private static String scopeAt(String content,int position){
        String prefix=content.substring(0,position);int start=Math.max(prefix.lastIndexOf('。'),prefix.lastIndexOf('\n'))+1;
        Matcher m=PROJECT.matcher(prefix.substring(start));String found="";while(m.find())found=cleanScope(m.group(1));return found;
    }
    private static String qualified(String label,String inherited){String own=scope(label);return own.isEmpty()?(inherited.isEmpty()?label:inherited+label):own+label.substring(label.indexOf("项目")+2).replaceFirst("^的","");}
    private static final List<NaturalRule> RULES=Arrays.stream(NATURAL).map(spec->new NaturalRule(spec[0],Pattern.compile("(?i)(?:"+spec[1]+")\\s*(?:改为|调整为|更新为|替换为|是|为|为此|is|to|[：:=])?\\s*"+VALUE))).toList();
    public static List<Extracted> extract(String content) {
        if(content==null||content.isBlank()||UNSAFE.matcher(content).find()||UNCONFIRMED.matcher(content).find()||QUESTION.matcher(content).find())return List.of();
        Map<String,Extracted> found=new LinkedHashMap<>();
        Matcher a=ASSIGN.matcher(content);
        boolean explicitRemember=content.matches("(?is)^(?:请)?(?:记住|remember).*" );
        while(a.find()){String label=a.group(1).trim(),value=a.group(2).trim();if(List.of("更正","记住","请记住","更新").contains(label)||!explicitRemember&&!KNOWN_LABEL.matcher(label).matches())continue;add(found,qualified(label,scopeAt(content,a.start(1))),value,a.group().trim());}
        // Known declarative phrases are intentionally conservative; arbitrary prose remains searchable original text.
        for(NaturalRule rule:RULES){Matcher m=rule.pattern().matcher(content);
            while(m.find()){String value=m.group(1).trim();String prefix=content.substring(0,m.start()),project=scopeAt(content,m.start());boolean explicitCorrection=content.startsWith("更正：")||content.startsWith("更正:");boolean ambiguousPrefix=m.start()>0&&Character.isLetter(content.charAt(m.start()-1))&&!explicitRemember&&project.isEmpty()&&!(explicitCorrection&&prefix.endsWith("的"));if(ambiguousPrefix||value.startsWith("的")||value.startsWith("吗")||value.isBlank()||found.values().stream().anyMatch(x->x.quote().contains(m.group().trim())))continue;add(found,qualified(rule.label(),project),value,m.group().trim());}}
        if(content.matches("(?s)^(?:请)?(?:以后|今后|始终|不要|不得|必须).*"))add(found,"约束",content.trim(),content.trim());
        if(explicitRemember&&found.isEmpty())add(found,"明确约定",content.trim(),content.trim());
        return found.values().stream().limit(8).toList();
    }
    private static void add(Map<String,Extracted> result,String label,String value,String quote){
        if(value.isBlank()||value.length()>500||label.length()>32)return;
        String normalized=key(label),category=category(label);
        if(field(label).equals("已完成")||label.equals("约束")||label.equals("明确约定"))normalized += ":"+digest(value.toLowerCase(Locale.ROOT));
        result.put(normalized,new Extracted(label,value,category,normalized,quote));
    }
    private static String digest(String value){try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    public static String key(String label){return switch(label.trim().toLowerCase(Locale.ROOT)){
        case "budget","预算"->"预算";case "owner","负责人"->"负责人";case "项目编号","代号","project code","项目代号"->"项目代号";
        case "ticket","工单","工单编号"->"工单编号";default->label.trim().toLowerCase(Locale.ROOT);};}
    public static String category(String label){if(label.matches(".*(?:目标|完成|下一步|待解决|待办|计划).*"))return "TASK";if(label.contains("偏好"))return "PREFERENCE";if(label.contains("约束")||label.contains("要求"))return "CONSTRAINT";return "FACT";}
    public static void ingest(State state,List<ChatMessage> messages){
        state.extractionVersion=2;
        // Replay and initial scans must agree with the incremental cursor even when a message is backdated.
        for(ChatMessage m:messages.stream().sorted(Comparator.comparing(ChatMessage::getMemorySeq,Comparator.nullsLast(Comparator.naturalOrder()))).toList()){if(!"user".equals(m.getRole())||m.getId()==null||!state.seen.add(m.getId()))continue;
            if(!state.enabled){state.ignoredRecall.add(m.getId());continue;}
            if(state.blockedMessages.contains(m.getId())||state.ignoredRecall.contains(m.getId()))continue;
            String content=m.getContent()==null?"":m.getContent().trim();
            Matcher forget=Pattern.compile("^(?:请)?(?:忘记|不要再记住)\\s*([^。！!]{1,32})[。！!]?$").matcher(content);
            if(forget.matches()){String k=key(forget.group(1));List<Item> affected=state.items.stream().filter(i->i.key.equals(k)&&i.status.equals("ACTIVE")).toList();for(Item item:affected)forget(state,item.id,messages);continue;}
            for(Extracted x:extract(content))if(!state.suppressedKeys.contains(x.key())){
                // Never infer which of several projects an unqualified update belongs to.
                if(scope(x.label()).isEmpty()&&content.matches("(?s).*(?:更正|改为|调整为|更新为|替换为).*")){
                    long projects=state.items.stream().filter(i->i.status.equals("ACTIVE")&&!scope(i.label).isEmpty()&&field(i.label).equals(field(x.label()))).map(i->scope(i.label)).distinct().count();
                    if(projects>1){state.ambiguousMessages.add(m.getId());state.ignoredRecall.add(m.getId());continue;}
                }
                Item current=active(state,x.key());if(current!=null&&current.value.equals(x.value()))continue;
                if(current==null&&state.items.stream().filter(i->i.status.equals("ACTIVE")).count()>=ACTIVE_LIMIT){state.capacityReached=true;continue;}
                Item item=new Item();item.id=UUID.randomUUID().toString();item.key=x.key();item.label=x.label();item.value=x.value();item.category=x.category();item.quote=x.quote();item.sourceMessageId=m.getId();item.sourceTime=m.getCreatedAt();item.origin="USER_MESSAGE";
                item.pinned=!x.category().equals("FACT")||x.label().equals("明确约定");put(state,item);
            }
        }
    }
    private static Item active(State state,String key){return state.items.stream().filter(i->i.status.equals("ACTIVE")&&i.key.equals(key)).findFirst().orElse(null);}
    private static void put(State state,Item item){Item prior=active(state,item.key);item.sequence=++state.sequence;if(prior!=null){prior.status="SUPERSEDED";prior.supersededBy=item.id;}state.items.add(item);}
    public static Item manual(State state,String label,String value,String category,boolean pinned,List<ChatMessage> messages){
        return manual(state,label,value,category,pinned,messages,null);
    }
    public static Item manual(State state,String label,String value,String category,boolean pinned,List<ChatMessage> messages,String replacingId){
        if(label==null||label.isBlank()||label.length()>32||value==null||value.isBlank()||value.length()>500||category==null||!List.of("FACT","TASK","PREFERENCE","CONSTRAINT").contains(category))throw new BizException(400,"标签1–32字、内容1–500字，类型需有效");
        if(UNSAFE.matcher(label+" "+value).find())throw new BizException(400,"请勿把密码或密钥写入记忆");
        String k=replacingId==null?key(label):state.items.stream().filter(i->i.id.equals(replacingId)&&i.status.equals("ACTIVE")).findFirst().orElseThrow(()->BizException.notFound("记忆不存在")).key;
        if(active(state,k)==null&&state.items.stream().filter(i->i.status.equals("ACTIVE")).count()>=ACTIVE_LIMIT)throw new BizException(400,"记忆已达128条，请先整理");
        Item prior=active(state,k);if(prior!=null)blockValues(state,List.of(prior),messages);
        state.suppressedKeys.remove(k);Item item=new Item();item.id=UUID.randomUUID().toString();item.key=k;item.label=label.trim();item.value=value.trim();item.category=category;item.pinned=pinned;item.origin="MANUAL";put(state,item);return item;
    }
    public static void forget(State state,String id,List<ChatMessage> messages){
        Item target=state.items.stream().filter(i->i.id.equals(id)).findFirst().orElseThrow(()->BizException.notFound("记忆不存在"));
        List<Item> lineage=state.items.stream().filter(i->i.key.equals(target.key)).toList();blockValues(state,lineage,messages);state.suppressedKeys.add(target.key);state.items.removeAll(lineage);
    }
    private static void blockValues(State state,List<Item> items,List<ChatMessage> messages){for(ChatMessage m:messages)if(items.stream().anyMatch(i->Objects.equals(i.sourceMessageId,m.getId())||containsValue(m.getContent(),i.value)&&sameScopeOrUnqualified(i,m.getContent())))state.blockedMessages.add(m.getId());}
    private static boolean sameScopeOrUnqualified(Item item,String text){String own=scope(item.label);if(own.isEmpty())return true;Set<String> scopes=queryScopes(text);return scopes.isEmpty()||scopes.stream().anyMatch(s->s.equalsIgnoreCase(own));}
    private static boolean containsValue(String text,String value){if(text==null)return false;if(text.contains(value))return true;Matcher n=Pattern.compile("^[\\d,.]+(?:元|美元|英镑|\\s*(?:CNY|USD|GBP))?$").matcher(value);if(n.matches()){String digits=value.replaceAll("[^\\d.]","");return !digits.isEmpty()&&text.replace(",","").contains(digits);}return text.replaceAll("\\s+","").contains(value.replaceAll("\\s+",""));}
    public static void clear(State state,List<ChatMessage> messages){messages.forEach(m->{state.blockedMessages.add(m.getId());if("user".equals(m.getRole()))state.seen.add(m.getId());});state.suppressedKeys.clear();state.items.clear();state.ambiguousMessages.clear();state.capacityReached=false;}
    public static List<ChatMessage> filter(State state,List<ChatMessage> messages){return messages.stream().filter(m->!state.blockedMessages.contains(m.getId())&&!state.ambiguousMessages.contains(m.getId())).toList();}
    /** Pure task-status lookup uses user records; planning and analysis continue through the model. */
    public static String taskRecall(State state,String query){
        if(!state.enabled||query==null||!query.matches("(?is).*(?:回忆|本聊天|本会话|之前|任务进度|任务状态|remember|recall).*"))return "";
        if(query.matches("(?is).*(?:如何|怎么|为什么|建议|分析|应该|帮我|设计|制定|生成|导出|比较|how|why|suggest|plan|compare).*"))return "";
        query=query.replace("已经完成","已完成").replace("尚未解决","待解决");
        List<String> fields=List.of("目标","已完成","下一步","待解决");boolean all=query.contains("任务进度")||query.contains("任务状态");
        boolean requested=false;for(String field:fields)requested|=query.contains(field);if(!all&&!requested)return "";
        // Only handle a pure task-status request. Mixed fact/document questions still need normal answering.
        String remainder=query.replaceAll("(?i)回忆|请|本聊天|本会话|我|我们|的|目标|已经完成的事项|已完成事项|已完成|已经完成|事项|下一步|待解决的问题|待解决|问题|任务进度|任务状态|是什么|有哪些|是什么|是多少|给出|列出|记录|没有|不要猜|只答|只给|只回答|不要猜测|之前|最初|及|和|与|同时|再|，|、|。|；|[\\s?？:：,;]","");
        if(!remainder.isBlank())return "";
        StringBuilder reply=new StringBuilder("根据本聊天的用户记录：\n");
        for(String label:fields){if(!all&&!query.contains(label))continue;List<Item> matches=state.items.stream().filter(i->i.status.equals("ACTIVE")&&field(i.label).equals(label)).toList();reply.append("\n- ").append(label).append("：");if(matches.isEmpty())reply.append("没有可用记录");else{int shown=0;for(Item item:matches){if(shown>=6||reply.length()+item.value.length()>3000){reply.append("；其余记录请打开本聊天记忆查看");break;}if(shown++>0)reply.append("；");if(!scope(item.label).isEmpty())reply.append(scope(item.label)).append("：");reply.append(item.value);}}}
        reply.append("\n\n可在“本聊天记忆”中核对来源或修改。以上是用户记录的状态。");return reply.toString();
    }
    public static Context context(State state,List<ChatMessage> all,List<ChatMessage> recent,String query){
        return context(state,all,recent,query,false);
    }
    public static Context context(State state,List<ChatMessage> all,List<ChatMessage> recent,String query,boolean independent){
        if(!state.enabled)return new Context("",List.of(),List.of(),0,state.historyTruncated);
        Set<String> recentIds=new HashSet<>();recent.forEach(m->recentIds.add(m.getId()));Set<String> terms=terms(query);
        // JDK questions may need a confirmed Java choice. This association retrieves
        // records; it does not assert that Java and JDK versions are interchangeable.
        // Keep original-message recall literal so aliases do not broaden uncertain prose.
        Set<String> factTerms=new HashSet<>(terms);Set<String> requestedFields=new HashSet<>();String normalized=query==null?"":query.toLowerCase(Locale.ROOT);
        ALIASES.forEach((canonical,aliases)->{if(aliases.stream().anyMatch(normalized::contains)){factTerms.addAll(terms(canonical));requestedFields.add(canonical);}});
        if(terms.contains("jdk"))factTerms.add("java");
        Set<String> requestedScopes=queryScopes(query);
        List<Item> ranked=new ArrayList<>(state.items.stream().filter(i->i.status.equals("ACTIVE")).toList());
        ranked.removeIf(i->!scope(i.label).isEmpty()&&!requestedScopes.isEmpty()&&requestedScopes.stream().noneMatch(s->s.equalsIgnoreCase(scope(i.label))));
        // Unscoped facts cannot supply a missing field for an explicitly named project.
        if(!requestedScopes.isEmpty())ranked.removeIf(i->scope(i.label).isEmpty()&&Set.of("FACT","TASK").contains(i.category)&&!i.label.equals("明确约定"));
        // An independent question needs preferences/constraints, not unrelated task progress.
        // Explicit freeform agreements and manually prioritized items remain available.
        if(independent)ranked.removeIf(i->!Set.of("PREFERENCE","CONSTRAINT").contains(i.category)
            &&!i.label.equals("明确约定")&&!(i.pinned&&"MANUAL".equals(i.origin)));
        ranked.sort(Comparator.<Item>comparingDouble(i->(i.pinned?4:0)+4*score(factTerms,i.label)+2*score(factTerms,i.value)).reversed().thenComparing(Comparator.comparingLong((Item i)->i.sequence).reversed()));
        boolean recallAll=query!=null&&query.matches("(?is).*(?:回忆|约定|记忆|之前|最初|项目|任务|remember|recall).*"),truncated=state.historyTruncated;
        StringBuilder text=new StringBuilder();List<Item> selected=new ArrayList<>();
        if(requestedScopes.isEmpty()&&ranked.stream().filter(i->requestedFields.contains(field(i.label))).map(i->scope(i.label)).filter(s->!s.isEmpty()).distinct().count()>1)
            text.append("- 当前问题未指定项目：以下记录分属不同项目，请分别标明归属；若需要唯一值，先询问项目名称，不得混用。\n");
        if(!state.ambiguousMessages.isEmpty()&&!requestedFields.isEmpty())text.append("- 有未指定项目的更正未写入事实；以各项目的明确记录为准。可在本聊天记忆中核对待确认原话。\n");
        for(Item i:ranked){if(!independent&&!i.pinned&&!recallAll&&score(factTerms,i.label+" "+i.value)<1)continue;String line="- "+i.label+"："+i.value+"（"+(i.sourceMessageId==null?"用户手动编辑":"来源消息 "+i.sourceMessageId)+"）\n";
            if(selected.size()>=16||text.length()+line.length()>2800){truncated=true;continue;}text.append(line);selected.add(i);}
        List<ChatMessage> candidates=(independent?java.util.stream.Stream.<ChatMessage>empty():all.stream()).filter(m->"user".equals(m.getRole())&&!recentIds.contains(m.getId())&&!state.blockedMessages.contains(m.getId())&&!state.ignoredRecall.contains(m.getId())&&m.getContent()!=null&&m.getContent().length()<=1000&&extract(m.getContent()).isEmpty()&&!UNSAFE.matcher(m.getContent()).find()&&(requestedScopes.isEmpty()||!queryScopes(m.getContent()).isEmpty()&&queryScopes(m.getContent()).stream().allMatch(s->requestedScopes.stream().anyMatch(q->q.equalsIgnoreCase(s))))&&score(terms,m.getContent())>=2).sorted(Comparator.<ChatMessage>comparingDouble(m->score(terms,m.getContent())).reversed().thenComparing(ChatMessage::getCreatedAt,Comparator.nullsLast(Comparator.reverseOrder()))).toList();
        List<ChatMessage> recalled=new ArrayList<>();for(ChatMessage m:candidates){String line="- 原话（消息 "+m.getId()+"）："+m.getContent()+"\n";if(recalled.size()>=4||text.length()+line.length()>CONTEXT_BUDGET){truncated=true;continue;}text.append(line);recalled.add(m);}
        return new Context(text.toString(),List.copyOf(selected),List.copyOf(recalled),text.length(),truncated||state.capacityReached);
    }
    static Set<String> queryScopes(String query){Set<String> result=new LinkedHashSet<>();if(query==null)return result;Matcher m=PROJECT.matcher(query);while(m.find()){String s=cleanScope(m.group(1));if(!s.isBlank())result.add(s);}return result;}
    /** One-time local re-extraction. Preserve IDs, manual edits, deletion tombstones and paused messages. */
    public static void upgrade(State state,List<ChatMessage> history){
        if(state.extractionVersion>=2)return;
        var rebuilt=new State();rebuilt.sequence=state.sequence;
        rebuilt.blockedMessages.addAll(state.blockedMessages);rebuilt.ignoredRecall.addAll(state.ignoredRecall);rebuilt.suppressedKeys.addAll(state.suppressedKeys);
        state.items.stream().filter(i->"MANUAL".equals(i.origin)).forEach(rebuilt.items::add);
        Set<String> protectedKeys=new HashSet<>(state.suppressedKeys);state.items.stream().filter(i->"MANUAL".equals(i.origin)&&"ACTIVE".equals(i.status)).map(i->i.key).forEach(protectedKeys::add);
        // A legacy global deletion/edit must not resurrect its old values under a new scoped label.
        for(ChatMessage m:history)for(Extracted x:extract(m.getContent()))if(protectedKeys.contains(field(x.label()))||protectedKeys.contains(x.key())){rebuilt.suppressedKeys.add(x.key());if(state.suppressedKeys.contains(field(x.label())))state.suppressedKeys.add(x.key());}
        // Blocking an entire source after editing one field must not erase its other active fields.
        // Reconstruct only already-active, unprotected fields; never reopen deleted source prose.
        Map<String,String> originalQuotes=new HashMap<>();List<ChatMessage> replay=new ArrayList<>();
        Map<String,List<Item>> activeSources=new HashMap<>();for(Item old:state.items)if("USER_MESSAGE".equals(old.origin)&&"ACTIVE".equals(old.status)&&old.sourceMessageId!=null)activeSources.computeIfAbsent(old.sourceMessageId,k->new ArrayList<>()).add(old);
        for(ChatMessage m:history){
            if(!state.blockedMessages.contains(m.getId())){replay.add(m);continue;}
            StringBuilder retained=new StringBuilder();
            for(Item old:activeSources.getOrDefault(m.getId(),List.of()))if(!protectedKeys.contains(old.key)&&!protectedKeys.contains(field(old.label))){
                var matches=extract(m.getContent()).stream().filter(x->field(x.label()).equals(field(old.label))&&x.value().equals(old.value)).toList();
                String label=matches.size()==1?matches.get(0).label():old.label;
                if(protectedKeys.contains(key(label)))continue;
                retained.append(label).append("：").append(old.value).append('\n');
                originalQuotes.put(m.getId()+"\0"+key(label)+"\0"+old.value,matches.size()==1?matches.get(0).quote():old.quote);
            }
            if(!retained.isEmpty()){var preserved=new ChatMessage();preserved.setId(m.getId());preserved.setRole("user");preserved.setContent(retained.toString());preserved.setCreatedAt(m.getCreatedAt());preserved.setMemorySeq(m.getMemorySeq());replay.add(preserved);rebuilt.blockedMessages.remove(m.getId());}
        }
        ingest(rebuilt,replay);rebuilt.blockedMessages.addAll(state.blockedMessages);
        for(Item fresh:rebuilt.items){String quote=originalQuotes.get(fresh.sourceMessageId+"\0"+key(fresh.label)+"\0"+fresh.value);if(quote!=null)fresh.quote=quote;}
        Map<String,String> ids=new HashMap<>();for(Item fresh:rebuilt.items){Item old=state.items.stream().filter(i->"MANUAL".equals(fresh.origin)?i.id.equals(fresh.id):Objects.equals(i.key,fresh.key)&&Objects.equals(i.sourceMessageId,fresh.sourceMessageId)&&Objects.equals(i.value,fresh.value)&&Objects.equals(i.origin,fresh.origin)).findFirst().orElse(null);ids.put(fresh.id,old==null?fresh.id:old.id);}
        for(Item old:state.items)if("USER_MESSAGE".equals(old.origin)){old.status="SUPERSEDED";old.supersededBy=rebuilt.items.stream().filter(i->Objects.equals(i.sourceMessageId,old.sourceMessageId)&&field(i.label).equals(field(old.label))).map(i->ids.get(i.id)).findFirst().orElse(null);}
        List<Item> merged=new ArrayList<>(state.items);for(Item fresh:rebuilt.items){String freshId=fresh.id;fresh.id=ids.get(freshId);if(fresh.supersededBy!=null)fresh.supersededBy=ids.getOrDefault(fresh.supersededBy,fresh.supersededBy);merged.removeIf(i->i.id.equals(fresh.id));merged.add(fresh);}
        state.items=merged;state.seen.addAll(rebuilt.seen);state.ignoredRecall.addAll(rebuilt.ignoredRecall);state.ambiguousMessages.addAll(rebuilt.ambiguousMessages);state.sequence=rebuilt.sequence;state.capacityReached|=rebuilt.capacityReached;state.extractionVersion=2;
    }
    // Unicode words + Chinese bigrams give exact identifiers and Chinese phrases a shared lexical index.
    static Set<String> terms(String text){Set<String> result=new HashSet<>();if(text==null)return result;Matcher words=Pattern.compile("[a-z0-9][a-z0-9_.-]+|[\\p{IsHan}]+",Pattern.CASE_INSENSITIVE).matcher(text.toLowerCase(Locale.ROOT));while(words.find()){String s=words.group();if(s.matches("[\\p{IsHan}]+")){for(int i=0;i<s.length()-1;i++){String term=s.substring(i,i+2);if(!Set.of("我们","这个","本聊","聊天","之前","什么","多少","回忆","只答","没有","项目","完成").contains(term))result.add(term);}}else if(!Set.of("the","this","what","remember","recall","only","from","with").contains(s))result.add(s);}return result;}
    static double score(Set<String> query,String text){Set<String> candidate=terms(text);double score=0;for(String t:query)if(candidate.contains(t))score+=t.matches(".*[a-z0-9].*")?3:1;return score;}
}
