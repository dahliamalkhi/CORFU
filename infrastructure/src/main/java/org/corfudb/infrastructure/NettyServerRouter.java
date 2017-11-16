package org.corfudb.infrastructure;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nonnull;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import org.corfudb.protocols.wireprotocol.CorfuMsg;
import org.corfudb.protocols.wireprotocol.CorfuMsgType;
import org.corfudb.protocols.wireprotocol.CorfuPayloadMsg;
import org.corfudb.protocols.wireprotocol.ExceptionMsg;


/**
 * The netty server router routes incoming messages to registered roles using
 * the
 * Created by mwei on 12/1/15.
 */
@Slf4j
@ChannelHandler.Sharable
public class NettyServerRouter extends ChannelInboundHandlerAdapter
        implements IServerRouter {

    public static final String PREFIX_EPOCH = "SERVER_EPOCH";
    public static final String KEY_EPOCH = "CURRENT";

    public static class ServerThreadFactory
            implements ForkJoinPool.ForkJoinWorkerThreadFactory {

        public static final String THREAD_PREFIX = "ServerRouter-";
        final AtomicInteger threadNumber = new AtomicInteger(0);

        public static class ServerWorkerThread extends ForkJoinWorkerThread {

            protected ServerWorkerThread(final ForkJoinPool pool, final String threadName) {
                super(pool);
                this.setName(threadName);
                this.setUncaughtExceptionHandler(NettyServerRouter::handleUncaughtException);
            }

            @Override
            protected void onTermination(Throwable exception) {
                if (exception != null) {
                    log.error("onTermination: Thread terminated due to {}:{}",
                            exception.getClass().getSimpleName(),
                            exception.getMessage(),
                            exception);
                } else {
                    log.warn("onTermination: Thread terminated (completed normally).");
                }
                super.onTermination(exception);
            }
        }

        @Override
        public ForkJoinWorkerThread newThread(ForkJoinPool pool) {
            return new ServerWorkerThread(pool,
                    THREAD_PREFIX + threadNumber.getAndIncrement());
        }
    }

    protected static void handleUncaughtException(Thread t, @Nonnull Throwable e) {
        log.error("handleUncaughtException[{}]: Uncaught {}:{}",
                t.getName(),
                e.getClass().getSimpleName(),
                e.getMessage(),
                e);
    }

    protected final ExecutorService handlerWorkers =
            new ForkJoinPool(Runtime.getRuntime().availableProcessors(),
                    new ServerThreadFactory(),
                    NettyServerRouter::handleUncaughtException, true);

    /**
     * This map stores the mapping from message type to netty server handler.
     */
    Map<CorfuMsgType, CorfuMsgHandler.Handler> handlerMap;

    BaseServer baseServer;

    /**
     * The epoch of this router. This is managed by the base server implementation.
     */
    @Getter
    @Setter
    long serverEpoch;

    /**
     * Returns a new NettyServerRouter.
     * @param opts map of options (FIXME: unused)
     */
    public NettyServerRouter(Map<String, Object> opts) {
        handlerMap = new EnumMap<>(CorfuMsgType.class);
        baseServer = new BaseServer();
        addServer(baseServer);
    }

    /**
     * Add a new netty server handler to the router.
     *
     * @param server The server to add.
     */
    public void addServer(AbstractServer server) {
        // Iterate through all types of CorfuMsgType, registering the handler
        server.getMsgHandler().getHandledTypes()
                .forEach(x -> {
                    handlerMap.put(x, server.getMsgHandler().getHandler(x));
                    log.trace("Registered {} to handle messages of type {}", server, x);
                });
    }

    /**
     * Remove a server from the router.
     * @param server  server to remove
     */
    public void removeServer(AbstractServer server) {
        // Iterate through all types of CorfuMsgType, un-registering the handler
        server.getMsgHandler().getHandledTypes()
                .forEach(x -> {
                    handlerMap.remove(x, server);
                    log.trace("Un-Registered {} to handle messages of type {}", server, x);
                });
    }

    /**
     * Send a netty message through this router, setting the fields in the outgoing message.
     *
     * @param ctx    Channel handler context to use.
     * @param inMsg  Incoming message to respond to.
     * @param outMsg Outgoing message.
     */
    public void sendResponse(ChannelHandlerContext ctx, CorfuMsg inMsg, CorfuMsg outMsg) {
        outMsg.copyBaseFields(inMsg);
        ctx.writeAndFlush(outMsg);
        log.trace("Sent response: {}", outMsg);
    }

    /**
     * Validate the epoch of a CorfuMsg, and send a WRONG_EPOCH response if
     * the server is in the wrong epoch. Ignored if the message type is reset (which
     * is valid in any epoch).
     *
     * @param msg The incoming message to validate.
     * @param ctx The context of the channel handler.
     * @return True, if the epoch is correct, but false otherwise.
     */
    public boolean validateEpoch(CorfuMsg msg, ChannelHandlerContext ctx) {
        long serverEpoch = getServerEpoch();
        if (!msg.getMsgType().ignoreEpoch && msg.getEpoch() != serverEpoch) {
            sendResponse(ctx, msg, new CorfuPayloadMsg<>(CorfuMsgType.WRONG_EPOCH,
                    serverEpoch));
            log.trace("Incoming message with wrong epoch, got {}, expected {}, message was: {}",
                    msg.getEpoch(), serverEpoch, msg);
            return false;
        }
        return true;
    }

    /**
     * Handle an incoming message read on the channel.
     *
     * @param ctx Channel handler context
     * @param msg The incoming message on that channel.
     */
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        try {
            // The incoming message should have been transformed to a CorfuMsg earlier in the
            // pipeline.
            CorfuMsg m = ((CorfuMsg) msg);
            // We get the handler for this message from the map
            CorfuMsgHandler.Handler<CorfuMsg> handler = handlerMap.get(m.getMsgType());
            if (handler == null) {
                // The message was unregistered, we are dropping it.
                log.warn("Received unregistered message {}, dropping", m);
            } else {
                if (validateEpoch(m, ctx)) {
                    // Route the message to the handler.
                    if (log.isTraceEnabled()) {
                        log.trace("Message routed to {}: {}", handler.getClass().getSimpleName(),
                                msg);
                    }
                    handlerWorkers.submit(() -> {
                        try {
                            handler.handle(m, ctx, this);
                        } catch (Exception ex) {
                            // Log the exception during handling
                            log.error("handle: Unhandled exception processing {} message",
                                    m.getMsgType(), ex);
                            sendResponse(ctx, m,
                                    CorfuMsgType.ERROR_SERVER_EXCEPTION
                                            .payloadMsg(new ExceptionMsg(ex)));
                        }
                    });
                }
            }
        } catch (Exception e) {
            log.error("Exception during read!", e);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Error in handling inbound message, {}", cause);
        ctx.close();
    }

}
