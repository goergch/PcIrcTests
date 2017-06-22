package com.iuno.paymentchannel.client;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.Uninterruptibles;
import com.google.protobuf.ByteString;
import com.subgraph.orchid.encoders.Hex;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.kits.WalletAppKit;
import org.bitcoinj.params.RegTestParams;
import org.bitcoinj.params.TestNet3Params;
import org.bitcoinj.protocols.channels.*;
import org.bitcoinj.utils.BriefLogFormatter;
import org.bitcoinj.utils.Threading;
import org.bitcoinj.wallet.Wallet;
import org.bitcoinj.wallet.WalletExtension;
import org.pircbotx.exception.IrcException;
import org.slf4j.LoggerFactory;
import org.spongycastle.crypto.params.KeyParameter;

import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;

import static org.bitcoinj.core.Coin.CENT;

/**
 * Created by goergch on 16.06.17.
 */
public class PaymentChannelClientExample {
    private static final org.slf4j.Logger log = LoggerFactory.getLogger(PaymentChannelClientExample.class);
    private WalletAppKit appKit;
    private final Coin channelSize;
    private final ECKey myKey;
    private final NetworkParameters params;

    public static void main(String[] args) throws Exception {
        BriefLogFormatter.init();


        NetworkParameters params = TestNet3Params.get();
        new PaymentChannelClientExample().run("tdmiuno", params);
    }

    public PaymentChannelClientExample() {
        channelSize = CENT;
        myKey = new ECKey();
        params = RegTestParams.get();
    }

    public void run(final String host,  final NetworkParameters params) throws Exception {
        // Bring up all the objects we need, create/load a wallet, sync the chain, etc. We override WalletAppKit so we
        // can customize it by adding the extension objects - we have to do this before the wallet file is loaded so
        // the plugin that knows how to parse all the additional data is present during the load.
        appKit = new WalletAppKit(params, new File("."), "payment_channel_example_client") {
            @Override
            protected List<WalletExtension> provideWalletExtensions() {
                // The StoredPaymentChannelClientStates object is responsible for, amongst other things, broadcasting
                // the refund transaction if its lock time has expired. It also persists channels so we can resume them
                // after a restart.
                // We should not send a PeerGroup in the StoredPaymentChannelClientStates constructor
                // since WalletAppKit will find it for us.
                return ImmutableList.<WalletExtension>of(new StoredPaymentChannelClientStates(null));
            }
        };
        // Broadcasting can take a bit of time so we up the timeout for "real" networks
        final int timeoutSeconds = params.getId().equals(NetworkParameters.ID_REGTEST) ? 15 : 150;
        if (params == RegTestParams.get()) {
            appKit.connectToLocalHost();
        }
        appKit.startAsync();
        appKit.awaitRunning();
        // We now have active network connections and a fully synced wallet.
        // Add a new key which will be used for the multisig contract.
        appKit.wallet().importKey(myKey);
        appKit.wallet().allowSpendingUnconfirmedTransactions();

        System.out.println(appKit.wallet());

        // Create the object which manages the payment channels protocol, client side. Tell it where the server to
        // connect to is, along with some reasonable network timeouts, the wallet and our temporary key. We also have
        // to pick an amount of value to lock up for the duration of the channel.
        //
        // Note that this may or may not actually construct a new channel. If an existing unclosed channel is found in
        // the wallet, then it'll re-use that one instead.

        waitForSufficientBalance(channelSize);
        final String channelID = host;

        ECKey ownKey = ECKey.fromPrivate(Hex.decode("b88b0c780f980ab9d5c0e651703bd51ed3e78528f3d88146ce5df0824b01edf6"));
        ECKey serverKey = ECKey.fromPublicOnly(Hex.decode("0205257e70e26402d03a7f96a625946c96e59394218d619f8f695ceb4f3f677fb7"));

        openAndSend(ownKey,serverKey,1000,"4711");

        log.info("Stopping ...");
        appKit.stopAsync();
        appKit.awaitTerminated();
    }


    private void openAndSend(ECKey ownKey, ECKey serverKey, final long amount, final String invoiceId) throws IOException, ValueOutOfRangeException, InterruptedException {
        // Use protocol version 1 for simplicity
//        PaymentChannelClientConnection client = new PaymentChannelClientConnection(
//                server, timeoutSecs, appKit.wallet(), myKey, channelSize, channelID, null,);
        PaymentChannelClientIrcConnection client = null;
        try {
            client = new PaymentChannelClientIrcConnection("machineTest", ownKey, serverKey,appKit.wallet(), myKey,
                    channelSize);
        } catch (IrcException e) {
            e.printStackTrace();
        }

        // Opening the channel requires talking to the server, so it's asynchronous.
        final CountDownLatch latch = new CountDownLatch(1);
        Futures.addCallback(client.getChannelOpenFuture(), new FutureCallback<PaymentChannelClientIrcConnection>() {

            public void onSuccess(PaymentChannelClientIrcConnection client) {
                // By the time we get here, if the channel is new then we already made a micropayment! The reason is,
                // we are not allowed to have payment channels that pay nothing at all.


                try {
                    // Wait because the act of making a micropayment is async, and we're not allowed to overlap.
                    // This callback is running on the user thread (see the last lines in openAndSend) so it's safe
                    // for us to block here: if we didn't select the right thread, we'd end up blocking the payment
                    // channels thread and would deadlock.
                    ByteString infoString = ByteString.copyFromUtf8(invoiceId);
                    log.info("ByteString: {}",infoString.toStringUtf8());
                    Uninterruptibles.getUninterruptibly(client.incrementPayment(Coin.valueOf(amount), infoString, null));
                } catch (ValueOutOfRangeException e) {
                    log.error("Failed to increment payment by a CENT, remaining value is {}", client.state().getValueRefunded());
                    throw new RuntimeException(e);
                } catch (ExecutionException e) {
                    log.error("Failed to increment payment", e);
                    throw new RuntimeException(e);
                }
                log.info("Successfully sent payment of one CENT, total remaining on channel is now {}", client.state().getValueRefunded());


                if (client.state().getValueRefunded().compareTo(CENT.divide(10)) < 0) {
                    // Now tell the server we're done so they should broadcast the final transaction and refund us what's
                    // left. If we never do this then eventually the server will time out and do it anyway and if the
                    // server goes away for longer, then eventually WE will time out and the refund tx will get broadcast
                    // by ourselves.
                    log.info("Settling channel for good");
                    client.settle();
                } else {
                    // Just unplug from the server but leave the channel open so it can resume later.
                    client.disconnectWithoutSettlement();
                }
                latch.countDown();
            }

            public void onFailure(Throwable throwable) {
                log.error("Failed to open connection", throwable);
//                latch.countDown();
            }

        }, Threading.USER_THREAD);
        latch.await();
    }

    private void waitForSufficientBalance(Coin amount) {
        // Not enough money in the wallet.
        Coin amountPlusFee = amount.add(Transaction.REFERENCE_DEFAULT_MIN_TX_FEE);
        // ESTIMATED because we don't really need to wait for confirmation.
        ListenableFuture<Coin> balanceFuture = appKit.wallet().getBalanceFuture(amountPlusFee, Wallet.BalanceType.ESTIMATED);
        if (!balanceFuture.isDone()) {
            System.out.println("Please send " + amountPlusFee.toFriendlyString() +
                    " to " + myKey.toAddress(params));
            Futures.getUnchecked(balanceFuture);
        }
    }
}


