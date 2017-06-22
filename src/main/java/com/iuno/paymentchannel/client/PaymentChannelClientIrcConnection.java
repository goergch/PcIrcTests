package com.iuno.paymentchannel.client;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.google.protobuf.ByteString;
import com.iuno.paymentchannel.PcIrcMessage;
import com.iuno.paymentchannel.PcIrcMessageRegistry;
import com.iuno.paymentchannel.messages.*;
import com.iuno.paymentchannel.server.PaymentChannelServerIrcListener;
import com.lambdaworks.codec.Base64;
import com.subgraph.orchid.encoders.Hex;
import org.bitcoin.paymentchannel.Protos;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.protocols.channels.*;
import org.bitcoinj.wallet.Wallet;
import org.pircbotx.Configuration;
import org.pircbotx.MultiBotManager;
import org.pircbotx.PircBotX;
import org.pircbotx.exception.IrcException;
import org.pircbotx.hooks.ListenerAdapter;
import org.pircbotx.hooks.events.ConnectEvent;
import org.pircbotx.hooks.events.JoinEvent;
import org.pircbotx.hooks.events.MessageEvent;
import org.pircbotx.hooks.events.PrivateMessageEvent;
import org.pircbotx.hooks.types.GenericMessageEvent;
import org.spongycastle.crypto.params.KeyParameter;

import javax.annotation.Nullable;


import java.io.IOException;
import java.security.SecureRandom;
import java.security.SignatureException;

/**
 * Created by goergch on 16.06.17.
 */
public class PaymentChannelClientIrcConnection extends ListenerAdapter{
    private final SettableFuture<PaymentChannelClientIrcConnection> channelOpenFuture = SettableFuture.create();
    private final PaymentChannelClient channelClient;
    private final PircBotX botX;
    private final PcIrcMessageRegistry messageRegistry;
    private final ECKey ownKey;
    private final String serverAddress;
    private final String clientAddress;
    private MultiBotManager manager;
    private boolean connected = false;
    private int dataToBeReceived;
    private StringBuilder builder;
    private ECKey serverKey;
    private String challenge;

    public static final String CHANNEL = "#tdmiunopaymentchannel";

    public PaymentChannelClientIrcConnection(String ownIrcAddress, ECKey ownKey, ECKey serverKey, Wallet wallet, ECKey myKey,
                                             Coin maxValue) throws IOException, IrcException {

        this.serverKey = serverKey;
        this.ownKey = ownKey;
        this.messageRegistry = PcIrcMessageRegistry.createStandardRegistry();
        this.serverAddress = serverKey.getPublicKeyAsHex().substring(0,10);
        this.clientAddress = ownKey.getPublicKeyAsHex().substring(0,10);

        channelClient = new PaymentChannelClient(wallet, myKey,maxValue, Sha256Hash.of((serverAddress+clientAddress).getBytes()), new PaymentChannelClient.ClientConnection()
        {
            public void sendToServer(Protos.TwoWayChannelMessage msg) {
                String message =  String.copyValueOf(Base64.encode(msg.toByteArray()));
                int length = message.length();
                if(length < 200){
                    PcIrcDataMessage dataMessage = new PcIrcDataMessage(serverAddress,clientAddress,length,message);
                    botX.sendIRC().message(CHANNEL,dataMessage.getMessage());
                }else{
                    PcIrcDataMessage dataMessage = new PcIrcDataMessage(serverAddress,clientAddress,length, message.substring(0,200));
                    botX.sendIRC().message(CHANNEL,dataMessage.getMessage());
                    for(int i = 200; i < length; i=i+200){
                        if(i+200 >= length){
                            PcIrcResumeMessage resumeMessage = new PcIrcResumeMessage(serverAddress,clientAddress, message.substring(i));
                            botX.sendIRC().message(CHANNEL,resumeMessage.getMessage());
                        }else{
                            PcIrcResumeMessage resumeMessage = new PcIrcResumeMessage(serverAddress,clientAddress, message.substring(i,i+200));
                            botX.sendIRC().message(CHANNEL,resumeMessage.getMessage());
                        }
                    }

                }
            }

            public void destroyConnection(PaymentChannelCloseException.CloseReason reason) {
                PcIrcDisconnectMessage disconnectMessage = new PcIrcDisconnectMessage(serverAddress,clientAddress);
                botX.sendIRC().message(CHANNEL,disconnectMessage.getMessage());

            }

            public boolean acceptExpireTime(long expireTime) {
                return true;
            }

            public void channelOpen(boolean wasInitiated) {
                channelOpenFuture.set(PaymentChannelClientIrcConnection.this);
            }
        });

        Configuration.Builder templateConfig = new Configuration.Builder()
                .setName(ownIrcAddress)
                .setAutoNickChange(true)
                .addListener(this)
                .addAutoJoinChannel(CHANNEL);

        manager = new MultiBotManager();
        manager.addBot(templateConfig.buildForServer("irc.freenode.net"));
        manager.start();
        botX = manager.getBotById(0);

//

    }


    @Override
    public void onMessage(MessageEvent event) throws Exception {
        String message = event.getMessage();
        String receiverAddress = message.substring(0,10);
        String messageType = message.substring(20,24);
        if(!clientAddress.equals(receiverAddress)){
            return;
        }
        PcIrcMessage pcMessage = null;
        if(!messageRegistry.contains(messageType)){
            return;
        }
        pcMessage = messageRegistry.get(messageType).create(message);

        String senderAddress = pcMessage.getSenderAddress();



        if(pcMessage.getClass().equals(PcIrcConnectedMessage.class)){
            PcIrcConnectedMessage connectedMessage = (PcIrcConnectedMessage)pcMessage;
            try{
                serverKey.verifyMessage(challenge,connectedMessage.getSignature());
                channelClient.connectionOpen();
            }catch (SignatureException e){
                System.out.println(e);
                PcIrcDisconnectMessage disconnectMessage = new PcIrcDisconnectMessage(serverAddress,clientAddress);
                botX.sendIRC().message(CHANNEL,disconnectMessage.getMessage());
            }


        }else if(pcMessage.getClass().equals(PcIrcDisconnectMessage.class)) {
            channelClient.connectionClosed();
            disconnectWithoutSettlement();

        }else if(pcMessage.getClass().equals(PcIrcDataMessage.class)) {
            PcIrcDataMessage dataMessage = (PcIrcDataMessage) pcMessage;


            dataToBeReceived = dataMessage.getLength() -  dataMessage.getData().length();

            if (dataToBeReceived == 0){
                try{
                    Protos.TwoWayChannelMessage protoMess = Protos.TwoWayChannelMessage.parseFrom(Base64.decode(dataMessage.getData().toCharArray()));
                    channelClient.receiveMessage(protoMess);
                }catch(Exception e){
                    System.out.println();
                }

            }else{
                builder = new StringBuilder();
                builder.append(dataMessage.getData());
            }
        }else if(pcMessage.getClass().equals(PcIrcResumeMessage.class)) {
            PcIrcResumeMessage resumeMessage = (PcIrcResumeMessage) pcMessage;

            if(dataToBeReceived > 0){
                dataToBeReceived = dataToBeReceived - resumeMessage.getData().length();
                builder.append(resumeMessage.getData());
                if(dataToBeReceived == 0){
                    try{
                        Protos.TwoWayChannelMessage protoMess = Protos.TwoWayChannelMessage.parseFrom(Base64.decode(builder.toString().toCharArray()));
                        channelClient.receiveMessage(protoMess);
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


//    @Override
//    public void onConnect(ConnectEvent event) throws Exception {
//        if(botX.isConnected() && !connected){
//
//        }
//        super.onConnect(event);
//    }

    @Override
    public void onJoin(JoinEvent event) throws Exception {
        if(event.getChannel().getName().equals(CHANNEL)){
            byte[] random = new SecureRandom().generateSeed(30);
            challenge = String.copyValueOf(Base64.encode(random));

            PcIrcConnectMessage connectMessage =
                    new PcIrcConnectMessage(serverAddress,clientAddress,
                            serverKey.getPublicKeyAsHex(), ownKey.getPublicKeyAsHex(),challenge);
            botX.sendIRC().message(CHANNEL,connectMessage.getMessage());
            connected = true;
        }

        super.onJoin(event);
    }

    public ListenableFuture<PaymentChannelClientIrcConnection> getChannelOpenFuture() {
        return channelOpenFuture;
    }

    /**
     * Increments the total value which we pay the server.
     *
     * @param size How many satoshis to increment the payment by (note: not the new total).
     * @param info Information about this payment increment, used to extend this protocol.
     * @param userKey Key derived from a user password, needed for any signing when the wallet is encrypted.
     *                The wallet KeyCrypter is assumed.
     * @throws ValueOutOfRangeException If the size is negative or would pay more than this channel's total value
     *                                  ({@link PaymentChannelClientConnection#state()}.getTotalValue())
     * @throws IllegalStateException If the channel has been closed or is not yet open
     *                               (see {@link PaymentChannelClientConnection#getChannelOpenFuture()} for the second)
     */
    public ListenableFuture<PaymentIncrementAck> incrementPayment(Coin size,
                                                                  @Nullable ByteString info,
                                                                  @Nullable KeyParameter userKey)
            throws ValueOutOfRangeException, IllegalStateException {
        return channelClient.incrementPayment(size, info, userKey);
    }

    /**
     * <p>Gets the {@link PaymentChannelV1ClientState} object which stores the current state of the connection with the
     * server.</p>
     *
     * <p>Note that if you call any methods which update state directly the server will not be notified and channel
     * initialization logic in the connection may fail unexpectedly.</p>
     */
    public PaymentChannelClientState state() {
        return channelClient.state();
    }

    /**
     * Closes the connection, notifying the server it should settle the channel by broadcasting the most recent payment
     * transaction.
     */
    public void settle() {
        // Shutdown is a little complicated.
        //
        // This call will cause the CLOSE message to be written to the wire, and then the destroyConnection() method that
        // we defined above will be called, which in turn will call wireParser.closeConnection(), which in turn will invoke
        // NioClient.closeConnection(), which will then close the socket triggering interruption of the network
        // thread it had created. That causes the background thread to die, which on its way out calls
        // ProtobufConnection.connectionClosed which invokes the connectionClosed method we defined above which in turn
        // then configures the open-future correctly and closes the state object. Phew!
        try {
            channelClient.settle();
            PcIrcDisconnectMessage disconnectMessage = new PcIrcDisconnectMessage(serverAddress,clientAddress);
            botX.sendIRC().message(CHANNEL, disconnectMessage.getMessage());
            manager.stopAndWait();
        } catch (IllegalStateException e) {
            // Already closed...oh well
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    /**
     * Disconnects the network connection but doesn't request the server to settle the channel first (literally just
     * unplugs the network socket and marks the stored channel state as inactive).
     */
    public void disconnectWithoutSettlement() {
        if(botX != null && botX.isConnected()){
            PcIrcDisconnectMessage disconnectMessage = new PcIrcDisconnectMessage(serverAddress,clientAddress);
            botX.sendIRC().message(CHANNEL, disconnectMessage.getMessage());
        }
        try {
            if(manager != null){
                manager.stopAndWait();
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}
