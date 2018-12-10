package org.corfudb.util.retry;

import java.time.Duration;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

/**
 * This class implements a basic interval-based retry.
 *
 * <p>Created by mwei on 9/1/15.
 */
@Slf4j
public class IntervalRetry<E extends Exception, F extends Exception,
        G extends Exception, H extends Exception, O> extends AbstractRetry<E, F,
        G, H, O, IntervalRetry> {


    /**
     * The interval, in milliseconds to wait for retry.
     **/
    @Getter
    @Setter
    Duration retryInterval = Duration.ofMillis(1000);

    public IntervalRetry(IRetryable<E, F, G, H, O> runFunction) {
        super(runFunction);
    }

    /**
     * Apply the retry logic.
     *
     */
    @Override
    public Duration nextWait() throws InterruptedException {
        return retryInterval;
    }

}
