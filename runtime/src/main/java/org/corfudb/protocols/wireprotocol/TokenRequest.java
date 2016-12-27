package org.corfudb.protocols.wireprotocol;

import io.netty.buffer.ByteBuf;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.Set;
import java.util.UUID;

/**
 * A token request is at the heart of the Corfu log protocol.
 *
 * There are four token request scenarios, designated by the relevant constants :
 * 0. {@link TokenRequest::TK_QUERY} : Query of the current log tail and of specific stream-tails.
 * 1. {@link TokenRequest::TK_RAW} : Ask for raw (global) log token(s).
 *              This extends the global log tail by the requested # of tokens.
 * 2. {@link TokenRequest::TK_STREAM} : Ask for token(s) on a specific stream.
 *          This extends both the global log tail, and the specific stream tail, by the requested # of tokens.
 * 3. {@link TokenRequest::TK_MULTI_STREAM} : Ask for token(s) on multiple streams.
 *          This extends both the global log tail, and each of the specified stream tails, by the requested # of tokens.
 * 4. {@link TokenRequest::TK_TX} :
 *          First, check transaction resolution. If transaction can commit, then behave like {@link TokenRequest::TK_MULTI_STREAM}.
 */
@Data
@AllArgsConstructor
public class TokenRequest implements ICorfuPayload<TokenRequest> {

    public static final byte TK_QUERY = 0;
    public static final byte TK_RAW = 1;
    public static final byte TK_STREAM = 2;
    public static final byte TK_MULTI_STREAM = 3;
    public static final byte TK_TX = 4;

    /** The type of request, one of the above */
    final byte reqType;

    /** The number of tokens to request. */
    final Long numTokens;

    /** The streams which are written to by this token request. */
    final Set<UUID> streams;

    /* True if the Replex protocol encountered an overwrite at the global log layer. */
    @Deprecated
    final Boolean overwrite = false; // todo : deprecate

    /* True if the Replex protocol encountered an overwrite at the local stream layer. */
    @Deprecated
    final Boolean replexOverwrite = false; // todo: deprecate

    /* used for transaction resolution. */
    final TxResolutionInfo txnResolution;

    // todo: deprecate this constructor!
    public TokenRequest(Long numTokens, Set<UUID> streams, Boolean overwrite, Boolean replexOverwrite,
                        boolean isTx, long readTS, Set<UUID> readSet) {

        if (numTokens == 0)
            this.reqType = TK_QUERY;
        else if (isTx)
            this.reqType = TK_TX;
        else if (streams == null || streams.size() == 0)
            this.reqType = TK_RAW;
        else
            this.reqType = TK_MULTI_STREAM;

        this.numTokens = numTokens;
        this.streams = streams;
        //this.overwrite = overwrite;
        //this.replexOverwrite = replexOverwrite;
        this.txnResolution = isTx ? new TxResolutionInfo(readTS, readSet) : new TxResolutionInfo(-1L, null);
    }

    public TokenRequest(Long numTokens, Set<UUID> streams, Boolean overwrite, Boolean replexOverwrite) {
        this(numTokens, streams, overwrite, replexOverwrite,
                false, 0L, null);
    }

    public TokenRequest(ByteBuf buf) {
        reqType = ICorfuPayload.fromBuffer(buf, Byte.class);

        switch (reqType) {

            case TK_QUERY:
                numTokens = 0L;
                streams = ICorfuPayload.setFromBuffer(buf, UUID.class);
                this.txnResolution = null;
                break;

            case TK_RAW:
                numTokens = ICorfuPayload.fromBuffer(buf, Long.class);
                streams = null;
                this.txnResolution = null;
                break;

            case TK_STREAM:
            case TK_MULTI_STREAM:
                numTokens = ICorfuPayload.fromBuffer(buf, Long.class);
                streams = ICorfuPayload.setFromBuffer(buf, UUID.class);
                this.txnResolution = null;
                break;

            case TK_TX:
                numTokens = ICorfuPayload.fromBuffer(buf, Long.class);
                streams = ICorfuPayload.setFromBuffer(buf, UUID.class);
                txnResolution = ICorfuPayload.fromBuffer(buf, TxResolutionInfo.class);
                break;

            default:
                numTokens = -1L;
                streams = null;
                txnResolution = null;
                break;
        }
    }

    @Override
    public void doSerialize(ByteBuf buf) {
        ICorfuPayload.serialize(buf, reqType);
        if (reqType != TK_QUERY)
            ICorfuPayload.serialize(buf, numTokens);

        if (reqType != TK_RAW)
            ICorfuPayload.serialize(buf, streams);

        if (reqType == TK_TX)
            ICorfuPayload.serialize(buf, txnResolution);
    }
}