package org.corfudb.generator.operations;

import org.corfudb.generator.distributions.Keys;
import org.corfudb.generator.state.KeysState.ThreadName;
import org.corfudb.generator.state.State;
import org.corfudb.runtime.object.VloVersioningListener;

/**
 * Subscribes on version updates in VLO and updates the generator state accordingly
 */
public class UpdateVersionHandler {

    public void handle(State state) {
        VloVersioningListener.subscribe(ver -> {
            //update the state
            ThreadName threadName = ThreadName.buildFromCurrentThread();
            Keys.Version version = Keys.Version.build(ver);

            state.getKeysState().updateThreadLatestVersion(threadName, version);
        });
    }
}