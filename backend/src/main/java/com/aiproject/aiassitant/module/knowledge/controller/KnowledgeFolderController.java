package com.aiproject.aiassitant.module.knowledge.controller;
import com.aiproject.aiassitant.common.BizException;
import com.aiproject.aiassitant.common.R;
import com.aiproject.aiassitant.module.knowledge.service.*;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.net.InetAddress;
@RestController
@RequestMapping("/knowledge")
@RequiredArgsConstructor
public class KnowledgeFolderController {
    private final FolderPathService paths; private final KnowledgeFolderService folders;
    public record FolderRequest(String path){}
    private void local(HttpServletRequest request){
        try{if(!InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress())throw new BizException(403,"文件夹绑定仅供本机使用");}
        catch(java.net.UnknownHostException e){throw new BizException(403,"文件夹绑定仅供本机使用");}
    }
    @GetMapping("/local-folders") public R<FolderPathService.DirectoryListing> browse(@RequestParam(required=false)String path,HttpServletRequest request){local(request);return R.ok(paths.browse(path));}
    @PostMapping("/collections/{id}/folder") public R<KnowledgeFolderService.SyncResult> bind(@PathVariable String id,@RequestBody FolderRequest body,HttpServletRequest request){local(request);return R.ok(folders.bind(id,body.path()));}
    @PostMapping("/collections/{id}/folder/sync") public R<KnowledgeFolderService.SyncResult> sync(@PathVariable String id,HttpServletRequest request){local(request);return R.ok(folders.sync(id));}
    @DeleteMapping("/collections/{id}/folder") public R<Void> unbind(@PathVariable String id,HttpServletRequest request){local(request);folders.unbind(id);return R.ok();}
}
