package com.iuno.paymentchannel;


import com.lambdaworks.codec.Base64;
import org.bitcoinj.core.ECKey;

import java.security.SecureRandom;

/**
 * Created by goergch on 18.06.17.
 */
public class KeyGenerator {

    public static void main(String[] args){
        ECKey key = new ECKey(new SecureRandom());
        System.out.println(String.format("The PrivKey: %s", key.getPrivateKeyAsHex()));
        System.out.println(String.format("The PubKey: %s", key.getPublicKeyAsHex()));
    }
}
