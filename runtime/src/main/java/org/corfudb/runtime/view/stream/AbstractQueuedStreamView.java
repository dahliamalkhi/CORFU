package org.corfudb.runtime.view.stream;

import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.corfudb.protocols.wireprotocol.DataType;
import org.corfudb.protocols.wireprotocol.ILogData;
import org.corfudb.runtime.CorfuRuntime;
import org.corfudb.runtime.view.Address;

import javax.annotation.Nonnull;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** The abstract queued stream view implements a stream backed by a read queue.
 *
 * A read queue is a priority queue where addresses can be inserted, and are
 * dequeued in ascending order. Subclasses implement the fillReadQueue()
 * function, which defines how the read queue should be filled, and the
 * read() function, which reads an entry and updates the pointers for the
 * stream view.
 *
 * The addresses in the read queue must be global addresses.
 *
 * This implementation does not handle bulk reads and depends on IStreamView's
 * implementation of remainingUpTo(), which simply calls nextUpTo() under a lock
 * until it returns null.
 *
 * Created by mwei on 1/6/17.
 */
@Slf4j
public abstract class AbstractQueuedStreamView extends
        AbstractContextStreamView<AbstractQueuedStreamView
                .QueuedStreamContext> {

    /** Create a new queued stream view.
     *
     * @param streamID  The ID of the stream
     * @param runtime   The runtime used to create this view.
     */
    public AbstractQueuedStreamView(final CorfuRuntime runtime,
                                    final UUID streamID) {
        super(runtime, streamID, QueuedStreamContext::new);
    }

    /** Add the given address to the resolved queue of the
     * given context.
     * @param context           The context to add the address to
     * @param globalAddress     The resolved global address.
     */
    protected void addToResolvedQueue(QueuedStreamContext context,
                                      long globalAddress,
                                      ILogData ld) {
        context.resolvedQueue.add(globalAddress);
        context.resolvedEstBytes += ld.getSizeEstimate();

        if (context.maxResolution < globalAddress)
        {
            context.maxResolution = globalAddress;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected ILogData getNextEntry(QueuedStreamContext context,
                                    long maxGlobal) {
        // If we have no entries to read, fill the read queue.
        // Return if the queue is still empty.
        if (context.readQueue.isEmpty() && context.readCpQueue.isEmpty() &&
                !fillReadQueue(maxGlobal, context)) {
            return null;
        }

        // If checkpoint data is available, get from readCpQueue first
        NavigableSet<Long> getFrom;
        if (context.readCpQueue.size() > 0) {
            getFrom = context.readCpQueue;
            if (context.readQueue.isEmpty()) {
                // readQueue is empty, readCpQueue is not.
                // This is a case where we have had 2 checkpoints
                // adjacent to each other, and no non-checkpoint
                // entries in the stream in between the checkpoints
                // or during the 2nd checkpoint.  Processing of
                // checkpoint entries will not advance our context
                // globalPointer, only regular entries in readQueue.
                // However, we know that readQueue is *empty*, so
                // we advance globalPointer here.
                context.globalPointer = maxGlobal;
            }
        } else {
            getFrom = context.readQueue;
        }

        // If the lowest DATA element is greater than maxGlobal, there's nothing
        // to return.
        if (context.readCpQueue.isEmpty() && context.readQueue.first() > maxGlobal) {
            return null;
        }

        // Otherwise we remove entries one at a time from the read queue.
        // The entry may not actually be part of the stream, so we might
        // have to perform several reads.
        while (getFrom.size() > 0) {
            final long thisRead = getFrom.pollFirst();
            ILogData ld = read(thisRead);
            if (ld.containsStream(context.id)) {
                // Only add to resolved if ld is from readQueue
                if (getFrom == context.readQueue) {
                    addToResolvedQueue(context, thisRead, ld);
                }
                return ld;
            }
        }

        // None of the potential reads ended up being part of this
        // stream, so we return null.
        return null;
    }

    /** {@inheritDoc}
     *
     * In the queued implementation, we just read all entries in the read queue
     * in parallel. If there is any entry which changes the context, we cut the
     * list off there.
     * */
    @Override
    protected List<ILogData> getNextEntries(QueuedStreamContext context, long maxGlobal,
                                            Function<ILogData, Boolean> contextCheckFn) {
        NavigableSet<Long> readSet = new TreeSet<>();

        // Scan backward in the stream to find interesting
        // log records less than or equal to maxGlobal.
        // Boolean includes both CHECKPOINT & DATA entries.
        boolean readQueueIsEmpty = !fillReadQueue(maxGlobal, context);

        // We always have to fill to the read queue to ensure we read up to
        // max global.
        if (readQueueIsEmpty) {
            return Collections.emptyList();
        }

        // If we witnessed a checkpoint during our scan that
        // we should pay attention to, then start with them.
        readSet.addAll(context.readCpQueue);

        if (!context.readQueue.isEmpty() && context.readQueue.first() > maxGlobal) {
            // If the lowest element is greater than maxGlobal, there's nothing
            // more to return: readSet is ok as-is.
        } else {
            // Select everything in the read queue between
            // the start and maxGlobal
            readSet.addAll(context.readQueue.headSet(maxGlobal, true));
        }
        List<Long> toRead = readSet.stream()
                .collect(Collectors.toList());

        // The list to store read results in
        List<ILogData> readFrom = readAll(toRead).stream()
                .filter(x -> x.getType() == DataType.DATA)
                .filter(x -> x.containsStream(context.id))
                .collect(Collectors.toList());

        // If any entries change the context,
        // don't return anything greater than
        // that entry
        Optional<ILogData> contextEntry = readFrom.stream()
                .filter(contextCheckFn::apply).findFirst();
        if (contextEntry.isPresent()) {
            log.trace("getNextEntries[{}] context switch @ {}", this, contextEntry.get().getGlobalAddress());
            int idx = readFrom.indexOf(contextEntry.get());
            readFrom = readFrom.subList(0, idx + 1);
            // NOTE: readSet's clear() changed underlying context.readQueue
            readSet.headSet(contextEntry.get().getGlobalAddress(), true).clear();
        } else {
            // Clear the entries which were read
            context.readQueue.headSet(maxGlobal, true).clear();
        }

        // Transfer the addresses of the read entries to the resolved queue
        readFrom.stream()
                .forEach(x -> addToResolvedQueue(context, x.getGlobalAddress(), x));

        // Update the global pointer
        if (readFrom.size() > 0) {
            context.globalPointer = readFrom.get(readFrom.size() - 1)
                    .getGlobalAddress();
        }

        return readFrom;
    }

    /**
     * Retrieve the data at the given address which was previously
     * inserted into the read queue.
     *
     * @param address       The address to read.
     */
    abstract protected @Nonnull ILogData read(final long address);

    /**
     * Given a list of addresses, retrieve the data as a list in the same
     * order of the addresses given in the list.
     * @param addresses     The addresses to read.
     * @return              A list of ILogData in the same order as the
     *                      addresses given.
     */
    protected @Nonnull List<ILogData> readAll(@Nonnull final List<Long> addresses) {
        return addresses.parallelStream()
                        .map(this::read)
                        .collect(Collectors.toList());
    }

    /**
     * Fill the read queue for the current context. This method is called
     * whenever a client requests a read, but there are no addresses left in
     * the read queue.
     *
     * This method returns true if entries were added to the read queue,
     * false otherwise.
     *
     * @param maxGlobal     The maximum global address to read to.
     * @param context       The current stream context.
     *
     * @return              True, if entries were added to the read queue,
     *                      False, otherwise.
     */
    abstract protected boolean fillReadQueue(final long maxGlobal,
                                          final QueuedStreamContext context);

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized long find(long globalAddress, SearchDirection direction) {
        final QueuedStreamContext context = getCurrentContext();
        // First, check if we have resolved up to the given address
        if (context.maxResolution < globalAddress) {
            // If not we need to read to that position
            // to resolve all the addresses.
            remainingUpTo(globalAddress + 1);
        }

        // Now we can do the search.
        // First, check for inclusive searches.
        if (direction.isInclusive() &&
                context.resolvedQueue.contains(globalAddress)) {
            return globalAddress;
        }
        // Next, check all elements excluding
        // in the correct direction.
        Long result;
        if (direction.isForward()) {
            result = context.resolvedQueue.higher(globalAddress);
        }  else {
            result = context.resolvedQueue.lower(globalAddress);
        }

        // Convert the address to never read if there was no result.
        return result == null ? Address.NOT_FOUND : result;
    }

    /** {@inheritDoc} */
    @Override
    public synchronized ILogData previous() {
        final QueuedStreamContext context = getCurrentContext();
        log.trace("Previous[{}] max={} min={}", this,
                context.maxResolution,
                context.minResolution);
        // If never read, there would be no pointer to the previous entry.
        if (context.globalPointer == Address.NEVER_READ) {
            return null;
        }

        // Otherwise, the previous entry should be resolved, so get
        // one less than the current.
        Long prevAddress = context
                .resolvedQueue.lower(context.globalPointer);
        // If the pointer is before our min resolution, we need to resolve
        // to get the correct previous entry.
        if (prevAddress == null && Address.isAddress(context.minResolution)
                || prevAddress != null && prevAddress <= context.minResolution) {
            long oldPointer = context.globalPointer;
            context.globalPointer = prevAddress == null ? Address.NEVER_READ :
                                                prevAddress - 1L;
            remainingUpTo(context.minResolution);
            context.minResolution = Address.NON_ADDRESS;
            context.globalPointer = oldPointer;
            prevAddress = context
                    .resolvedQueue.lower(context.globalPointer);
            log.trace("Previous[}] updated queue {}", this, context.resolvedQueue);
        }
        // If still null, we're done.
        if (prevAddress == null) {
            return null;
        }
        // Add the current pointer back into the read queue
        context.readQueue.add(context.globalPointer);
        // Update the global pointer
        context.globalPointer = prevAddress;
        return read(prevAddress);
    }

   /** {@inheritDoc} */
    @Override
    public synchronized ILogData current() {
        final QueuedStreamContext context = getCurrentContext();

        if (Address.nonAddress(context.globalPointer)) {
            return null;
        }
        return read(context.globalPointer);
    }

    /** {@inheritDoc} */
    @Override
    public long getCurrentGlobalPosition() {
        return getCurrentContext().globalPointer;
    }


    /** {@inheritDoc}
     *
     * For the queued stream context, we include just a queue of potential
     * global addresses to be read from.
     */
    @ToString
    static class QueuedStreamContext extends AbstractStreamContext {


        /** A queue of addresses which have already been resolved. */
        final NavigableSet<Long> resolvedQueue
                = new TreeSet<>();

        /** The minimum global address which we have resolved this
         * stream to.
         */
        long minResolution = Address.NON_ADDRESS;

        /** The maximum global address which we have resolved this
         * stream to.
         */
        long maxResolution = Address.NON_ADDRESS;

        /**
         * A priority queue of potential addresses to be read from.
         */
        final NavigableSet<Long> readQueue
                = new TreeSet<>();

        /** List of checkpoint records, if a successful checkpoint has been observed.
         */
        final NavigableSet<Long> readCpQueue = new TreeSet<>();

        /** Info on checkpoint we used for initial stream replay,
         *  other checkpoint-related info & stats.  Hodgepodge, clarify.
         */
        UUID checkpointSuccessID = null;
        long checkpointSuccessStartAddr = Address.NEVER_READ;
        long checkpointSuccessEndAddr = Address.NEVER_READ;
        long checkpointSuccessNumEntries = 0L;
        long checkpointSuccessBytes = 0L;
        // No need to keep track of # of DATA entries, use context.resolvedQueue.size()?
        long resolvedEstBytes = 0L;

        /** Create a new stream context with the given ID and maximum address
         * to read to.
         * @param id                  The ID of the stream to read from
         * @param maxGlobalAddress    The maximum address for the context.
         */
        public QueuedStreamContext(UUID id, long maxGlobalAddress) {
            super(id, maxGlobalAddress);
        }


        /** {@inheritDoc} */
        @Override
        void reset() {
            super.reset();
            readCpQueue.clear();
            readQueue.clear();
            resolvedQueue.clear();

            checkpointSuccessID = null;
            checkpointSuccessStartAddr = Address.NEVER_READ;
            checkpointSuccessEndAddr = Address.NEVER_READ;
            checkpointSuccessNumEntries = 0;
            checkpointSuccessBytes = 0;
            resolvedEstBytes = 0;
        }

        /** {@inheritDoc} */
        @Override
        void seek(long globalAddress) {
            if (Address.nonAddress(globalAddress)) {
                throw new IllegalArgumentException("globalAddress must be >= Address.maxNonAddress()");
            }
            log.trace("Seek[{}]({}), min={} max={}", this,  globalAddress, minResolution, maxResolution);
            // Update minResolution if necessary
            if (globalAddress >= maxResolution) {
                log.warn("set min res to {}" , globalAddress);
                minResolution = globalAddress; // TODO SLF wha? minResolution can be greater than maxResolution
            }
            // remove anything in the read queue LESS
            // than global address.
            readQueue.headSet(globalAddress).clear();
            // transfer from the resolved queue into
            // the read queue anything equal to or
            // greater than the global address
            readQueue.addAll(resolvedQueue.tailSet(globalAddress, true));
            super.seek(globalAddress);
        }
    }

}
