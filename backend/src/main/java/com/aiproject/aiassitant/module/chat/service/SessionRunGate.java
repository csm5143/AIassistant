package com.aiproject.aiassitant.module.chat.service;
import com.aiproject.aiassitant.common.BizException;
import org.springframework.stereotype.Service;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
@Service public class SessionRunGate {
    private final Set<String> running=ConcurrentHashMap.newKeySet();
    public void claim(String session){if(!running.add(session))throw new BizException(409,"当前聊天有任务执行中，请等待完成或停止回答");}
    public void release(String session){running.remove(session);}
    public boolean isRunning(String session){return running.contains(session);}
}
