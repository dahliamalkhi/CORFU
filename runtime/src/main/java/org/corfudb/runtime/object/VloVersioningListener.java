package org.corfudb.runtime.object;

import java.util.concurrent.ConcurrentLinkedQueue;

public class VloVersioningListener {

    private static final ConcurrentLinkedQueue<VloListener> listeners = new ConcurrentLinkedQueue<>();

    private VloVersioningListener() {
        //private constructor
    }

    public static void subscribe(VloListener listener) {
        listeners.add(listener);
    }

    public static void submit(long version) {
        for (VloListener listener : listeners) {
            listener.onVersionChange(version);
        }
    }

    @FunctionalInterface
    public interface VloListener {
        void onVersionChange(long version);
    }
}