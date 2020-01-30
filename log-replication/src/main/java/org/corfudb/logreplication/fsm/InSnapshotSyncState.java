package org.corfudb.logreplication.fsm;

import org.corfudb.logreplication.transmitter.SnapshotReader;

/**
 * A class that represents the in snapshot sync state of the Log Replication FSM.
 *
 * This is the state when full logs are being synced to the remote site, based on a snapshot timestamp.
 */
public class InSnapshotSyncState implements LogReplicationState {

    LogReplicationContext context;

    public InSnapshotSyncState(LogReplicationContext context) {
        this.context = context;
    }

    @Override
    public LogReplicationState processEvent(LogReplicationEvent event) {
        switch (event.getType()) {
            // Case where another snapshot (full) sync is requested.
            case SNAPHOT_SYNC_REQUEST:
                // Add logic to cancel previous snapshot sync
                return new InSnapshotSyncState(context);
            case SNAPSHOT_SYNC_CANCEL:
                return new InRequireSnaphotSyncState(context);
            case  TRIMMED_EXCEPTION:
                return new InRequireSnaphotSyncState(context);
            case LEADERSHIP_LOST:
                return new InitializedState(context);
            case START_LOG_ENTRY_SYNC:
                return new InLogEntrySyncState(context);
            case LOG_REPLICATION_STOP:
                return new StoppedState(context);
            default: {
                // Log unexpected LogReplicationEvent when in InSnapshotSyncState
            }
        }
        return this;
    }

    @Override
    public void onEntry(LogReplicationState from) {
        // Execute snapshot transaction for every table to be replicated
        SnapshotReader snapshotReader = new SnapshotReader(context);
        try {
            context.getBlockingOpsScheduler().submit(snapshotReader::sync);
        } catch (Throwable t) {
            // Log Error
        }
    }

    @Override
    public void onExit(LogReplicationState to) {

    }

    @Override
    public LogReplicationStateType getType() {
        return LogReplicationStateType.IN_SNAPSHOT_SYNC;
    }
}
