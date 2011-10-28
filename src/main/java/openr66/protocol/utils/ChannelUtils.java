/**
   This file is part of GoldenGate Project (named also GoldenGate or GG).

   Copyright 2009, Frederic Bregier, and individual contributors by the @author
   tags. See the COPYRIGHT.txt in the distribution for a full listing of
   individual contributors.

   All GoldenGate Project is free software: you can redistribute it and/or 
   modify it under the terms of the GNU General Public License as published 
   by the Free Software Foundation, either version 3 of the License, or
   (at your option) any later version.

   GoldenGate is distributed in the hope that it will be useful,
   but WITHOUT ANY WARRANTY; without even the implied warranty of
   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
   GNU General Public License for more details.

   You should have received a copy of the GNU General Public License
   along with GoldenGate .  If not, see <http://www.gnu.org/licenses/>.
 */
package openr66.protocol.utils;

import goldengate.common.database.DbAdmin;
import goldengate.common.file.DataBlock;
import goldengate.common.logging.GgInternalLogger;
import goldengate.common.logging.GgInternalLoggerFactory;
import goldengate.common.logging.GgSlf4JLoggerFactory;

import java.net.InetAddress;
import java.net.InetSocketAddress;

import openr66.context.R66FiniteDualStates;
import openr66.context.task.localexec.LocalExecClient;
import openr66.database.DbConstant;
import openr66.database.data.DbTaskRunner;
import openr66.protocol.configuration.Configuration;
import openr66.protocol.exception.OpenR66ProtocolPacketException;
import openr66.protocol.localhandler.LocalChannelReference;
import openr66.protocol.localhandler.packet.AbstractLocalPacket;
import openr66.protocol.localhandler.packet.DataPacket;
import openr66.protocol.localhandler.packet.EndTransferPacket;
import openr66.protocol.localhandler.packet.LocalPacketFactory;
import openr66.protocol.localhandler.packet.RequestPacket;
import openr66.protocol.networkhandler.NetworkTransaction;
import openr66.protocol.networkhandler.packet.NetworkPacket;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFactory;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.group.ChannelGroupFuture;
import org.jboss.netty.channel.group.ChannelGroupFutureListener;
import org.jboss.netty.handler.execution.OrderedMemoryAwareThreadPoolExecutor;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.LoggerContext;

/**
 * Channel Utils
 * @author Frederic Bregier
 */
public class ChannelUtils extends Thread {
    /**
     * Internal Logger
     */
    private static final GgInternalLogger logger = GgInternalLoggerFactory
            .getLogger(ChannelUtils.class);

    public static final Integer NOCHANNEL = Integer.MIN_VALUE;

    /**
     * Get the Remote InetAddress
     *
     * @param channel
     * @return the remote InetAddress
     */
    public static InetAddress getRemoteInetAddress(Channel channel) {
        InetSocketAddress socketAddress = (InetSocketAddress) channel
                .getRemoteAddress();
        if (socketAddress == null) {
            socketAddress = new InetSocketAddress(20);
        }
        return socketAddress.getAddress();
    }

    /**
     * Get the Local InetAddress
     *
     * @param channel
     * @return the local InetAddress
     */
    public static InetAddress getLocalInetAddress(Channel channel) {
        final InetSocketAddress socketAddress = (InetSocketAddress) channel
                .getLocalAddress();
        return socketAddress.getAddress();
    }

    /**
     * Get the Remote InetSocketAddress
     *
     * @param channel
     * @return the remote InetSocketAddress
     */
    public static InetSocketAddress getRemoteInetSocketAddress(Channel channel) {
        return (InetSocketAddress) channel.getRemoteAddress();
    }

    /**
     * Get the Local InetSocketAddress
     *
     * @param channel
     * @return the local InetSocketAddress
     */
    public static InetSocketAddress getLocalInetSocketAddress(Channel channel) {
        return (InetSocketAddress) channel.getLocalAddress();
    }

    /**
     * Finalize resources attached to handlers
     *
     * @author Frederic Bregier
     */
    private static class R66ChannelGroupFutureListener implements
            ChannelGroupFutureListener {
        OrderedMemoryAwareThreadPoolExecutor pool;
        String name;
        ChannelFactory channelFactory;

        public R66ChannelGroupFutureListener(
                String name,
                OrderedMemoryAwareThreadPoolExecutor pool,
                ChannelFactory channelFactory) {
            this.name = name;
            this.pool = pool;
            this.channelFactory = channelFactory;
        }

        public void operationComplete(ChannelGroupFuture future)
                throws Exception {
            if (pool != null) {
                pool.shutdownNow();
            }
            if (channelFactory != null) {
                channelFactory.releaseExternalResources();
            }
            logger.info("Done with shutdown "+name);
        }
    }

    /**
     * Terminate all registered channels
     *
     * @return the number of previously registered network channels
     */
    private static int terminateCommandChannels() {
        final int result = Configuration.configuration.getServerChannelGroup()
                .size();
        logger.debug("ServerChannelGroup: " + result);
        Configuration.configuration.getServerChannelGroup().close()
                .addListener(
                        new R66ChannelGroupFutureListener(
                                "ServerChannelGroup",
                                Configuration.configuration
                                        .getServerPipelineExecutor(),
                                Configuration.configuration
                                        .getServerChannelFactory()));
        return result;
    }
    /**
     * Terminate all registered Http channels
     *
     * @return the number of previously registered http network channels
     */
    private static int terminateHttpChannels() {
        final int result = Configuration.configuration.getHttpChannelGroup()
                .size();
        logger.debug("HttpChannelGroup: " + result);
        Configuration.configuration.getHttpChannelGroup().close()
                .addListener(
                        new R66ChannelGroupFutureListener(
                                "HttpChannelGroup",
                                null,
                                Configuration.configuration
                                        .getHttpChannelFactory()));
        Configuration.configuration.getHttpsChannelFactory().releaseExternalResources();
        return result;
    }
    /**
     * Return the current number of network connections
     *
     * @param configuration
     * @return the current number of network connections
     */
    public static int nbCommandChannels(Configuration configuration) {
        return configuration.getServerChannelGroup().size();
    }

    /**
     *
     * @param channel
     */
    public static void close(Channel channel) {
        try {
            Thread.sleep(Configuration.WAITFORNETOP);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Channels.close(channel);
    }

    /**
     *
     * @param localChannelReference
     * @param block
     * @return the ChannelFuture of this write operation
     * @throws OpenR66ProtocolPacketException
     */
    public static ChannelFuture writeBackDataBlock(
            LocalChannelReference localChannelReference, DataBlock block)
            throws OpenR66ProtocolPacketException {
        ChannelBuffer md5 = ChannelBuffers.EMPTY_BUFFER;
        DbTaskRunner runner = localChannelReference.getSession().getRunner();
        if (RequestPacket.isMD5Mode(runner.getMode())) {
            md5 = FileUtils.getHash(block.getBlock());
        }
        localChannelReference.sessionNewState(R66FiniteDualStates.DATAS);
        DataPacket data = new DataPacket(runner.getRank(), block.getBlock()
                .copy(), md5);
        ChannelFuture future = writeAbstractLocalPacket(localChannelReference, data);
        runner.incrementRank();
        return future;
    }

    /**
     * Write the EndTransfer
     *
     * @param localChannelReference
     * @throws OpenR66ProtocolPacketException
     */
    public static void writeEndTransfer(
            LocalChannelReference localChannelReference)
    throws OpenR66ProtocolPacketException {
        EndTransferPacket packet = new EndTransferPacket(
                LocalPacketFactory.REQUESTPACKET);
        localChannelReference.sessionNewState(R66FiniteDualStates.ENDTRANSFERS);
        writeAbstractLocalPacket(localChannelReference, packet);
    }
    /**
     * Write an AbstractLocalPacket to the network Channel
     * @param localChannelReference
     * @param packet
     * @return the ChannelFuture on write operation
     * @throws OpenR66ProtocolPacketException
     */
    public static ChannelFuture writeAbstractLocalPacket(
            LocalChannelReference localChannelReference, AbstractLocalPacket packet)
    throws OpenR66ProtocolPacketException {
        NetworkPacket networkPacket;
        try {
            networkPacket = new NetworkPacket(localChannelReference
                    .getLocalId(), localChannelReference.getRemoteId(), packet);
        } catch (OpenR66ProtocolPacketException e) {
            logger.error("Cannot construct message from " + packet.toString(),
                    e);
            throw e;
        }
        return Channels.write(localChannelReference.getNetworkChannel(), networkPacket);
    }

    /**
     * Write an AbstractLocalPacket to the Local Channel
     * @param localChannelReference
     * @param packet
     * @return the ChannelFuture on write operation
     * @throws OpenR66ProtocolPacketException
     */
    public static ChannelFuture writeAbstractLocalPacketToLocal(
            LocalChannelReference localChannelReference, AbstractLocalPacket packet)
    throws OpenR66ProtocolPacketException {
        return Channels.write(localChannelReference.getLocalChannel(), packet);
    }
    /**
     * Exit global ChannelFactory
     */
    public static void exit() {
        Configuration.configuration.constraintLimitHandler.release();
        // First try to StopAll
        TransferUtils.stopSelectedTransfers(DbConstant.admin.session, 0,
                null, null, null, null, null, null, null, null, null, true, true, true);
        Configuration.configuration.isShutdown = true;
        Configuration.configuration.prepareServerStop();
        final long delay = Configuration.configuration.TIMEOUTCON;
        // Inform others that shutdown
        Configuration.configuration.getLocalTransaction()
                .shutdownLocalChannels();
        logger.warn("Exit: Give a delay of " + delay + " ms");
        try {
            Thread.sleep(delay);
        } catch (final InterruptedException e) {
        }
        NetworkTransaction.closeRetrieveExecutors();
        Configuration.configuration.getLocalTransaction().debugPrintActiveLocalChannels();
        Configuration.configuration.getGlobalTrafficShapingHandler()
                .releaseExternalResources();
        logger.debug("Exit Shutdown Command");
        terminateCommandChannels();
        logger.debug("Exit Shutdown Local");
        Configuration.configuration.getLocalTransaction().closeAll();
        logger.debug("Exit Shutdown Http");
        terminateHttpChannels();
        if (Configuration.configuration.useLocalExec) {
            LocalExecClient.releaseResources();
        }
        DbAdmin.closeAllConnection();
        Configuration.configuration.serverStop();
        System.err.println("Exit end of Shutdown");
        Thread.currentThread().interrupt();
    }

    public static void stopLogger() {
        if (GgInternalLoggerFactory.getDefaultFactory() instanceof GgSlf4JLoggerFactory) {
            LoggerContext lc = (LoggerContext) LoggerFactory.getILoggerFactory();
            lc.stop();
        }
    }
    /**
     * This function is the top function to be called when the server is to be
     * shutdown.
     */
    @Override
    public void run() {
        OpenR66SignalHandler.terminate(false);
    }
}
