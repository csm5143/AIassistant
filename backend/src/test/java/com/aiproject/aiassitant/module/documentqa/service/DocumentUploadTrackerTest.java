package com.aiproject.aiassitant.module.documentqa.service;

import com.aiproject.aiassitant.common.BizException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DocumentUploadTrackerTest {
    @Test void reportsOwnedProgressAndDoesNotRevealForeignTasksOrReuseIds() {
        var tracker = new DocumentUploadTracker();
        tracker.start("upload-001", "owner");
        tracker.update("upload-001", new DocumentQaService.UploadProgress("EMBEDDING",32,49));
        assertEquals(32, tracker.get("upload-001","owner").completed());
        assertEquals(404, assertThrows(BizException.class, () -> tracker.get("upload-001","other")).getCode());
        assertEquals(409, assertThrows(BizException.class, () -> tracker.start("upload-001","owner")).getCode());
        assertEquals(400, assertThrows(BizException.class, () -> tracker.start("bad/path","owner")).getCode());
        tracker.update("upload-001", new DocumentQaService.UploadProgress("FAILED",0,0));
        assertEquals("FAILED",tracker.get("upload-001","owner").phase());
    }
}
