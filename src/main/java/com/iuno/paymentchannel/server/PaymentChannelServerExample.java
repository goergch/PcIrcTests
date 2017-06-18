package com.iuno.paymentchannel.server;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.protobuf.ByteString;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.VerificationException;
import org.bitcoinj.kits.WalletAppKit;
import org.bitcoinj.params.RegTestParams;
import org.bitcoinj.params.TestNet3Params;
import org.bitcoinj.protocols.channels.*;
import org.bitcoinj.utils.BriefLogFormatter;
import org.bitcoinj.wallet.WalletExtension;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.File;
import java.net.SocketAddress;
import java.util.List;

/**
 * Created by goergch on 16.06.17.
 */
public class PaymentChannelServerExample implements PaymentChannelServerIrcListener.HandlerFactory {
    private static final org.slf4j.Logger log = LoggerFactory.getLogger(PaymentChannelServerExample.class);

    private WalletAppKit appKit;

    public static void main(String[] args) throws Exception {
        BriefLogFormatter.init();

        NetworkParameters params = TestNet3Params.get();
        new PaymentChannelServerExample().run(params);
    }

    public void run(NetworkParameters params) throws Exception {

        // Bring up all the objects we need, create/load a wallet, sync the chain, etc. We override WalletAppKit so we
        // can customize it by adding the extension objects - we have to do this before the wallet file is loaded so
        // the plugin that knows how to parse all the additional data is present during the load.
        appKit = new WalletAppKit(params, new File("."), "payment_channel_example_server") {
            @Override
            protected List<WalletExtension> provideWalletExtensions() {
                // The StoredPaymentChannelClientStates object is responsible for, amongst other things, broadcasting
                // the refund transaction if its lock time has expired. It also persists channels so we can resume them
                // after a restart.
                return ImmutableList.<WalletExtension>of(new StoredPaymentChannelServerStates(null));
            }
        };
        // Broadcasting can take a bit of time so we up the timeout for "real" networks
        final int timeoutSeconds = params.getId().equals(NetworkParameters.ID_REGTEST) ? 15 : 150;
        if (params == RegTestParams.get()) {
            appKit.connectToLocalHost();
        }
        appKit.startAsync();
        appKit.awaitRunning();

        System.out.println(appKit.wallet());

        // We provide a peer group, a wallet, a timeout in seconds, the amount we require to start a channel and
        // an implementation of HandlerFactory, which we just implement ourselves.
        //new PaymentChannelServerListener(appKit.peerGroup(), appKit.wallet(), timeoutSeconds, Coin.valueOf(100000), this).bindAndStart(4242);
        new PaymentChannelServerIrcListener(appKit.peerGroup(),appKit.wallet(),
                Coin.valueOf(100000), this).start("tdmiuno");
    }



    @Nullable
    public ServerConnectionEventHandler onNewConnection(final String ircClient) {
        return new ServerConnectionEventHandler() {
            @Override
            public void channelOpen(Sha256Hash channelId) {
                log.info("Channel open for {}: {}.", ircClient, channelId);

                // Try to get the state object from the stored state set in our wallet
                PaymentChannelServerState state = null;
                try {
                    StoredPaymentChannelServerStates storedStates = (StoredPaymentChannelServerStates)
                            appKit.wallet().getExtensions().get(StoredPaymentChannelServerStates.class.getName());
                    state = storedStates.getChannel(channelId).getOrCreateState(appKit.wallet(), appKit.peerGroup());
                } catch (VerificationException e) {
                    // This indicates corrupted data, and since the channel was just opened, cannot happen
                    throw new RuntimeException(e);
                }
                log.info("   with a maximum value of {}, expiring at UNIX timestamp {}.",
                        // The channel's maximum value is the value of the multisig contract which locks in some
                        // amount of money to the channel
                        state.getContract().getOutput(0).getValue(),
                        // The channel expires at some offset from when the client's refund transaction becomes
                        // spendable.
                        state.getExpiryTime() + StoredPaymentChannelServerStates.CHANNEL_EXPIRE_OFFSET);
            }

            @Override
            public ListenableFuture<ByteString> paymentIncrease(Coin by, Coin to, ByteString info) {
                log.info("Client {} paid increased payment by {} for a total of " + to.toString(), ircClient, by);
                return null;
            }

            @Override
            public void channelClosed(PaymentChannelCloseException.CloseReason reason) {
                log.info("Client {} closed channel for reason {}", ircClient, reason);
            }
        };
    }
}


