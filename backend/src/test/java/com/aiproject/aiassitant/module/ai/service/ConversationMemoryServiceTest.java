package com.aiproject.aiassitant.module.ai.service;

import com.aiproject.aiassitant.common.BizException;
import com.aiproject.aiassitant.module.chat.entity.*;
import com.aiproject.aiassitant.module.chat.mapper.*;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ConversationMemoryServiceTest {
    final ConversationMemoryMapper rows=mock(ConversationMemoryMapper.class);
    final ChatMessageMapper messages=mock(ChatMessageMapper.class);
    final ChatSessionMapper sessions=mock(ChatSessionMapper.class);
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    final ConversationMemory row=new ConversationMemory();
    ConversationMemoryService service=new ConversationMemoryService(rows,messages,sessions,json);
    @BeforeAll static void tables(){TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(),"memory-cursor-test"),ChatMessage.class);}
    @BeforeEach void fixture(){
        var s=new ChatSession();s.setId("chat");s.setUserId("owner");when(sessions.selectById("chat")).thenReturn(s);
        row.setSessionId("chat");row.setOwnerId("owner");row.setVersion(0L);row.setStateJson("{}");when(rows.lock("chat","owner")).thenReturn(row);
        when(messages.memoryInitial(eq("chat"),anyInt())).thenReturn(List.of());when(messages.memoryDelta(eq("chat"),anyLong(),anyInt())).thenReturn(List.of());when(messages.selectList(any())).thenReturn(List.of());
    }
    ChatMessage message(long seq,String role,String text){var m=new ChatMessage();m.setId("m"+seq);m.setMemorySeq(seq);m.setRole(role);m.setContent(text);m.setCreatedAt(LocalDateTime.of(2026,10,2,0,0).plusSeconds(seq));return m;}
    @Test void legacyRecordsBootstrapOnceAndCursorSurvivesServiceReconstructionAndBackdatedCorrections()throws Exception {
        var first=message(1,"user","预算：8600元。");when(messages.memoryInitial("chat",20001)).thenReturn(List.of(first));
        service.prepare("chat","owner",List.of(first),"你好",true);verify(messages,times(1)).memoryInitial("chat",20001);
        service=new ConversationMemoryService(rows,messages,sessions,json);
        var correction=message(2,"user","更正：预算改为9100元。");correction.setCreatedAt(first.getCreatedAt().minusDays(1));
        when(messages.memoryDelta("chat",1L,20000)).thenReturn(List.of(correction));
        var next=service.prepare("chat","owner",List.of(correction),"预算是多少",false);
        assertTrue(next.prompt().contains("9100"));assertFalse(next.prompt().contains("8600"));assertEquals(1,next.metrics().get("indexRowsRead"));
        var saved=json.readValue(row.getStateJson(),ConversationMemoryEngine.State.class);assertEquals(2L,saved.scanCursor);assertEquals(2,saved.indexedMessages);
        verify(messages,times(1)).memoryInitial("chat",20001);
    }
    @Test void warmIndependentRequestReadsOnlyNewMessagesAndKeepsPreferences(){
        var preference=message(1,"user","以后使用英文回答。");when(messages.memoryInitial("chat",20001)).thenReturn(List.of(preference));
        service.prepare("chat","owner",List.of(preference),"你好",true);clearInvocations(messages);
        var assistant=message(2,"assistant","Hello");var question=message(3,"user","你好");when(messages.memoryDelta("chat",1L,20000)).thenReturn(List.of(assistant,question));
        var next=service.prepare("chat","owner",List.of(assistant,question),"你好",true);
        assertTrue(next.prompt().contains("英文"));assertEquals(2,next.metrics().get("indexRowsRead"));assertEquals(0,next.metrics().get("recallRowsRead"));
        verify(messages,never()).memoryInitial(anyString(),anyInt());verify(messages,never()).selectList(any());
    }
    @Test void pausedMessagesStayExcludedAfterIncrementalIndexingAndReenable()throws Exception {
        var state=new ConversationMemoryEngine.State();state.enabled=false;state.scanCursor=0L;row.setStateJson(json.writeValueAsString(state));
        var paused=message(1,"user","预算：100元");when(messages.memoryDelta("chat",0L,20001)).thenReturn(List.of(paused));
        service.prepare("chat","owner",List.of(paused),"你好",true);
        state=json.readValue(row.getStateJson(),ConversationMemoryEngine.State.class);assertTrue(state.ignoredRecall.contains("m1"));assertTrue(state.items.isEmpty());
        state.enabled=true;row.setStateJson(json.writeValueAsString(state));assertFalse(service.prepare("chat","owner",List.of(),"预算是多少",false).prompt().contains("100元"));
    }
    @Test void broadRawRecallFallsBackInsteadOfDroppingPotentialMatches()throws Exception {
        var state=new ConversationMemoryEngine.State();state.scanCursor=1000L;state.indexedMessages=600;row.setStateJson(json.writeValueAsString(state));
        List<ChatMessage> hits=new ArrayList<>();for(int i=1;i<=257;i++)hits.add(message(i,"user","发布分支是release-"+i));when(messages.selectList(any())).thenReturn(hits);
        var source=message(999,"user","发布分支名是 release-autumn-2026，验收在周五。");when(messages.memoryInitial("chat",20001)).thenReturn(List.of(source));
        assertTrue(service.prepare("chat","owner",List.of(),"发布分支名是什么",false).prompt().contains("release-autumn-2026"));verify(messages).memoryInitial("chat",20001);
    }
    @Test void ownershipIsCheckedBeforeAnyCursorOrHistoryRead(){
        assertThrows(BizException.class,()->service.prepare("chat","other",List.of(),"你好",true));verifyNoInteractions(rows,messages);
    }
    @Test void legacyScopedExtractionUpgradesOnceThenReturnsToIncrementalReads()throws Exception{
        var first=message(1,"user","云帆项目的预算是9100元。");var second=message(2,"user","海棠项目的预算是20000元。");
        var state=new ConversationMemoryEngine.State();ConversationMemoryEngine.ingest(state,List.of(message(1,"user","预算：9100元。"),message(2,"user","预算：20000元。")));state.extractionVersion=0;state.scanCursor=2L;state.indexedMessages=2;row.setStateJson(json.writeValueAsString(state));
        when(messages.memoryInitial("chat",20001)).thenReturn(List.of(first,second));
        var prepared=service.prepare("chat","owner",List.of(),"云帆项目经费上限是多少？");assertTrue(prepared.prompt().contains("云帆项目预算：9100元"));assertFalse(prepared.prompt().contains("海棠项目"));assertEquals(2,prepared.metrics().get("indexRowsRead"));
        clearInvocations(messages);service.prepare("chat","owner",List.of(),"云帆项目预算");verify(messages,never()).memoryInitial(anyString(),anyInt());
    }
    @Test void crossingHistoryCapRetainsFullBoundedHistoryForManagementAndMarksTruncation()throws Exception {
        var state=new ConversationMemoryEngine.State();state.scanCursor=19999L;state.indexedMessages=19999;row.setStateJson(json.writeValueAsString(state));
        when(messages.memoryDelta("chat",19999L,2)).thenReturn(List.of(message(20000,"user","你好"),message(20001,"user","额外消息")));
        var old=message(1,"user","预算：9100元");when(messages.memoryInitial("chat",20001)).thenReturn(List.of(old));
        service.clear("chat","owner",0L);
        var saved=json.readValue(row.getStateJson(),ConversationMemoryEngine.State.class);
        assertTrue(saved.historyTruncated);assertEquals(20000,saved.indexedMessages);assertTrue(saved.blockedMessages.contains("m1"));assertTrue(saved.items.isEmpty());
    }
}
