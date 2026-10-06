package com.aiproject.aiassitant.module.knowledge.service;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
/** Bounded cache with TTL and shared in-flight loads. Values must be immutable. */
public final class BoundedTtlCache<K,V> {
    private record Entry<V>(V value,long expires){}
    public record Hit<V>(V value,boolean cached){}
    private final int capacity;
    private final long ttlNanos;
    private final LinkedHashMap<K,Entry<V>> entries=new LinkedHashMap<>(16,.75f,true);
    private final ConcurrentHashMap<K,CompletableFuture<V>> loading=new ConcurrentHashMap<>();
    public BoundedTtlCache(int capacity,java.time.Duration ttl){this.capacity=capacity;this.ttlNanos=ttl.toNanos();}
    public Hit<V> get(K key,Supplier<V> supplier){return get(key,supplier,value->true);}
    public Hit<V> get(K key,Supplier<V> supplier,java.util.function.Predicate<V> cacheable){
        synchronized(entries){var found=entries.get(key);if(found!=null){if(found.expires()>System.nanoTime())return new Hit<>(found.value(),true);entries.remove(key);}}
        var future=new CompletableFuture<V>();var other=loading.putIfAbsent(key,future);
        if(other!=null)return new Hit<>(other.join(),true);
        try{
            V value=supplier.get();
            if(cacheable.test(value))synchronized(entries){entries.put(key,new Entry<>(value,System.nanoTime()+ttlNanos));while(entries.size()>capacity)entries.remove(entries.keySet().iterator().next());}
            future.complete(value);return new Hit<>(value,false);
        }catch(RuntimeException|Error e){future.completeExceptionally(e);throw e;}finally{loading.remove(key,future);}
    }
    public void clear(){synchronized(entries){entries.clear();}}
    int size(){synchronized(entries){return entries.size();}}
}
