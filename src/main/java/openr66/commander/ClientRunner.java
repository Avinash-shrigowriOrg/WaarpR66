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
package openr66.commander;

import goldengate.common.database.data.AbstractDbData;
import goldengate.common.database.data.AbstractDbData.UpdatedInfo;
import goldengate.common.database.exception.GoldenGateDatabaseException;
import goldengate.common.logging.GgInternalLogger;
import goldengate.common.logging.GgInternalLoggerFactory;

import java.net.SocketAddress;
import java.util.concurrent.ConcurrentHashMap;

import openr66.client.RecvThroughHandler;
import openr66.context.ErrorCode;
import openr66.context.R66FiniteDualStates;
import openr66.context.R66Result;
import openr66.context.authentication.R66Auth;
import openr66.context.task.exception.OpenR66RunnerErrorException;
import openr66.database.DbConstant;
import openr66.database.data.DbHostAuth;
import openr66.database.data.DbTaskRunner;
import openr66.database.data.DbTaskRunner.TASKSTEP;
import openr66.protocol.configuration.Configuration;
import openr66.protocol.exception.OpenR66ProtocolNoConnectionException;
import openr66.protocol.exception.OpenR66ProtocolNotYetConnectionException;
import openr66.protocol.exception.OpenR66ProtocolPacketException;
import openr66.protocol.localhandler.LocalChannelReference;
import openr66.protocol.localhandler.packet.EndRequestPacket;
import openr66.protocol.localhandler.packet.RequestPacket;
import openr66.protocol.networkhandler.NetworkTransaction;
import openr66.protocol.utils.ChannelUtils;
import openr66.protocol.utils.R66Future;
import openr66.protocol.utils.TransferUtils;

import org.jboss.netty.channel.Channels;

/**
 * Client Runner from a TaskRunner
 *
 * @author Frederic Bregier
 *
 */
public class ClientRunner extends Thread {
    /**
     * Internal Logger
     */
    private static final GgInternalLogger logger = GgInternalLoggerFactory
            .getLogger(ClientRunner.class);

    private static final ConcurrentHashMap<String, Integer> taskRunnerRetryHashMap
         = new ConcurrentHashMap<String, Integer>();

    private final NetworkTransaction networkTransaction;
    private final DbTaskRunner taskRunner;
    private final R66Future futureRequest;
    private RecvThroughHandler handler = null;
    private LocalChannelReference localChannelReference = null;

    public ClientRunner(NetworkTransaction networkTransaction, DbTaskRunner taskRunner,
            R66Future futureRequest) {
        this.networkTransaction = networkTransaction;
        this.taskRunner = taskRunner;
        this.futureRequest = futureRequest;
    }

    /* (non-Javadoc)
     * @see java.lang.Runnable#run()
     */
    @Override
    public void run() {
        R66Future transfer;
        try {
            transfer = this.runTransfer();
        } catch (OpenR66RunnerErrorException e) {
            logger.error("Runner Error: {} {}", e.getMessage(), taskRunner.toShortString());
            return;
        } catch (OpenR66ProtocolNoConnectionException e) {
            logger.error("No connection Error {}", e.getMessage());
            if (localChannelReference != null) {
                localChannelReference.setErrorMessage(ErrorCode.ConnectionImpossible.mesg,
                    ErrorCode.ConnectionImpossible);
            }
            taskRunner.setErrorTask(localChannelReference);
            try {
                taskRunner.saveStatus();
            	taskRunner.run();
            } catch (OpenR66RunnerErrorException e1) {
            	this.changeUpdatedInfo(UpdatedInfo.INERROR, ErrorCode.ConnectionImpossible);
            }
            return;
        } catch (OpenR66ProtocolPacketException e) {
            logger.error("Protocol Error", e);
            return;
        } catch (OpenR66ProtocolNotYetConnectionException e) {
            logger.debug("No connection warning {}", e.getMessage());
            return;
        }
        R66Result result = transfer.getResult();
        if (result != null) {
            if (result.code == ErrorCode.QueryAlreadyFinished) {
                logger.warn("TRANSFER RESULT:\n    "+(transfer.isSuccess()?"SUCCESS":"FAILURE")+"\n    "+
                        ErrorCode.QueryAlreadyFinished.mesg+":"+
                        (result != null ? result.toString() : "no result"));
            } else {
                if (transfer.isSuccess()){
                    logger.info("TRANSFER RESULT:\n    SUCCESS\n    "+
                            (result != null ? result.toString() : "no result"));
                } else {
                    logger.error("TRANSFER RESULT:\n    FAILURE\n    "+
                            (result != null ? result.toString() : "no result"));
                }
            }
        } else {
            if (transfer.isSuccess()){
                logger.warn("TRANSFER REQUESTED RESULT:\n    SUCCESS\n    no result");
            } else {
                logger.error("TRANSFER REQUESTED RESULT:\n    FAILURE\n    no result");
            }
        }
        transfer = null;
        Thread.currentThread().setName("Finished_"+Thread.currentThread().getName());
    }
    /**
     *
     * @param runner
     * @param limit
     * @return True if the task was run less than limit, else False
     */
    public boolean incrementTaskRunerTry(DbTaskRunner runner, int limit) {
        String key = runner.getKey();
        Integer tries = taskRunnerRetryHashMap.get(key);
        logger.debug("try to find integer: "+tries);
        if (tries == null) {
            tries = new Integer(1);
        } else {
            tries = tries+1;
        }
        if (limit <= tries) {
            taskRunnerRetryHashMap.remove(key);
            return false;
        } else {
            taskRunnerRetryHashMap.put(key, tries);
            return true;
        }
    }

    /**
     * True transfer run (can be called directly to enable exception outside any executors)
     * @return The R66Future of the transfer operation
     * @throws OpenR66RunnerErrorException
     * @throws OpenR66ProtocolNoConnectionException
     * @throws OpenR66ProtocolPacketException
     * @throws OpenR66ProtocolNotYetConnectionException
     */
    public R66Future runTransfer()
    throws OpenR66RunnerErrorException, OpenR66ProtocolNoConnectionException,
    OpenR66ProtocolPacketException, OpenR66ProtocolNotYetConnectionException {
        logger.debug("Start attempt Transfer");
        localChannelReference = initRequest();
        try {
            localChannelReference.getFutureValidRequest().await();
        } catch (InterruptedException e) {
        }
        if (localChannelReference.getFutureValidRequest().isSuccess()) {
            return finishTransfer(true, localChannelReference);
        } else if (localChannelReference.getFutureValidRequest().getResult().code ==
            ErrorCode.ServerOverloaded) {
            return tryAgainTransferOnOverloaded(true, localChannelReference);
        } else return finishTransfer(true, localChannelReference);
    }
    /**
     * In case an overloaded signal is returned by the requested
     * @param retry if True, it will retry in case of overloaded remote server, else it just stops
     * @param localChannelReference
     * @return The R66Future of the transfer operation
     * @throws OpenR66RunnerErrorException
     * @throws OpenR66ProtocolNoConnectionException
     * @throws OpenR66ProtocolPacketException
     * @throws OpenR66ProtocolNotYetConnectionException
     */
    public R66Future tryAgainTransferOnOverloaded(boolean retry, LocalChannelReference localChannelReference) 
    throws OpenR66RunnerErrorException, OpenR66ProtocolNoConnectionException, 
    OpenR66ProtocolPacketException, OpenR66ProtocolNotYetConnectionException {
        if (this.localChannelReference == null) {
            this.localChannelReference = localChannelReference;
        }
        boolean incRetry = incrementTaskRunerTry(taskRunner, Configuration.RETRYNB);
        logger.debug("tryAgainTransferOnOverloaded: "+retry+":"+incRetry);
        switch (taskRunner.getUpdatedInfo()) {
            case DONE:
            case INERROR:
            case INTERRUPTED:
                break;
            default:
                this.changeUpdatedInfo(UpdatedInfo.INERROR, ErrorCode.ServerOverloaded);
        }
        // redo if possible
        if (retry && incRetry) {
            try {
                Thread.sleep(Configuration.configuration.constraintLimitHandler.getSleepTime());
            } catch (InterruptedException e) {
            }
            return runTransfer();
        } else {
            if (localChannelReference == null) {
                taskRunner.setLocalChannelReference(new LocalChannelReference());
            }
            taskRunner.getLocalChannelReference().setErrorMessage(ErrorCode.ConnectionImpossible.mesg,
                    ErrorCode.ConnectionImpossible);
            this.taskRunner.setErrorTask(localChannelReference);
            this.taskRunner.run();
            throw new OpenR66ProtocolNoConnectionException("End of retry on ServerOverloaded");
        }
    }
    /**
     * Finish the transfer (called at the end of runTransfer)
     * 
     * @param retry if True, it will retry in case of overloaded remote server, else it just stops
     * @param localChannelReference
     * @return The R66Future of the transfer operation
     * @throws OpenR66ProtocolNotYetConnectionException 
     * @throws OpenR66ProtocolPacketException 
     * @throws OpenR66ProtocolNoConnectionException 
     * @throws OpenR66RunnerErrorException 
     */
    public R66Future finishTransfer(boolean retry, LocalChannelReference localChannelReference) 
    throws OpenR66RunnerErrorException {
        if (this.localChannelReference == null) {
            this.localChannelReference = localChannelReference;
        }
        R66Future transfer = localChannelReference.getFutureRequest();
        try {
            transfer.await();
        } catch (InterruptedException e1) {
        }
        taskRunnerRetryHashMap.remove(taskRunner.getKey());
        logger.info("Request done with {}",(transfer.isSuccess()?"success":"error"));
        Channels.close(localChannelReference.getLocalChannel());
        // now reload TaskRunner if it still exists (light client can forget it)
        if (transfer.isSuccess()) {
            try {
                taskRunner.select();
            } catch (GoldenGateDatabaseException e) {
                logger.debug("Not a problem but cannot find at the end the task");
                taskRunner.setFrom(transfer.runner);
            }
            this.changeUpdatedInfo(UpdatedInfo.DONE, ErrorCode.CompleteOk);
        } else {
            try {
                taskRunner.select();
            } catch (GoldenGateDatabaseException e) {
                logger.debug("Not a problem but cannot find at the end the task");
                taskRunner.setFrom(transfer.runner);
            }
            // Case when we were interrupted
            if (transfer.getResult() == null) {
                switch (taskRunner.getUpdatedInfo()) {
                    case DONE:
                        R66Result ok =
                            new R66Result(null, true, ErrorCode.CompleteOk, taskRunner);
                        transfer.setResult(ok);
                        transfer.setSuccess();
                        this.changeUpdatedInfo(UpdatedInfo.DONE, ErrorCode.CompleteOk);
                        break;
                    case INERROR:
                    case INTERRUPTED:
                    default:
                        R66Result error =
                            new R66Result(null, true, ErrorCode.Internal, taskRunner);
                        transfer.setResult(error);
                        transfer.cancel();
                        this.changeUpdatedInfo(UpdatedInfo.INERROR, ErrorCode.Internal);
                }
                return transfer;
            }
            if (transfer.getResult().code == ErrorCode.QueryAlreadyFinished) {
                // check if post task to execute
                logger.warn("WARN QueryAlreadyFinished:\n    "+transfer.toString()+"\n    "+
                        taskRunner.toShortString());
                try {
                    TransferUtils.finalizeTaskWithNoSession(taskRunner, localChannelReference);
                } catch (OpenR66RunnerErrorException e) {
                    this.taskRunner.changeUpdatedInfo(UpdatedInfo.INERROR);
                    try {
                        this.taskRunner.update();
                    } catch (GoldenGateDatabaseException e1) {
                    }
                }
            } else {
                switch (taskRunner.getUpdatedInfo()) {
                    case DONE:
                    case INERROR:
                    case INTERRUPTED:
                        break;
                    default:
                        this.changeUpdatedInfo(UpdatedInfo.INERROR, transfer.getResult().code);
                }
            }
        }
        return transfer;
    }
    /**
     * Initialize the request
     * @return the localChannelReference holding the transfer request
     * @throws OpenR66ProtocolNoConnectionException
     * @throws OpenR66RunnerErrorException
     * @throws OpenR66ProtocolPacketException
     * @throws OpenR66ProtocolNotYetConnectionException
     */
    public LocalChannelReference initRequest()
        throws OpenR66ProtocolNoConnectionException, OpenR66RunnerErrorException,
        OpenR66ProtocolPacketException, OpenR66ProtocolNotYetConnectionException {
        this.changeUpdatedInfo(UpdatedInfo.RUNNING, ErrorCode.Running);
        long id = taskRunner.getSpecialId();
        String tid;
        if (id == DbConstant.ILLEGALVALUE) {
            tid = taskRunner.getRuleId()+"_"+taskRunner.getMode()+"_NEWTRANSFER";
        } else {
            tid = taskRunner.getRuleId()+"_"+taskRunner.getMode()+"_"+id;
        }
        Thread.currentThread().setName(tid);
        logger.debug("Will run {}",this.taskRunner);
        boolean restartPost = false;
        if (taskRunner.getGloballaststep() == TASKSTEP.POSTTASK.ordinal()) {
            // Send a validation to requested
            if (! taskRunner.isSelfRequested()) {
                // restart
                restartPost = true;
            }
        }
        if (taskRunner.isSelfRequested()) {
            // Don't have to restart a task for itself (or should use requester)
            logger.warn("Requested host cannot initiate itself the request");
            this.changeUpdatedInfo(UpdatedInfo.INERROR, ErrorCode.LoopSelfRequestedHost);
            throw new OpenR66ProtocolNoConnectionException("Requested host cannot initiate itself the request");
        }
        DbHostAuth host = R66Auth.getServerAuth(DbConstant.admin.session,
                taskRunner.getRequested());
        if (host == null) {
            logger.error("Requested host cannot be found: "+taskRunner.getRequested());
            //taskRunner.setExecutionStatus(ErrorCode.NotKnownHost);
            this.changeUpdatedInfo(UpdatedInfo.INERROR, ErrorCode.NotKnownHost);
            throw new OpenR66ProtocolNoConnectionException("Requested host cannot be found "+
                    taskRunner.getRequested());
        }
        if (host.isClient()) {
            logger.debug("Cannot initiate a connection with a client: {}",
                    host);
            this.changeUpdatedInfo(UpdatedInfo.INERROR, ErrorCode.ConnectionImpossible);
            throw new OpenR66ProtocolNoConnectionException("Cannot connect to client "+
                    host.toString());
        }
        SocketAddress socketAddress = host.getSocketAddress();
        boolean isSSL = host.isSsl();

        LocalChannelReference localChannelReference = networkTransaction
            .createConnectionWithRetry(socketAddress, isSSL, futureRequest);
        taskRunner.setLocalChannelReference(localChannelReference);
        socketAddress = null;
        if (localChannelReference == null) {
            // propose to redo
            // See if reprogramming is ok (not too many tries)
            String retry;
            if (incrementTaskRunerTry(taskRunner, Configuration.RETRYNB)) {
                logger.warn("Will retry since Cannot connect to {}", host);
                retry = " but will retry";
                // now wait
                try {
                    Thread.sleep(Configuration.configuration.delayRetry);
                } catch (InterruptedException e) {
                }
                this.changeUpdatedInfo(UpdatedInfo.TOSUBMIT, ErrorCode.ConnectionImpossible);
                throw new OpenR66ProtocolNotYetConnectionException("Cannot connect to server "+
                        host.toString()+retry);
            } else {
                logger.debug("Will not retry since limit of connection attemtps is reached for {}",
                        host);
                retry = " and retries limit is reached so stop here";
                this.changeUpdatedInfo(UpdatedInfo.INERROR, ErrorCode.ConnectionImpossible);
                taskRunner.setLocalChannelReference(new LocalChannelReference());
                throw new OpenR66ProtocolNoConnectionException("Cannot connect to server "+
                        host.toString()+retry);
            }
        }
        //taskRunnerRetryHashMap.remove(taskRunner.getKey());
        if (handler != null) {
            localChannelReference.setRecvThroughHandler(handler);
        }
        if (restartPost) {
            localChannelReference.setClientRunner(this);
            R66Result finalValue = new R66Result(localChannelReference.getSession(), true,
                    ErrorCode.CompleteOk, taskRunner);
            localChannelReference.sessionNewState(R66FiniteDualStates.ENDREQUESTS);
            taskRunner.quickFinalizeOnPost(localChannelReference, finalValue);
            EndRequestPacket validPacket = new EndRequestPacket(ErrorCode.CompleteOk.ordinal());
            if (! taskRunner.isSender()) {
                localChannelReference.sessionNewState(R66FiniteDualStates.ENDREQUESTR);
                validPacket.validate();
            }
            try {
                ChannelUtils.writeAbstractLocalPacket(localChannelReference, validPacket).awaitUninterruptibly();
            } catch (OpenR66ProtocolPacketException e) {
            }
            if (!localChannelReference.getFutureRequest().awaitUninterruptibly(
                    Configuration.configuration.TIMEOUTCON)) {
                // valid it however
                taskRunner.setAllDone();
                try {
                    taskRunner.saveStatus();
                } catch (OpenR66RunnerErrorException e) {
                    // ignore
                }
                localChannelReference.validateRequest(localChannelReference
                    .getFutureEndTransfer().getResult());
            }
            logger.debug("Wait for request to {}",host);
            host = null;
            return localChannelReference;
        }
        // If Requester is NOT Sender, and if TransferTask then decrease now if possible the rank
        if (!taskRunner.isSender() && (taskRunner.getGloballaststep() == 
            TASKSTEP.TRANSFERTASK.ordinal())) {
            logger.debug("Requester is not Sender so decrease if possible the rank {}", 
                    taskRunner);
            taskRunner.restartRank();
            taskRunner.saveStatus();
            logger.debug("Requester is not Sender so new rank is "+taskRunner.getRank()+ " {}", 
                    taskRunner);
        }
        RequestPacket request = taskRunner.getRequest();
        logger.debug("Will send request {} ",request);
        localChannelReference.setClientRunner(this);
        localChannelReference.sessionNewState(R66FiniteDualStates.REQUESTR);
        try {
            ChannelUtils.writeAbstractLocalPacket(localChannelReference, request);
        } catch (OpenR66ProtocolPacketException e) {
            // propose to redo
            logger.warn("Cannot transfer request to "+host.toString());
            this.changeUpdatedInfo(UpdatedInfo.INTERRUPTED, ErrorCode.Internal);
            Channels.close(localChannelReference.getLocalChannel());
            localChannelReference = null;
            host = null;
            request = null;
            throw e;
        }
        logger.debug("Wait for request to {}",host);
        request = null;
        host = null;
        return localChannelReference;
    }
    /**
     * Change the UpdatedInfo of the current runner
     * @param info
     */
    public void changeUpdatedInfo(AbstractDbData.UpdatedInfo info, ErrorCode code) {
        this.taskRunner.changeUpdatedInfo(info);
        this.taskRunner.setErrorExecutionStatus(code);
        try {
            this.taskRunner.update();
        } catch (GoldenGateDatabaseException e) {
        }
    }

    /**
     * @param handler the handler to set
     */
    public void setRecvThroughHandler(RecvThroughHandler handler) {
        this.handler = handler;
    }
}
