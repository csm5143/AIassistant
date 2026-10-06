package com.aiproject.aiassitant.module.documentqa.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.aiproject.aiassitant.common.BizException;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Local durable state: each file is replaced atomically on the same volume. No credentials are stored. */
final class DocumentUploadJobStore {
    final Path root; private final ObjectMapper mapper;
    DocumentUploadJobStore(Path root,ObjectMapper mapper){this.root=root.toAbsolutePath().normalize();this.mapper=mapper;try{Files.createDirectories(this.root);}catch(IOException e){throw new IllegalStateException("无法创建文档任务目录",e);}}
    Path directory(String id){if(id==null||!id.matches("[A-Za-z0-9_-]{8,64}"))throw new BizException(400,"任务编号无效");Path path=root.resolve(id).normalize();if(!path.startsWith(root)||path.equals(root))throw new BizException(400,"任务路径无效");return path;}
    Path file(String id,String name){if(!List.of("job.json","input.bin","source.json","embeddings.json").contains(name))throw new IllegalArgumentException();return directory(id).resolve(name);}
    void create(String id){try{Files.createDirectory(directory(id));}catch(FileAlreadyExistsException e){throw new BizException(409,"任务编号已使用");}catch(IOException e){throw new IllegalStateException("无法保存文档任务",e);}}
    void write(String id,String name,Object data){try{Path target=file(id,name),tmp=target.resolveSibling(name+".part");mapper.writeValue(tmp.toFile(),data);try(var channel=java.nio.channels.FileChannel.open(tmp,StandardOpenOption.WRITE)){channel.force(true);}Files.move(tmp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(IOException e){throw new IllegalStateException("无法保存文档任务检查点",e);}}
    <T>T read(String id,String name,Class<T> type){Path path=file(id,name);if(!Files.exists(path))return null;try{return mapper.readValue(path.toFile(),type);}catch(IOException e){throw new IllegalStateException("文档任务检查点损坏",e);}}
    List<String> ids(){try(var entries=Files.list(root)){return entries.filter(Files::isDirectory).map(p->p.getFileName().toString()).filter(n->n.matches("[A-Za-z0-9_-]{8,64}")).toList();}catch(IOException e){throw new IllegalStateException("无法读取文档任务目录",e);}}
    void delete(String id){Path dir=directory(id);if(!Files.exists(dir))return;try(var paths=Files.walk(dir)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList()){if(!path.toAbsolutePath().normalize().startsWith(root))throw new IllegalStateException("任务清理路径越界");Files.deleteIfExists(path);}}catch(IOException e){throw new IllegalStateException("无法清理文档任务",e);}}
}
