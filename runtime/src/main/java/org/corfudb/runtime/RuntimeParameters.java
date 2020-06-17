package org.corfudb.runtime;

import com.google.common.collect.ImmutableMap;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import lombok.Data;
import lombok.Singular;
import lombok.ToString;
import lombok.Builder.Default;
import lombok.experimental.SuperBuilder;
import org.corfudb.comm.ChannelImplementation;
import org.corfudb.protocols.wireprotocol.MsgHandlingFilter;
import org.corfudb.util.MetricsUtils;
import org.corfudb.runtime.clients.NettyClientRouter;

import java.lang.Thread.UncaughtExceptionHandler;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@SuperBuilder
@Data
@ToString
public class RuntimeParameters {

        @Default
        private final long nettyShutdownQuitePeriod = 100;
        @Default
        private final long nettyShutdownTimeout = 300;

        /**
         * True, if TLS is enabled.
         */
        @Default
        boolean tlsEnabled = false;

        /**
         * A path to the key store.
         */
        String keyStore;

        /*
         * A file containing the password for the key store.
         */
        String ksPasswordFile;

        /**
         * A path to the trust store.
         */
        String trustStore;

        /**
         * A path containing the password for the trust store.
         */
        String tsPasswordFile;

        /**
         * True, if SASL plain text authentication is enabled.
         */
        @Default
        boolean saslPlainTextEnabled = false;

        /**
         * A path containing the username file for SASL.
         */
        String usernameFile;

        /**
         * A path containing the password file for SASL.
         */
        String passwordFile;
        //endregion

        // region Handshake Parameters
        /**
         * Sets handshake timeout in seconds.
         */
        @Default
        int handshakeTimeout = 10;
        // endregion

        //region Connection parameters
        /**
         * {@link Duration} before requests timeout.
         * This is the duration after which the logreader hole fills the address.
         */
        @Default
        Duration requestTimeout = Duration.ofSeconds(5);

        /**
         * This timeout (in seconds) is used to detect servers that
         * shutdown abruptly without terminating the connection properly.
         */
        @Default
        int idleConnectionTimeout = 7;

        /**
         * The period at which the client sends keep-alive messages to the
         * server (a message is only send there is no write activity on the channel
         * for the whole period.
         */
        @Default
        int keepAlivePeriod = 2;

        /**
         * {@link Duration} before connections timeout.
         */
        @Default
        Duration connectionTimeout = Duration.ofMillis(500);

        /**
         * {@link Duration} before reconnecting to a disconnected node.
         */
        @Default
        Duration connectionRetryRate = Duration.ofSeconds(1);

        /**
         * The {@link UUID} for this client. Randomly generated by default.
         */
        @Default
        UUID clientId = UUID.randomUUID();

        /**
         * The type of socket which {@link NettyClientRouter}s should use. By default,
         * an NIO based implementation is used.
         */
        @Default
        ChannelImplementation socketType = ChannelImplementation.NIO;

        /**
         * The {@link EventLoopGroup} which {@link NettyClientRouter}s will use.
         * If not specified, the runtime will generate this using the {@link ChannelImplementation}
         * specified in {@code socketType} and the {@link java.util.concurrent.ThreadFactory} specified in
         * {@code nettyThreadFactory}.
         */
        EventLoopGroup nettyEventLoop;

        /**
         * A string which will be used to set the
         * {@link com.google.common.util.concurrent.ThreadFactoryBuilder#nameFormat} for the
         * {@code nettyThreadFactory}. By default, this is set to "netty-%d".
         * If you provide your own {@code nettyEventLoop}, this field is ignored.
         */
        @Default
        String nettyEventLoopThreadFormat = "netty-%d";

        /**
         * The number of threads that should be available in the {@link NettyClientRouter}'s
         * event pool. 0 means that we will use 2x the number of processors reported in the
         * system. If you provide your own {@code nettyEventLoop}, this field is ignored.
         */
        @Default
        int nettyEventLoopThreads = 0;

        /**
         * True, if the {@code NettyEventLoop} should be shutdown when the runtime is
         * shutdown. False otherwise.
         */
        @Default
        boolean shutdownNettyEventLoop = true;

        /**
         * Netty channel options, if provided. If no options are set, we default to
         * the defaults in {@link this#DEFAULT_CHANNEL_OPTIONS}.
         */
        @Singular
        Map<ChannelOption, Object> customNettyChannelOptions;

        /**
         * Default channel options, used if there are no options in the
         * {@link this#customNettyChannelOptions} field.
         */
        static final Map<ChannelOption, Object> DEFAULT_CHANNEL_OPTIONS =
                ImmutableMap.<ChannelOption, Object>builder()
                        .put(ChannelOption.TCP_NODELAY, true)
                        .put(ChannelOption.SO_REUSEADDR, true)
                        .build();

        /**
         * A {@link UncaughtExceptionHandler} which handles threads that have an uncaught
         * exception. Used on all {@link java.util.concurrent.ThreadFactory}s the runtime creates, but if you
         * generate your own thread factory, this field is ignored. If this field is not set,
         * the runtime's default handler runs, which logs an error level message.
         */
        UncaughtExceptionHandler uncaughtExceptionHandler;

        /**
         * Represents filtering logic to be applied on the inbound messages received by Netty Client
         * Router. If filters are not null, Netty Client Router add a filter handler and configures it
         * with provided filters. If filters are null, no filter handler will be added to Netty's pipeline.
         */
        @Default
        List<MsgHandlingFilter> nettyClientInboundMsgFilters = null;

        /**
         * Port at which the {@link CorfuRuntime} will allow third-party
         * collectors to pull for metrics.
         */
        @Default
        private int prometheusMetricsPort = MetricsUtils.NO_METRICS_PORT;

        // Register handlers region

        // These two handlers are provided to give some control on what happen when system is down.
        // For applications that want to have specific behaviour when a the system appears
        // unavailable, they can register their own handler for both before the rpc request and
        // upon cluster unreachability.
        // An example of how to use these handlers implementing timeout is given in
        // test/src/test/java/org/corfudb/runtime/CorfuRuntimeTest.java

        /**
         * SystemDownHandler is invoked at any point when the Corfu client attempts to make an RPC
         * request to the Corfu cluster but is unable to complete this.
         * NOTE: This will also be invoked during connect if the cluster is unreachable.
         */
        @Default
        volatile Runnable systemDownHandler = () -> {
        };

        /**
         * BeforeRPCHandler callback is invoked every time before an RPC call.
         */
        @Default
        volatile Runnable beforeRpcHandler = () -> {
        };

        //endregion

        /**
         * Get the netty channel options to be used by the netty client implementation.
         *
         * @return A map containing options which should be applied to each netty channel.
         */
        public Map<ChannelOption, Object> getNettyChannelOptions() {
                return customNettyChannelOptions.size() == 0
                        ? DEFAULT_CHANNEL_OPTIONS : customNettyChannelOptions;
        }
}
