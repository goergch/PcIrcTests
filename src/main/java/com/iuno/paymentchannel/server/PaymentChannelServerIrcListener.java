package com.iuno.paymentchannel.server;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.protobuf.ByteString;
import com.lambdaworks.codec.Base64;
import com.subgraph.orchid.encoders.Hex;
import org.bitcoin.paymentchannel.Protos;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.TransactionBroadcaster;
import org.bitcoinj.net.ProtobufConnection;
import org.bitcoinj.protocols.channels.PaymentChannelCloseException;
import org.bitcoinj.protocols.channels.PaymentChannelServer;
import org.bitcoinj.protocols.channels.PaymentChannelServerListener;
import org.bitcoinj.protocols.channels.ServerConnectionEventHandler;
import org.bitcoinj.wallet.Wallet;
import org.pircbotx.Configuration;
import org.pircbotx.MultiBotManager;
import org.pircbotx.PircBotX;
import org.pircbotx.exception.IrcException;
import org.pircbotx.hooks.ListenerAdapter;
import org.pircbotx.hooks.events.PrivateMessageEvent;
import org.pircbotx.hooks.types.GenericMessageEvent;

import javax.annotation.Nullable;
import java.io.IOException;
import java.math.BigInteger;
import java.net.SocketAddress;
import java.util.HashMap;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Created by goergch on 16.06.17.
 */
public class PaymentChannelServerIrcListener extends ListenerAdapter {
    private final Wallet wallet;
    private final TransactionBroadcaster broadcaster;

    // The event handler factory which creates new ServerConnectionEventHandler per connection
    private final HandlerFactory eventHandlerFactory;
    private final Coin minAcceptedChannelSize;
    private PircBotX botX;

    private HashMap<String, ServerHandler> handlerHashMap = new HashMap<String, ServerHandler>();
    private int dataToBeReceived = 0;
    private StringBuilder builder;

    private ECKey paymentChannelServerKey;

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
        this.paymentChannelServerKey = ECKey.fromPrivate(Hex.decode("b1400f7a63ec5d38c9b34b843d6615a45dbda674498e37022383ef28e19ec692"));
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
                .addListener(this);

        MultiBotManager manager = new MultiBotManager();
        manager.addBot(templateConfig.buildForServer("irc.freenode.net"));
        manager.start();
        botX = manager.getBotById(0);
    }

    @Override
    public void onPrivateMessage(PrivateMessageEvent event) throws Exception {
        String clientId = event.getUser().getNick();

        if(event.getMessage().startsWith("connect") && event.getMessage().length() == 47){
            if(handlerHashMap.containsKey(clientId) ){
                //The connection might have been broken
                handlerHashMap.remove(clientId);
            }
            handlerHashMap.put(clientId,new ServerHandler(clientId));

            String challengeString = event.getMessage().substring(7);

            handlerHashMap.get(clientId).connectionOpen(challengeString);
        } else if(event.getMessage().startsWith("disconnect")){
            if(handlerHashMap.containsKey(clientId) ){

                handlerHashMap.get(clientId).connectionClosed();
                handlerHashMap.remove(clientId);
                botX.send().message(clientId,"disconnected");
            }
        }
        if(event.getMessage().startsWith("DATA")){

            if(!handlerHashMap.containsKey(clientId) ) {
                System.out.println("Warning this connection from {} has not been seen yet");
                botX.send().message(clientId,"disconnect");
            }
            ServerHandler handler = handlerHashMap.get(clientId);
            int length = Integer.parseInt(event.getMessage().substring(4,8));
            dataToBeReceived = length - event.getMessage().length();

            if (dataToBeReceived == 0){
                try{
                    Protos.TwoWayChannelMessage message = Protos.TwoWayChannelMessage.parseFrom(Base64.decode(event.getMessage().substring(8).toCharArray()));
                    handler.messageReceived(message);
                }catch(Exception e){
                    System.out.println();
                }

            }else{
                builder = new StringBuilder();
                builder.append(event.getMessage().substring(8));
            }

        }else if(dataToBeReceived > 0){
            if(!handlerHashMap.containsKey(clientId) ) {
                System.out.println("Warning this connection from {} has not been seen yet");
                botX.send().message(clientId,"disconnect");
            }
            ServerHandler handler = handlerHashMap.get(clientId);
            dataToBeReceived = dataToBeReceived - event.getMessage().length();
            builder.append(event.getMessage());
            if(dataToBeReceived == 0){
                try{
                    Protos.TwoWayChannelMessage message = Protos.TwoWayChannelMessage.parseFrom(Base64.decode(builder.toString().toCharArray()));
                    handler.messageReceived(message);
                }catch(Exception e){
                    System.out.println();
                }

            }

        }
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
        public ServerHandler(final String clientId) {
            ServerHandler.this.clientId = clientId;
            paymentChannelManager = new PaymentChannelServer(broadcaster, wallet, minAcceptedChannelSize, new PaymentChannelServer.ServerConnection() {
                 public void sendToClient(Protos.TwoWayChannelMessage msg) {
                     StringBuilder builder = new StringBuilder();
                     builder.append("DATA");
                     String message =  String.copyValueOf(Base64.encode(msg.toByteArray()));
                     int length = message.length() + 8;
                     String lengthString = String.format("%04d",length);
                     builder.append(lengthString);
                     builder.append(message);
                     for(int i = 0; i < length; i=i+200){
                         if(i+200 >= length){
                             botX.send().message(clientId,builder.toString().substring(i));
                         }else{
                             botX.send().message(clientId,builder.toString().substring(i,i+200));
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
            if (eventHandler == null)
                botX.send().message(clientId,"disconnect");
            else {

                String signedMessage = paymentChannelServerKey.signMessage(challenge);

                String connectedMessage = "connected" + signedMessage;
                botX.send().message(clientId,connectedMessage);
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
    }
}
