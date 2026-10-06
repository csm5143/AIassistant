package com.aiproject.aiassitant.module.knowledge.service;

import com.aiproject.aiassitant.common.BizException;
import com.aiproject.aiassitant.module.knowledge.controller.KnowledgeFolderController;
import com.aiproject.aiassitant.module.knowledge.entity.*;
import com.aiproject.aiassitant.module.knowledge.mapper.*;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import java.io.RandomAccessFile;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KnowledgeFoldersTest {
    @TempDir Path temp;
    FolderPathService paths;
    KnowledgeStorageService storage;
    KnowledgeService knowledge;
    KbCollectionMapper collections;
    KbDocumentMapper documents;
    DocumentProcessingService processor;
    KnowledgeFolderService folders;
    KbCollection collection;
    Path source;
    List<KbDocument> indexed;

    @BeforeAll static void metadata() {
        var assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "folder-test");
        TableInfoHelper.initTableInfo(assistant, KbCollection.class);
        TableInfoHelper.initTableInfo(assistant, KbDocument.class);
    }
    @BeforeEach void setup() throws Exception {
        source = Files.createDirectory(temp.resolve("资料"));
        paths = new FolderPathService();
        ReflectionTestUtils.setField(paths, "enabled", true);
        ReflectionTestUtils.setField(paths, "allowedRoots", source.toString());
        ReflectionTestUtils.setField(paths, "storageDir", temp.resolve("managed").toString());
        collections = mock(KbCollectionMapper.class);
        documents = mock(KbDocumentMapper.class);
        knowledge = mock(KnowledgeService.class);
        processor = mock(DocumentProcessingService.class);
        storage = new KnowledgeStorageService(paths, collections);
        folders = new KnowledgeFolderService(paths, storage, knowledge, collections, documents, processor, new KnowledgeIndexVersion());
        collection = new KbCollection(); collection.setId("kb1"); collection.setUserId("owner");
        when(knowledge.getCollection("kb1")).thenReturn(collection);
        when(collections.selectById("kb1")).thenReturn(collection);
        indexed = new ArrayList<>();
        when(documents.selectList(any())).thenAnswer(invocation -> new ArrayList<>(indexed));
        doAnswer(invocation -> { indexed.add(invocation.getArgument(0)); return 1; }).when(documents).insert(any(KbDocument.class));
        doAnswer(invocation -> { indexed.removeIf(doc -> doc.getId().equals(invocation.getArgument(0))); return null; }).when(knowledge).deleteDocument(anyString());
    }
    Path file(String relative, String text) throws Exception {
        Path p=source.resolve(relative); Files.createDirectories(p.getParent()); Files.writeString(p,text); return p;
    }
    void ready() { indexed.forEach(doc -> doc.setStatus("READY")); }

    @Test void directoriesAreClosedByDefaultAndOutsidePathsStayClosed() {
        ReflectionTestUtils.setField(paths,"enabled",false);
        assertFalse(paths.browse(null).enabled());
        assertEquals(403,assertThrows(BizException.class,()->paths.validateDirectory(source.toString(),true)).getCode());
        ReflectionTestUtils.setField(paths,"enabled",true);
        assertEquals(403,assertThrows(BizException.class,()->paths.browse(temp.toString())).getCode());
        assertEquals(1,paths.browse(null).roots().size());
        assertNull(paths.browse(source.toString()).parent());
    }
    @Test void rejectsTraversalAndStoredPathSubstitution() throws Exception {
        for(String relative:List.of("../secret.txt","/secret.txt","a/../../secret.txt","C:/secret.txt","a//b.txt"))
            assertThrows(BizException.class,()->FolderPathService.validateRelativePath(relative));
        Path original=file("原文.txt","中文原文");
        assertEquals(original.toRealPath(),paths.resolveSource(source.toString(),"原文.txt",original.toString()));
        assertThrows(BizException.class,()->paths.resolveSource(source.toString(),"原文.txt",temp.resolve("outside.txt").toString()));
    }
    @Test void avoidsRecursiveIndexingOfManagedStorage() {
        ReflectionTestUtils.setField(paths,"storageDir",source.resolve("uploads").toString());
        assertEquals(400,assertThrows(BizException.class,()->paths.validateDirectory(source.toString(),true)).getCode());
    }
    @Test void importsSameNamedNestedFilesWithoutCollisionsAndSkipsNonDocuments() throws Exception {
        file("甲/说明.txt","甲资料"); file("乙/说明.txt","乙资料"); file("node_modules/隐藏.txt","不应收录"); file("照片.png","not an image");
        var result=folders.bind("kb1",source.toString());
        assertEquals(2,result.added());
        assertEquals(Set.of("甲/说明.txt","乙/说明.txt"),new HashSet<>(indexed.stream().map(KbDocument::getSourceRelativePath).toList()));
        assertEquals(2,indexed.stream().map(KbDocument::getSourceKey).distinct().count());
        assertTrue(indexed.stream().allMatch(doc->"FOLDER".equals(doc.getSourceKind())));
        verify(processor,times(2)).processDocument(anyString());
    }
    @Test void resyncDoesNotReindexUnchangedFilesButUpdatesChangedFiles() throws Exception {
        Path original=file("手册.txt","版本一"); folders.bind("kb1",source.toString()); ready(); clearInvocations(processor);
        var unchanged=folders.sync("kb1"); assertEquals(1,unchanged.unchanged()); verifyNoInteractions(processor);
        Files.writeString(original,"版本二"); var changed=folders.sync("kb1");
        assertEquals(1,changed.updated()); assertEquals(0,changed.added());
        assertEquals(FolderPathService.fingerprint(original),indexed.get(0).getSourceFingerprint());
        verify(processor).processDocument(indexed.get(0).getId());
    }
    @Test void synchronizationRemovesOnlyMissingRecordsAndLeavesOtherOriginals() throws Exception {
        Path removed=file("移除.txt","a"),kept=file("保留.txt","b"); folders.bind("kb1",source.toString());ready();
        Files.delete(removed); var result=folders.sync("kb1");
        assertEquals(1,result.removed()); assertEquals(1,result.unchanged());
        assertTrue(Files.exists(kept)); assertEquals(1,indexed.size());
    }
    @Test void inaccessibleRootNeverDeletesExistingIndex() throws Exception {
        Path original=file("保留.txt","a");folders.bind("kb1",source.toString());ready();
        Files.delete(original);Files.delete(source);
        assertThrows(BizException.class,()->folders.sync("kb1"));
        verify(knowledge,never()).deleteDocument(anyString());assertEquals(1,indexed.size());
    }
    @Test void deepIncompleteScanDoesNotRemoveExistingRecords() throws Exception {
        file("保留.txt","a");folders.bind("kb1",source.toString());ready();
        Path deep=source;for(int i=0;i<33;i++)deep=Files.createDirectory(deep.resolve("d"));
        assertThrows(BizException.class,()->folders.sync("kb1"));
        verify(knowledge,never()).deleteDocument(anyString());assertEquals(1,indexed.size());
    }
    @Test void busyChangedFilesAreSkippedWithoutReplacingProcessingFingerprint() throws Exception {
        Path original=file("忙碌.txt","a");folders.bind("kb1",source.toString());
        String fingerprint=indexed.get(0).getSourceFingerprint();Files.writeString(original,"b");clearInvocations(processor);
        var result=folders.sync("kb1");assertEquals(1,result.skipped());assertEquals(0,result.updated());
        assertEquals(fingerprint,indexed.get(0).getSourceFingerprint());verifyNoInteractions(processor);
    }
    @Test void oversizedChangedFileRetainsItsExistingRecord() throws Exception {
        Path original=file("过大.txt","a");folders.bind("kb1",source.toString());ready();
        try(var out=new RandomAccessFile(original.toFile(),"rw")){out.setLength(FolderPathService.MAX_FILE_SIZE+1);}
        var result=folders.sync("kb1");assertEquals(1,result.skipped());assertEquals(0,result.removed());
        assertEquals(1,indexed.size());verify(knowledge,never()).deleteDocument(anyString());
    }
    @Test void failedFilesCanBeRetriedBySynchronization() throws Exception {
        file("重试.txt","a");folders.bind("kb1",source.toString());indexed.get(0).setStatus("FAILED");clearInvocations(processor);
        assertEquals(1,folders.sync("kb1").updated());verify(processor).processDocument(anyString());
    }
    @Test void previewDetectsChangesAndDeletingBoundRecordDoesNotDeleteSource() throws Exception {
        Path original=file("原件.txt","a");folders.bind("kb1",source.toString());KbDocument doc=indexed.get(0);
        assertEquals("a",new String(storage.readBytes(doc),java.nio.charset.StandardCharsets.UTF_8));
        Files.writeString(original,"b");assertEquals(409,assertThrows(BizException.class,()->storage.previewFile(doc)).getCode());
        storage.deleteCopy(doc);assertEquals("b",Files.readString(original));
    }
    @Test void unbindingCopiesFilesIntoManagedStorageAndKeepsOriginals() throws Exception {
        Path original=file("原件.txt","保留原文");folders.bind("kb1",source.toString());ready();KbDocument doc=indexed.get(0);
        folders.unbind("kb1");
        Path copy=storage.base().resolve(doc.getId()).resolve("原件.txt");
        assertEquals("保留原文",Files.readString(copy));assertEquals("保留原文",Files.readString(original));
        assertNotEquals(original.toRealPath(),copy.toRealPath());
    }
    @Test void unbindingWaitsForProcessingAndDoesNotCopyChangingFile() throws Exception {
        Path original=file("忙碌.txt","a");folders.bind("kb1",source.toString());
        assertEquals(409,assertThrows(BizException.class,()->folders.unbind("kb1")).getCode());
        ready();Files.writeString(original,"b");assertEquals(409,assertThrows(BizException.class,()->folders.unbind("kb1")).getCode());
        assertFalse(Files.exists(storage.base()));assertTrue(Files.exists(original));
    }
    @Test void regularFolderImportSavesSafeCopyAndDeletesOnlyThatDocument() throws Exception {
        var upload=new MockMultipartFile("file","../说明.txt","text/plain","说明".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Path saved=storage.saveUpload("doc1",upload);assertTrue(saved.startsWith(storage.base()));assertEquals("说明.txt",saved.getFileName().toString());
        storage.saveBytes("doc2","说明.txt",new byte[]{1});
        KbDocument doc=new KbDocument();doc.setId("doc1");doc.setStoragePath(saved.toString());doc.setSourceKind("IMPORT");
        assertEquals("说明",Files.readString(storage.resolveFile(doc)));storage.deleteCopy(doc);
        assertFalse(Files.exists(saved));assertTrue(Files.exists(storage.base().resolve("doc2/说明.txt")));
    }
    @Test void folderEndpointsRejectRemoteRequestsBeforeReadingDisk() {
        var controller=new KnowledgeFolderController(paths,folders);var request=new MockHttpServletRequest();request.setRemoteAddr("192.168.1.50");
        assertEquals(403,assertThrows(BizException.class,()->controller.browse(null,request)).getCode());
        assertNull(collection.getFolderPath());
    }
    @Test void unauthorizedCollectionIsRejectedBeforeBinding() throws Exception {
        file("资料.txt","a");when(knowledge.getCollection("kb1")).thenThrow(new BizException(404,"知识库不存在"));
        assertEquals(404,assertThrows(BizException.class,()->folders.bind("kb1",source.toString())).getCode());
        verifyNoInteractions(documents,collections,processor);assertTrue(indexed.isEmpty());
    }
}
