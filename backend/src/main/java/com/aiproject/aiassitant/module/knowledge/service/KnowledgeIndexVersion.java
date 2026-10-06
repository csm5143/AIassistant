package com.aiproject.aiassitant.module.knowledge.service;
import org.springframework.stereotype.Component;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
@Component
public class KnowledgeIndexVersion {
    private final ConcurrentHashMap<String,Long> versions=new ConcurrentHashMap<>();
    private final AtomicLong sequence=new AtomicLong();
    private volatile long floor;
    public long current(String user){return versions.getOrDefault(user,floor);}
    public synchronized void changed(String user){if(user==null)return;if(versions.size()>4096){floor=sequence.incrementAndGet();versions.clear();}versions.put(user,sequence.incrementAndGet());}
}
