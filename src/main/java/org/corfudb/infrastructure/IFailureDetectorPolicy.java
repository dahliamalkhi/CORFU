package org.corfudb.infrastructure;

import org.corfudb.runtime.CorfuRuntime;
import org.corfudb.runtime.view.Layout;

import java.util.HashMap;

/**
 * Failure Detection Policies.
 * Created by zlokhandwala on 9/29/16.
 */
public interface IFailureDetectorPolicy {

    /**
     * Executes the policy which runs detecting failures.
     *
     * @param layout latest layout
     */
    void executePolicy(Layout layout, CorfuRuntime corfuRuntime);

    /**
     * Gets the server status from the last execution of the policy.
     *
     * @return A hash map containing servers mapped to their failure status.
     */
    HashMap<String, Boolean> getServerStatus();
}
