package org.corfudb.generator.operations.tx;

import org.corfudb.generator.distributions.Operations;
import org.corfudb.generator.operations.Operation;
import org.corfudb.generator.state.State;
import org.corfudb.generator.state.State.CorfuTablesGenerator;

import java.util.List;

/**
 * Abstract class for all transactional operations
 */
public abstract class AbstractTxOperation extends Operation {

    protected final Operations operations;
    protected final CorfuTablesGenerator tablesManager;

    public AbstractTxOperation(State state, Operation.Type operationType, Operations operations,
                               CorfuTablesGenerator tablesManager) {
        super(state, operationType);
        this.operations = operations;
        this.tablesManager = tablesManager;
    }

    protected void executeOperations() {
        int numOperations = state.getOperationCount().sample();
        List<Operation.Type> operationTypes = operations.sample(numOperations);

        for (Operation.Type opType : operationTypes) {
            if (opType == Type.TX_OPTIMISTIC || opType == Type.TX_SNAPSHOT || opType == Type.TX_NESTED) {
                continue;
            }

            Operation operation = operations.create(opType);
            operation.execute();
        }
    }

    protected long stopTx() {
        return tablesManager.getRuntime().getObjectsView().TXEnd();
    }

    protected void startOptimisticTx() {
        tablesManager.getRuntime().getObjectsView().TXBegin();
    }
}