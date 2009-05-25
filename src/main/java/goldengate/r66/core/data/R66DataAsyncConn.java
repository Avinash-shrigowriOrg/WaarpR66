/**
 * Copyright 2009, Frederic Bregier, and individual contributors
 * by the @author tags. See the COPYRIGHT.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 3.0 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package goldengate.r66.core.data;

import goldengate.common.command.exception.Reply425Exception;
import goldengate.common.logging.GgInternalLogger;
import goldengate.common.logging.GgInternalLoggerFactory;
import goldengate.ftp.core.command.FtpArgumentCode;
import goldengate.ftp.core.command.FtpArgumentCode.TransferMode;
import goldengate.ftp.core.command.FtpArgumentCode.TransferStructure;
import goldengate.ftp.core.command.FtpArgumentCode.TransferType;
import goldengate.ftp.core.config.FtpConfiguration;
import goldengate.ftp.core.data.handler.DataNetworkHandler;
import goldengate.ftp.core.exception.FtpNoConnectionException;
import goldengate.ftp.core.session.FtpSession;
import goldengate.ftp.core.utils.FtpChannelUtils;

import java.net.InetAddress;
import java.net.InetSocketAddress;

import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.Channels;

/**
 * Main class that handles Data connection using asynchronous connection with
 * Netty
 *
 * @author Frederic Bregier
 *
 */
public class R66DataAsyncConn {
    /**
     * Internal Logger
     */
    private static final GgInternalLogger logger = GgInternalLoggerFactory
            .getLogger(R66DataAsyncConn.class);

    /**
     * SessionInterface
     */
    private final FtpSession session;

    /**
     * Current Data Network Handler
     */
    private DataNetworkHandler dataNetworkHandler = null;

    /**
     * Data Channel with the client
     */
    private Channel dataChannel = null;

    /**
     * External address of the client (active)
     */
    private InetSocketAddress remoteAddress = null;

    /**
     * Local listening address for the server (passive)
     */
    private InetSocketAddress localAddress = null;

    /**
     * Active: the connection is done from the Server to the Client on this
     * remotePort Passive: not used
     */
    private int remotePort = -1;

    /**
     * Active: the connection is done from the Server from this localPort to the
     * Client Passive: the connection is done from the Client to the Server on
     * this localPort
     */
    private int localPort = -1;

    /**
     * Is the connection passive
     */
    private boolean passiveMode = false;

    /**
     * Is the server binded (active or passive, but mainly passive)
     */
    private boolean isBind = false;

    /**
     * The R66TransferControl
     */
    private final R66TransferControl transferControl;

    /**
     * Current TransferType. Default ASCII
     */
    private FtpArgumentCode.TransferType transferType = FtpArgumentCode.TransferType.ASCII;

    /**
     * Current TransferSubType. Default NONPRINT
     */
    private FtpArgumentCode.TransferSubType transferSubType = FtpArgumentCode.TransferSubType.NONPRINT;

    /**
     * Current TransferStructure. Default FILE
     */
    private FtpArgumentCode.TransferStructure transferStructure = FtpArgumentCode.TransferStructure.FILE;

    /**
     * Current TransferMode. Default Stream
     */
    private FtpArgumentCode.TransferMode transferMode = FtpArgumentCode.TransferMode.STREAM;

    /**
     * Constructor for Active session by default
     *
     * @param session
     */
    public R66DataAsyncConn(FtpSession session) {
        this.session = session;
        dataChannel = null;
        remoteAddress = FtpChannelUtils
                .getRemoteInetSocketAddress(this.session.getControlChannel());
        remotePort = remoteAddress.getPort();
        setDefaultLocalPort();
        localAddress = new InetSocketAddress(FtpChannelUtils
                .getLocalInetAddress(this.session.getControlChannel()),
                localPort);
        passiveMode = false;
        isBind = false;
        transferControl = new R66TransferControl(session);
    }

    /**
     * Clear the Data Connection
     *
     */
    public void clear() {
        unbindPassive();
        transferControl.clear();
        passiveMode = false;
        remotePort = -1;
        localPort = -1;
    }

    /**
     * Set the local port to the default (20)
     *
     */
    private void setDefaultLocalPort() {
        setLocalPort(session.getConfiguration().getServerPort() - 1);// Default
        // L-1
    }

    /**
     * Set the Local Port (Active or Passive)
     *
     * @param localPort
     */
    public void setLocalPort(int localPort) {
        this.localPort = localPort;
    }

    /**
     * @return the local address
     */
    public InetSocketAddress getLocalAddress() {
        return localAddress;
    }

    /**
     * @return the remote address
     */
    public InetSocketAddress getRemoteAddress() {
        return remoteAddress;
    }

    /**
     * @return the remotePort
     */
    public int getRemotePort() {
        return remotePort;
    }

    /**
     * @return the localPort
     */
    public int getLocalPort() {
        return localPort;
    }

    /**
     * Change to active connection (don't change localPort)
     *
     * @param address
     *            remote address
     */
    public void setActive(InetSocketAddress address) {
        unbindPassive();
        remoteAddress = address;
        passiveMode = false;
        isBind = false;
        remotePort = remoteAddress.getPort();
    }

    /**
     * Change to passive connection (all necessaries informations like local
     * port should have been set)
     */
    public void setPassive() {
        unbindPassive();
        localAddress = new InetSocketAddress(FtpChannelUtils
                .getLocalInetAddress(session.getControlChannel()),
                localPort);
        passiveMode = true;
        isBind = false;
        //logger.debug("Passive prepared");
    }

    /**
     * @return the passiveMode
     */
    public boolean isPassiveMode() {
        return passiveMode;
    }

    /**
     *
     * @return True if the connection is bind (active = connected, passive = not
     *         necessarily connected)
     */
    public boolean isBind() {
        return isBind;
    }

    /**
     * Is the Data dataChannel connected
     *
     * @return True if the dataChannel is connected
     */
    public boolean isConnected() {
        return dataChannel != null && dataChannel.isConnected();
    }

    /**
     * @return the transferMode
     */
    public FtpArgumentCode.TransferMode getMode() {
        return transferMode;
    }

    /**
     * @param transferMode
     *            the transferMode to set
     */
    public void setMode(FtpArgumentCode.TransferMode transferMode) {
        this.transferMode = transferMode;
        setCorrectCodec();
    }

    /**
     * @return the transferStructure
     */
    public FtpArgumentCode.TransferStructure getStructure() {
        return transferStructure;
    }

    /**
     * @param transferStructure
     *            the transferStructure to set
     */
    public void setStructure(FtpArgumentCode.TransferStructure transferStructure) {
        this.transferStructure = transferStructure;
        setCorrectCodec();
    }

    /**
     * @return the transferSubType
     */
    public FtpArgumentCode.TransferSubType getSubType() {
        return transferSubType;
    }

    /**
     * @param transferSubType
     *            the transferSubType to set
     */
    public void setSubType(FtpArgumentCode.TransferSubType transferSubType) {
        this.transferSubType = transferSubType;
        setCorrectCodec();
    }

    /**
     * @return the transferType
     */
    public FtpArgumentCode.TransferType getType() {
        return transferType;
    }

    /**
     * @param transferType
     *            the transferType to set
     */
    public void setType(FtpArgumentCode.TransferType transferType) {
        this.transferType = transferType;
        setCorrectCodec();
    }

    /**
     *
     * @return True if the current mode for data connection is FileInterface +
     *         (Stream or Block) + (Ascii or Image)
     */
    public boolean isFileStreamBlockAsciiImage() {
        return transferStructure == TransferStructure.FILE &&
                (transferMode == TransferMode.STREAM || transferMode == TransferMode.BLOCK) && (transferType == TransferType.ASCII || transferType == TransferType.IMAGE);
    }

    /**
     *
     * @return True if the current mode for data connection is Stream
     */
    public boolean isStreamFile() {
        return transferMode == TransferMode.STREAM && transferStructure == TransferStructure.FILE;
    }

    /**
     * This function must be called after any changes of parameters, ie after
     * MODE, STRU, TYPE
     *
     */
    private void setCorrectCodec() {
        try {
            getDataNetworkHandler().setCorrectCodec();
        } catch (FtpNoConnectionException e) {
        }
    }

    /**
     * Unbind passive connection when close the Data Channel (from
     * channelClosed())
     *
     */
    public void unbindPassive() {
        if (isBind && passiveMode) {
            isBind = false;
            InetSocketAddress local = getLocalAddress();
            if (dataChannel != null && dataChannel.isConnected()) {
                //logger.debug("PASSIVE MODE CLOSE");
                Channels.close(dataChannel);
            }
            //logger.debug("Passive mode unbind");
            session.getConfiguration().getFtpInternalConfiguration()
                    .unbindPassive(local);
            // Previous mode was Passive so remove the current configuration if
            // any
            InetAddress remote = remoteAddress.getAddress();
            session.getConfiguration().delFtpSession(remote, local);
        }
        dataChannel = null;
        dataNetworkHandler = null;
    }

    /**
     * Initialize the socket from Server side (only used in Passive)
     *
     * @return True if OK
     * @throws Reply425Exception
     */
    public boolean initPassiveConnection() throws Reply425Exception {
        unbindPassive();
        if (passiveMode) {
            // Connection is enable but the client will do the real connection
            session.getConfiguration().getFtpInternalConfiguration()
                    .bindPassive(getLocalAddress());
            //logger.debug("Passive mode ready");
            isBind = true;
            return true;
        }
        // Connection is already prepared
        return true;
    }

    /**
     * Return the current Data Channel
     *
     * @return the current Data Channel
     * @throws FtpNoConnectionException
     */
    public Channel getCurrentDataChannel() throws FtpNoConnectionException {
        if (dataChannel == null) {
            throw new FtpNoConnectionException("No Data Connection active");
        }
        return dataChannel;
    }

    /**
     *
     * @return the DataNetworkHandler
     * @throws FtpNoConnectionException
     */
    public DataNetworkHandler getDataNetworkHandler()
            throws FtpNoConnectionException {
        if (dataNetworkHandler == null) {
            throw new FtpNoConnectionException("No Data Connection active");
        }
        return dataNetworkHandler;
    }

    /**
     *
     * @param dataNetworkHandler
     *            the {@link DataNetworkHandler} to set
     */
    public void setDataNetworkHandler(DataNetworkHandler dataNetworkHandler) {
        this.dataNetworkHandler = dataNetworkHandler;
    }

    /**
     *
     * @param configuration
     * @return a new Passive Port
     */
    public static int getNewPassivePort(FtpConfiguration configuration) {
        return configuration.getNextRangePort();
    }

    /**
     * @return The current status in String of the different parameters
     */
    public String getStatus() {
        StringBuilder builder = new StringBuilder("Data connection: ");
        builder.append((isConnected()? "connected " : "not connected "));
        builder.append((isBind()? "bind " : "not bind "));
        builder.append((isPassiveMode()? "passive mode" : "active mode"));
        builder.append('\n');
        builder.append("Mode: ");
        builder.append(transferMode.name());
        builder.append('\n');
        builder.append("Structure: ");
        builder.append(transferStructure.name());
        builder.append('\n');
        builder.append("Type: ");
        builder.append(transferType.name());
        builder.append(' ');
        builder.append(transferSubType.name());
        return builder.toString();
    }

    /**
	 *
	 */
    @Override
    public String toString() {
        return getStatus().replace('\n', ' ');
    }

    /**
     *
     * @return the R66TransferControl
     */
    public R66TransferControl getFtpTransferControl() {
        return transferControl;
    }

    /**
     * Set the new connected Data Channel
     *
     * @param dataChannel the new Data Channel
     * @throws InterruptedException
     * @throws Reply425Exception
     */
    public void setNewOpenedDataChannel(Channel dataChannel) throws InterruptedException,
            Reply425Exception {
        this.dataChannel = dataChannel;
        if (dataChannel == null) {
            String curmode = null;
            if (isPassiveMode()) {
                curmode = "passive";
            } else {
                curmode = "active";
            }
            //logger.debug("Connection impossible in {} mode", curmode);
            // Cannot open connection
            throw new Reply425Exception("Cannot open " + curmode +
                    " data connection");
        }
        isBind = true;
    }
}
