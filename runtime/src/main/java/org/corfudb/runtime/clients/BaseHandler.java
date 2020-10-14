package org.corfudb.runtime.clients;

import com.google.gson.Gson;
import io.netty.channel.ChannelHandlerContext;

import java.lang.invoke.MethodHandles;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import org.corfudb.protocols.wireprotocol.CorfuMsg;
import org.corfudb.protocols.wireprotocol.CorfuMsgType;
import org.corfudb.protocols.wireprotocol.CorfuPayloadMsg;
import org.corfudb.protocols.wireprotocol.ExceptionMsg;
import org.corfudb.protocols.wireprotocol.JSONPayloadMsg;
import org.corfudb.protocols.wireprotocol.VersionInfo;
import org.corfudb.protocols.wireprotocol.WrongClusterMsg;
import org.corfudb.runtime.exceptions.ServerNotReadyException;
import org.corfudb.runtime.exceptions.WrongClusterException;
import org.corfudb.runtime.exceptions.WrongEpochException;

import org.corfudb.runtime.proto.service.CorfuMessage.ResponseMsg;
import org.corfudb.runtime.proto.service.CorfuMessage.ResponsePayloadMsg.PayloadCase;
import org.corfudb.runtime.proto.service.Base;

/**
 * This is a base client which handles basic Corfu messages such as PING, ACK.
 * This is also responsible for handling unknown server exceptions.
 *
 * <p>Created by zlokhandwala on 2/20/18.
 */
@Slf4j
public class BaseHandler implements IClient {

    /**
     * The router to use for the client.
     */
    @Getter
    @Setter
    public IClientRouter router;

    /**
     * The protobuf router to use for the client.
     * For old CorfuMsg, use {@link #router}
     */
    @Getter
    @Setter
    public IClientProtobufRouter protobufRouter;

    /** Public functions which are exposed to clients. */

    /**
     * The handler and handlers which implement this client.
     */
    @Getter
    public ClientMsgHandler msgHandler = new ClientMsgHandler(this)
            .generateHandlers(MethodHandles.lookup(), this);

    /**
     * For old CorfuMsg, use {@link #msgHandler}
     * The handler and handlers which implement this client.
     */
    @Getter
    public ClientResponseHandler responseHandler = new ClientResponseHandler(this)
            .generateHandlers(MethodHandles.lookup(), this);

    /**
     * Handle a ping request from the server.
     *
     * @param msg The ping request message
     * @param ctx The context the message was sent under
     * @param r   A reference to the router
     * @return The return value, null since this is a message from the server.
     */
    @ClientHandler(type = CorfuMsgType.PING)
    private static Object handlePing(CorfuMsg msg, ChannelHandlerContext ctx, IClientRouter r) {
        CorfuMsg outMsg = new CorfuMsg(CorfuMsgType.PONG);
        outMsg.copyBaseFields(msg);
        ctx.writeAndFlush(outMsg, ctx.voidPromise());
        log.trace("Sent response: {}", outMsg);
        return null;
    }

    /**
     * Handle a pong response from the server.
     * For protobuf, use {@link #handlePingResponse(ResponseMsg,
     * ChannelHandlerContext, IClientProtobufRouter)}
     *
     * @param msg The ping request message
     * @param ctx The context the message was sent under
     * @param r   A reference to the router
     * @return Always True, since the ping message was successful.
     */
    @ClientHandler(type = CorfuMsgType.PONG)
    @Deprecated
    private static Object handlePong(CorfuMsg msg, ChannelHandlerContext ctx, IClientRouter r) {
        return true;
    }

    /**
     * Handle an ACK response from the server.
     *
     * @param msg The ping request message
     * @param ctx The context the message was sent under
     * @param r   A reference to the router
     * @return Always True, since the ACK message was successful.
     */
    @ClientHandler(type = CorfuMsgType.ACK)
    @Deprecated
    private static Object handleAck(CorfuMsg msg, ChannelHandlerContext ctx, IClientRouter r) {
        return true;
    }

    /**
     * Handle a NACK response from the server.
     *
     * @param msg The ping request message
     * @param ctx The context the message was sent under
     * @param r   A reference to the router
     * @return Always True, since the ACK message was successful.
     */
    @ClientHandler(type = CorfuMsgType.NACK)
    @Deprecated
    private static Object handleNack(CorfuMsg msg, ChannelHandlerContext ctx, IClientRouter r) {
        return false;
    }

    /**
     * Handle a WRONG_EPOCH response from the server.
     *
     * @param msg The wrong epoch message
     * @param ctx The context the message was sent under
     * @param r   A reference to the router
     * @return none, throw a wrong epoch exception instead.
     */
    @ClientHandler(type = CorfuMsgType.WRONG_EPOCH)
    private static Object handleWrongEpoch(CorfuPayloadMsg<Long> msg, ChannelHandlerContext ctx,
                                           IClientRouter r) {
        throw new WrongEpochException(msg.getPayload());
    }

    /**
     * Handle a Version response from the server.
     *
     * @param msg The version message
     * @param ctx The context the message was sent under
     * @param r   A reference to the router
     * @return The versioninfo object.
     */
    @ClientHandler(type = CorfuMsgType.VERSION_RESPONSE)
    @Deprecated
    private static Object handleVersionResponse(JSONPayloadMsg<VersionInfo> msg,
                                                ChannelHandlerContext ctx, IClientRouter r) {
        return msg.getPayload();
    }

    @ClientHandler(type = CorfuMsgType.NOT_READY)
    private static Object handleNotReady(CorfuMsg msg, ChannelHandlerContext ctx, IClientRouter r) {
        throw new ServerNotReadyException();
    }

    /**
     * Generic handler for a server exception.
     */
    @ClientHandler(type = CorfuMsgType.ERROR_SERVER_EXCEPTION)
    private static Object handleServerException(CorfuPayloadMsg<ExceptionMsg> msg,
                                                ChannelHandlerContext ctx, IClientRouter r)
            throws Throwable {
        log.warn("Server threw exception for request {}", msg.getRequestID(),
                msg.getPayload().getThrowable());
        throw msg.getPayload().getThrowable();
    }

    /**
     * Handle a wrong cluster id exception.
     * @param msg Wrong cluster id exception message.
     * @param ctx A context the message was sent under.
     * @param r A reference to the router.
     * @return None, throw a wrong cluster id exception.
     */
    @ClientHandler(type = CorfuMsgType.WRONG_CLUSTER_ID)
    private static Object handleWrongClusterId(CorfuPayloadMsg<WrongClusterMsg> msg,
                                               ChannelHandlerContext ctx, IClientRouter r) {
        WrongClusterMsg wrongClusterMessage = msg.getPayload();
        throw new WrongClusterException(wrongClusterMessage.getServerClusterId(),
                wrongClusterMessage.getClientClusterId());
    }

    // Protobuf region

    /**
     * Handle a ping response from the server.
     * For old CorfuMsg, use {@link #handlePong(CorfuMsg, ChannelHandlerContext, IClientRouter)}
     *
     * @param msg The ping response message.
     * @param ctx The context the message was sent under.
     * @param r A reference to the router
     * @return Always True, since the ping message was successful.
     */
    @ResponseHandler(type = PayloadCase.PING_RESPONSE)
    private static Object handlePingResponse(ResponseMsg msg, ChannelHandlerContext ctx,
                                             IClientProtobufRouter r) {
        return true;
    }

    @ResponseHandler(type = PayloadCase.AUTHENTICATE_RESPONSE)
    private static Object handleAuthResponse(ResponseMsg msg, ChannelHandlerContext ctx,
                                             IClientProtobufRouter r) {
        // TODO: add implementation after BaseServer done.
        return true;
    }

    /**
     * Handle a restart response from the server.
     *
     * @param msg The ping response message.
     * @param ctx The context the message was sent under.
     * @param r A reference to the router
     * @return Always True, since the restart message was successful.
     */
    @ResponseHandler(type = PayloadCase.RESTART_RESPONSE)
    private static Object handleRestartResponse(ResponseMsg msg, ChannelHandlerContext ctx,
                                                IClientProtobufRouter r) {
        return true;
    }

    /**
     * Handle a reset response from the server.
     *
     * @param msg The ping response message.
     * @param ctx The context the message was sent under.
     * @param r A reference to the router
     * @return Always True, since the reset message was successful.
     */
    @ResponseHandler(type = PayloadCase.RESET_RESPONSE)
    private static Object handleResetResponse(ResponseMsg msg, ChannelHandlerContext ctx,
                                              IClientProtobufRouter r) {
        return true;
    }

    /**
     * Handle a seal response from the server.
     *
     * @param msg The ping response message.
     * @param ctx The context the message was sent under.
     * @param r A reference to the router
     * @return Always True, since the seal message was successful.
     */
    @ResponseHandler(type = PayloadCase.SEAL_RESPONSE)
    private static Object handleSealResponse(ResponseMsg msg, ChannelHandlerContext ctx,
                                             IClientProtobufRouter r) {
        return true;
    }

    /**
     * Handle a version response from the server.
     * For old CorfuMsg, use {@link #handleVersionResponse(JSONPayloadMsg, ChannelHandlerContext, IClientRouter)}
     *
     * @param msg The ping response message.
     * @param ctx The context the message was sent under.
     * @param r A reference to the router
     * @return The VersionInfo object fetched from response msg.
     */
    @ResponseHandler(type = PayloadCase.VERSION_RESPONSE)
    private static Object handleVersionResponse(ResponseMsg msg, ChannelHandlerContext ctx,
                                                IClientProtobufRouter r) {
        Base.VersionResponseMsg versionResponseMsg = msg.getPayload().getVersionResponse();
        String jsonPayloadMsg = versionResponseMsg.getJsonPayloadMsg();
        final Gson parser = new Gson();

        return parser.fromJson(jsonPayloadMsg, VersionInfo.class);
    }

    // End region
}
}
