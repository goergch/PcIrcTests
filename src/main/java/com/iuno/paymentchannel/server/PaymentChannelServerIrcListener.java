package com.iuno.paymentchannel.server;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.protobuf.ByteString;
import com.iuno.paymentchannel.PcIrcMessage;
import com.iuno.paymentchannel.PcIrcMessageRegistry;
import com.iuno.paymentchannel.messages.*;
import com.lambdaworks.codec.Base64;
import com.subgraph.orchid.encoders.Hex;
import org.bitcoin.paymentchannel.Protos;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.TransactionBroadcaster;
import org.bitcoinj.protocols.channels.PaymentChannelCloseException;
import org.bitcoinj.protocols.channels.PaymentChannelServer;
import org.bitcoinj.protocols.channels.ServerConnectionEventHandler;
import org.bitcoinj.wallet.Wallet;
import org.pircbotx.Configuration;
import org.pircbotx.MultiBotManager;
import org.pircbotx.PircBotX;
import org.pircbotx.exception.IrcException;
import org.pircbotx.hooks.ListenerAdapter;
import org.pircbotx.hooks.events.MessageEvent;
import org.pircbotx.hooks.events.PrivateMessageEvent;
import org.pircbotx.hooks.types.GenericMessageEvent;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.HashMap;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Created by goergch on 16.06.17.
 */
public class PaymentChannelServerIrcListener extends ListenerAdapter {
    public static final String CHANNEL = "#tdmiunopaymentchannel";
    private final Wallet wallet;
    private final TransactionBroadcaster broadcaster;

    // The event handler factory which creates new ServerConnectionEventHandler per connection
    private final HandlerFactory eventHandlerFactory;
    private final Coin minAcceptedChannelSize;
    private final PcIrcMessageRegistry messageRegistry;
    private PircBotX botX;

    private HashMap<String, ServerHandler> handlerHashMap = new HashMap<String, ServerHandler>();
    private int dataToBeReceived = 0;
    private StringBuilder builder;

    private HashMap<String, ECKey>receivingAddresses = new HashMap<String, ECKey>();





    public void addReceivingKey(ECKey ecKey) {
        receivingAddresses.put(ecKey.getPublicKeyAsHex().substring(0,10), ecKey);
    }

    public interface HandlerFactory {
        /**
         * Called when a new connection completes version handshake to get a new connection-specific listener.
         * If null is returned, the connection is immediately closed.
         */
        @Nullable
        ServerConnectionEventHandler onNewConnection(String ircClient);
    }

    public PaymentChannelServerIrcListener(TransactionBroadcaster broadcaster, Wallet wallet, Coin minAcceptedChannelSize,
                                        HandlerFactory eventHandlerFactory) throws IOException {

        this.wallet = checkNotNull(wallet);
        this.broadcaster = checkNotNull(broadcaster);
        this.eventHandlerFactory = checkNotNull(eventHandlerFactory);
        this.minAcceptedChannelSize = checkNotNull(minAcceptedChannelSize);
        this.messageRegistry = PcIrcMessageRegistry.createStandardRegistry();
    }

    public void start(String ircAddress) throws IOException, IrcException {
//        Configuration configuration = new Configuration.Builder()
//                .setName(ircAddress) //Set the nick of the bot. CHANGE IN YOUR CODE
//                .addServer("irc.freenode.net") //Join the freenode network
//                .addListener(this) //Add our listener that will be called on Events
//                .buildConfiguration();
//        botX = new PircBotX(configuration);
//        botX.startBot();

        Configuration.Builder templateConfig = new Configuration.Builder()
                .setName(ircAddress)
                .setAutoNickChange(true)
                .addListener(this)
                .addAutoJoinChannel(CHANNEL);

        MultiBotManager manager = new MultiBotManager();
        manager.addBot(templateConfig.buildForServer("irc.freenode.net"));
        manager.start();
        botX = manager.getBotById(0);
    }


    @Override
    public void onMessage(MessageEvent event) throws Exception {

        String message = event.getMessage();
        String receiverAddress = message.substring(0,10);
        String messageType = message.substring(20,24);
        if(!receivingAddresses.containsKey(receiverAddress)){
            return;
        }
        PcIrcMessage pcMessage = null;
        if(!messageRegistry.contains(messageType)){
           return;
        }
        pcMessage = messageRegistry.get(messageType).create(message);

        ECKey receivingKey = receivingAddresses.get(pcMessage.getReceiverAddress());
        String senderAddress = pcMessage.getSenderAddress();

        if(pcMessage.getClass().equals(PcIrcConnectMessage.class)){
            PcIrcConnectMessage connectMessage = (PcIrcConnectMessage)pcMessage;

            if(!connectMessage.getReceiverPubKey().equals(receivingKey.getPublicKeyAsHex())){
                //TODO log something
                return;
            }

            if(handlerHashMap.containsKey(receiverAddress+senderAddress) ){
                //The connection might have been broken
                handlerHashMap.remove(receiverAddress+senderAddress);
            }
            handlerHashMap.put(receiverAddress+senderAddress,
                    new ServerHandler(receiverAddress+senderAddress, receiverAddress, senderAddress,
                            receivingKey, ECKey.fromPublicOnly(Hex.decode(connectMessage.getSenderPubKey()))));
            handlerHashMap.get(receiverAddress+senderAddress).connectionOpen(connectMessage.getChallenge());
        }else if(pcMessage.getClass().equals(PcIrcDisconnectMessage.class)) {
            if (!handlerHashMap.containsKey(receiverAddress + senderAddress)) {
                System.out.println("Warning this connection from {} has not been seen yet");
                return;
            }
            handlerHashMap.get(receiverAddress + senderAddress).connectionClosed();
            handlerHashMap.remove(receiverAddress + senderAddress);
        }else if(pcMessage.getClass().equals(PcIrcDataMessage.class)) {
            PcIrcDataMessage dataMessage = (PcIrcDataMessage) pcMessage;
            if(!handlerHashMap.containsKey(receiverAddress + senderAddress) ) {
                System.out.println("Warning this connection from {} has not been seen yet");
                PcIrcDisconnectMessage disconnectMessage = new PcIrcDisconnectMessage(senderAddress,receiverAddress);
                botX.sendIRC().message(CHANNEL,disconnectMessage.getMessage());
            }
            ServerHandler handler = handlerHashMap.get(receiverAddress + senderAddress);


            dataToBeReceived = dataMessage.getLength() -  dataMessage.getData().length();

            if (dataToBeReceived == 0){
                try{
                    Protos.TwoWayChannelMessage protoMess = Protos.TwoWayChannelMessage.parseFrom(Base64.decode(dataMessage.getData().toCharArray()));
                    handler.messageReceived(protoMess);
                }catch(Exception e){
                    System.out.println();
                }

            }else{
                builder = new StringBuilder();
                builder.append(dataMessage.getData());
            }
        }else if(pcMessage.getClass().equals(PcIrcResumeMessage.class)) {
            PcIrcResumeMessage resumeMessage = (PcIrcResumeMessage) pcMessage;
            if(!handlerHashMap.containsKey(receiverAddress + senderAddress) ) {
                System.out.println("Warning this connection from {} has not been seen yet");
                PcIrcDisconnectMessage disconnectMessage = new PcIrcDisconnectMessage(senderAddress, receiverAddress);
                botX.sendIRC().message(CHANNEL,disconnectMessage.getMessage());

            }
            if(dataToBeReceived > 0){
                ServerHandler handler = handlerHashMap.get(receiverAddress + senderAddress);
                dataToBeReceived = dataToBeReceived - resumeMessage.getData().length();
                builder.append(resumeMessage.getData());
                if(dataToBeReceived == 0){
                    try{
                        Protos.TwoWayChannelMessage protoMess = Protos.TwoWayChannelMessage.parseFrom(Base64.decode(builder.toString().toCharArray()));
                        handler.messageReceived(protoMess);
                    }catch(Exception e){
                        System.out.println();
                    }

                }
            }else{
                PcIrcDisconnectMessage disconnectMessage = new PcIrcDisconnectMessage(senderAddress, receiverAddress);
                botX.sendIRC().message(CHANNEL,disconnectMessage.getMessage());
            }
        }


        super.onMessage(event);
    }




    @Override
    public void onPrivateMessage(PrivateMessageEvent event) throws Exception {

        super.onPrivateMessage(event);
    }

    @Override
    synchronized public void onGenericMessage(GenericMessageEvent event) throws Exception {
        if(event.getUser() == null){
            return;
        }

        super.onGenericMessage(event);
    }


    private class ServerHandler {
        public ServerHandler(final String clientId, String serverAddress, String clientAddress, ECKey serverKey, ECKey clientKey) {
            ServerHandler.this.clientId = clientId;
            ServerHandler.this.clientAddress = clientAddress;
            ServerHandler.this.serverAddress = serverAddress;
            ServerHandler.this.serverKey = serverKey;
            paymentChannelManager = new PaymentChannelServer(broadcaster, wallet, minAcceptedChannelSize, new PaymentChannelServer.ServerConnection() {
                 public void sendToClient(Protos.TwoWayChannelMessage msg) {

                     String message =  String.copyValueOf(Base64.encode(msg.toByteArray()));
                     int length = message.length();
                     if(length < 200){
                         PcIrcDataMessage dataMessage = new PcIrcDataMessage(ServerHandler.this.clientAddress,ServerHandler.this.serverAddress,length,message);
                         botX.sendIRC().message(CHANNEL,dataMessage.getMessage());
                     }else{
                         PcIrcDataMessage dataMessage = new PcIrcDataMessage(ServerHandler.this.clientAddress,ServerHandler.this.serverAddress,length, message.substring(0,200));
                         botX.sendIRC().message(CHANNEL,dataMessage.getMessage());
                         for(int i = 200; i < length; i=i+200){
                             if(i+200 >= length){
                                 PcIrcResumeMessage resumeMessage = new PcIrcResumeMessage(ServerHandler.this.clientAddress,ServerHandler.this.serverAddress, message.substring(i));
                                 botX.sendIRC().message(CHANNEL,resumeMessage.getMessage());
                             }else{
                                 PcIrcResumeMessage resumeMessage = new PcIrcResumeMessage(ServerHandler.this.clientAddress,ServerHandler.this.serverAddress, message.substring(i,i+200));
                                 botX.sendIRC().message(CHANNEL,resumeMessage.getMessage());
                             }
                         }

                     }

                }

                 public void destroyConnection(PaymentChannelCloseException.CloseReason reason) {
                    if (closeReason != null)
                        closeReason = reason;
                    botX.send().message(clientId,"disconnect");
                }

                 public void channelOpen(Sha256Hash contractHash) {
                    eventHandler.channelOpen(contractHash);
                }

                 public ListenableFuture<ByteString> paymentIncrease(Coin by, Coin to, @Nullable ByteString info) {
                    return eventHandler.paymentIncrease(by, to, info);
                }
            });

        }
        public synchronized void messageReceived(Protos.TwoWayChannelMessage msg) {
            paymentChannelManager.receiveMessage(msg);
        }

        public synchronized void connectionClosed() {
            paymentChannelManager.connectionClosed();
            if (closeReason != null)
                eventHandler.channelClosed(closeReason);
            else
                eventHandler.channelClosed(PaymentChannelCloseException.CloseReason.CONNECTION_CLOSED);
//            eventHandler.setConnectionChannel(null);
        }
        public synchronized void connectionOpen(String challenge) {


            ServerConnectionEventHandler eventHandler = eventHandlerFactory.onNewConnection(clientId);
            if (eventHandler == null){
                PcIrcDisconnectMessage disconnectMessage = new PcIrcDisconnectMessage(clientAddress, serverAddress);
                botX.sendIRC().message(CHANNEL, disconnectMessage.getMessage());
            }
            else {

                String signedMessage = serverKey.signMessage(challenge);

                PcIrcConnectedMessage connectedMessage = new PcIrcConnectedMessage(clientAddress, serverAddress,signedMessage);
                botX.send().message(CHANNEL, connectedMessage.getMessage());

                PaymentChannelServerIrcListener.ServerHandler.this.eventHandler = eventHandler;
                paymentChannelManager.connectionOpen();
            }
        }

        private String clientId;

        private PaymentChannelCloseException.CloseReason closeReason;

        // The user-provided event handler
        private ServerConnectionEventHandler eventHandler;

        // The payment channel server which does the actual payment channel handling
        private final PaymentChannelServer paymentChannelManager;

        private String serverAddress;
        private String clientAddress;
        private ECKey serverKey;
    }
}
