package com.iuno.paymentchannel.client;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.google.protobuf.ByteString;
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
    private MultiBotManager manager;
    private String ircAddress;
    private boolean connected = false;
    private int dataToBeReceived;
    private StringBuilder builder;
    private ECKey serverKey;
    private String challenge;

    public PaymentChannelClientIrcConnection(final String ircAddress, String pubKey, Wallet wallet, ECKey myKey,
                                             Coin maxValue, String serverId, @Nullable KeyParameter userKeySetup) throws IOException, IrcException {
        this.ircAddress = ircAddress;
        this.serverKey = ECKey.fromPublicOnly(Hex.decode(pubKey));
        channelClient = new PaymentChannelClient(wallet, myKey,maxValue, Sha256Hash.of(serverId.getBytes()), new PaymentChannelClient.ClientConnection()
        {
            public void sendToServer(Protos.TwoWayChannelMessage msg) {
                StringBuilder builder = new StringBuilder();
                builder.append("DATA");
                String message =  String.copyValueOf(Base64.encode(msg.toByteArray()));
                int length = message.length() + 8;
                String lengthString = String.format("%04d",length);
                builder.append(lengthString);
                builder.append(message);
                for(int i = 0; i < length; i=i+200){
                    if(i+200 >= length){
                        botX.send().message(ircAddress,builder.toString().substring(i));
                    }else{
                        botX.send().message(ircAddress,builder.toString().substring(i,i+200));
                    }
                }
            }

            public void destroyConnection(PaymentChannelCloseException.CloseReason reason) {
                botX.send().message(ircAddress,"disconnect");

            }

            public boolean acceptExpireTime(long expireTime) {
                return true;
            }

            public void channelOpen(boolean wasInitiated) {
                channelOpenFuture.set(PaymentChannelClientIrcConnection.this);
            }
        });

        Configuration.Builder templateConfig = new Configuration.Builder()
                .setName("machine0iuno")
                .setAutoNickChange(true)
                .addListener(this);

        manager = new MultiBotManager();
        manager.addBot(templateConfig.buildForServer("irc.freenode.net"));
        manager.start();
        botX = manager.getBotById(0);

//

    }

    @Override
    public void onPrivateMessage(PrivateMessageEvent event) throws Exception {
        if(!event.getUser().getNick().equals(ircAddress)){
            //thats not the one we want to talk with...
            return;
        }

        if(event.getMessage().startsWith("disconnect")){
            channelClient.connectionClosed();
            disconnectWithoutSettlement();
        }else if(event.getMessage().startsWith("connected") && event.getMessage().length() == 97){
            String signedMessage = event.getMessage().substring(9);
            try{
                serverKey.verifyMessage(challenge,signedMessage);
                channelClient.connectionOpen();
            }catch (SignatureException e){
                System.out.println(e);
                botX.send().message(ircAddress, "disconnect");
            }



        }else if(event.getMessage().startsWith("DATA")){

            int length = Integer.parseInt(event.getMessage().substring(4,8));
            dataToBeReceived = length - event.getMessage().length();

            if (dataToBeReceived == 0){
                try{
                    Protos.TwoWayChannelMessage message = Protos.TwoWayChannelMessage.parseFrom(Base64.decode(event.getMessage().substring(8).toCharArray()));
                    channelClient.receiveMessage(message);
                }catch(Exception e){
                    System.out.println(e);
                    botX.send().message(ircAddress,"disconnect");
                }
            }else{
                builder = new StringBuilder();
                builder.append(event.getMessage().substring(8));
            }

        }else if(dataToBeReceived > 0){
            dataToBeReceived = dataToBeReceived - event.getMessage().length();
            builder.append(event.getMessage());
            if(dataToBeReceived == 0){
                try{
                    Protos.TwoWayChannelMessage message = Protos.TwoWayChannelMessage.parseFrom(Base64.decode(builder.toString().toCharArray()));
                    channelClient.receiveMessage(message);
                }catch(Exception e){
                    System.out.println(e);
                    botX.send().message(ircAddress,"disconnect");
                }
            }
        }
        super.onPrivateMessage(event);
    }

    @Override
    synchronized public void onGenericMessage(GenericMessageEvent event) throws Exception {
        if(event.getUser() == null){

            if(botX.isConnected() && !connected){
                byte[] random = new SecureRandom().generateSeed(30);
                challenge = String.copyValueOf(Base64.encode(random));
                String connectMessage = "connect" + challenge;

                botX.send().message(ircAddress,connectMessage);
                connected = true;
            }
            return;
        }

        super.onGenericMessage(event);
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
            botX.send().message(ircAddress,"disconnect");
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
            botX.send().message(ircAddress,"disconnect");
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
