package com.dataocean.module.fieldsemantic.event;

import com.dataocean.module.fieldsemantic.service.FieldSemanticService;
import com.dataocean.module.metadata.event.MetadataSnapshotCollectedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Creates explicit pending field bindings only after the complete collected snapshot commits. */
@Slf4j
@Component
@RequiredArgsConstructor
public class FieldSemanticSnapshotListener {
    private final FieldSemanticService fieldSemanticService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSnapshotCollected(MetadataSnapshotCollectedEvent event) {
        try {
            fieldSemanticService.bindCollectedSnapshot(event.datasourceId(), event.snapshotId(), event.triggeredBy());
        } catch (Exception exception) {
            log.warn("新快照字段语义来源绑定初始化失败 datasourceId={} snapshotId={}",
                    event.datasourceId(), event.snapshotId(), exception);
        }
    }
}
