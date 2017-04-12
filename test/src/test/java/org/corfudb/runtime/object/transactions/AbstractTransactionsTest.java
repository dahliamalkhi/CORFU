package org.corfudb.runtime.object.transactions;

import com.google.common.reflect.TypeToken;
import org.corfudb.runtime.object.AbstractObjectTest;
import org.corfudb.runtime.view.AbstractViewTest;
import org.junit.Before;

/**
 * Created by dmalkhi on 1/4/17.
 */
public class AbstractTransactionsTest extends AbstractObjectTest {
    @Before
    public void becomeCorfuApp() {         getDefaultRuntime(); }

    /**
     * Utility method to start a (default type) TX
     */
    protected void TXBegin() {
        getRuntime().getObjectsView().TXBuild()
                .begin();
    }

    /**
     * Utility method to end a TX
     */
    protected void TXEnd() {
        getRuntime().getObjectsView().TXEnd();
    }


    protected void TXAbort() {
        getRuntime().getObjectsView().TXAbort();
    }

    /**
     * Utility method to start an optimistic TX
     */
    protected void OptimisticTXBegin() {
        getRuntime().getObjectsView().TXBuild()
                .setType(TransactionType.OPTIMISTIC)
                .begin();
    }

    /**
     * Utility method to start a snapshot TX
     */
    protected void SnapshotTXBegin() {
        // By default, begin a snapshot at address 2L
        getRuntime().getObjectsView().TXBuild()
                .setType(TransactionType.SNAPSHOT)
                .setSnapshot(2L)
                .begin();
    }

    /**
     * Utility method to start a write-write TX
     */
    protected void WWTXBegin() {
        getRuntime().getObjectsView().TXBuild()
                .setType(TransactionType.WRITE_AFTER_WRITE)
                .begin();
    }

}
