package org.corfudb.runtime.view.stream;

import lombok.NonNull;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.corfudb.protocols.wireprotocol.DataType;
import org.corfudb.protocols.wireprotocol.ILogData;
import org.corfudb.protocols.wireprotocol.LogData;
import org.corfudb.runtime.CorfuRuntime;
import org.corfudb.runtime.view.Address;

import javax.annotation.Nonnull;
import java.util.*;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

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
                                      long globalAddress) {
        context.resolvedQueue.add(globalAddress);

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
        if (context.readQueue.isEmpty() &&
                !fillReadQueue(maxGlobal, context)) {
            return null;
        }

        // If the lowest element is greater than maxGlobal, there's nothing
        // to return.
        if (context.readQueue.first() > maxGlobal) {
            return null;
        }

        // Otherwise we remove entries one at a time from the read queue.
        // The entry may not actually be part of the stream, so we might
        // have to perform several reads.
        while (context.readQueue.size() > 0) {
            final long thisRead = context.readQueue.pollFirst();
            ILogData ld = read(thisRead);
            if (ld.containsStream(context.id)) {
                addToResolvedQueue(context, thisRead);
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
        // We always have to fill to the read queue to ensure we read up to
        // max global.
        if (!fillReadQueue(maxGlobal, context)) {
            return Collections.emptyList();
        }

        // If the lowest element is greater than maxGlobal, there's nothing
        // to return.
        if (context.readQueue.first() > maxGlobal) {
            return Collections.emptyList();
        }

        // Select everything in the read queue between
        // the start and maxGlobal
        NavigableSet<Long> readSet =
                context.readQueue.headSet(maxGlobal, true);

        List<Long> toRead = readSet.stream()
                .collect(Collectors.toList());

        // The list to store read results in
        List<ILogData> read = readAll(toRead).stream()
                // .filter(x -> x.getType() == DataType.DATA)
                .filter(x -> {if(x.getType()==DataType.CHECKPOINT){System.err.printf("I see CHECKPOINT B\n");} return x.getType() == DataType.DATA;})
                .filter(x -> x.containsStream(context.id))
                .collect(Collectors.toList());

        // If any entries change the context,
        // don't return anything greater than
        // that entry
        Optional<ILogData> contextEntry = read.stream()
                .filter(contextCheckFn::apply).findFirst();
        if (contextEntry.isPresent()) {
            log.trace("getNextEntries[{}] context switch @ {}", this, contextEntry.get().getGlobalAddress());
            int idx = read.indexOf(contextEntry.get());
            read = read.subList(0, idx + 1);
            readSet.headSet(contextEntry.get().getGlobalAddress(), true).clear();
        } else {
            // Clear the entries which were read
            readSet.clear();
        }

        // Transfer the addresses of the read entries to the resolved queue
        read.stream()
                .map(x -> x.getGlobalAddress())
                .forEach(x -> addToResolvedQueue(context, x));

        // Update the global pointer
        if (read.size() > 0) {
            context.globalPointer = read.get(read.size() - 1)
                    .getGlobalAddress();
        }

        // Return the list of entries read.
        return read;
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
        if (prevAddress == null && context.minResolution != Address.NEVER_READ
                || prevAddress != null && prevAddress <= context.minResolution) {
            long oldPointer = context.globalPointer;
            context.globalPointer = prevAddress == null ? Address.NEVER_READ :
                                                prevAddress - 1L;
            remainingUpTo(context.minResolution);
            context.minResolution = Address.NEVER_READ;
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

        if (context.globalPointer == Address.NEVER_READ) {
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
        long minResolution = Address.NEVER_READ;

        /** The maximum global address which we have resolved this
         * stream to.
         */
        long maxResolution = Address.NEVER_READ;

        /**
         * A priority queue of potential addresses to be read from.
         */
        final NavigableSet<Long> readQueue
                = new TreeSet<>();

        /**
         * A priority queue of potential addresses to read checkpoint records from,
         * if a successful checkpoint has been observed.
         */
        final NavigableSet<Long> readCpQueue
                = new TreeSet<>();

        UUID checkpointSuccessID = null;

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
        }

        /** {@inheritDoc} */
        @Override
        void seek(long globalAddress) {
            if (globalAddress < Address.NEVER_READ) {
                throw new IllegalArgumentException("globalAddress must be >= Address.NEVER_READ");
            }
            log.trace("Seek[{}]({}), min={} max={}", this,  globalAddress, minResolution, maxResolution);
            // Update minResolution if necessary
            if (globalAddress >= maxResolution) {
                log.warn("set min res to {}" , globalAddress);
                minResolution = globalAddress;
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
