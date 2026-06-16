package com.zbyd.patcher;

import com.android.apksig.ApkSigner;

import java.io.File;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Re-sign the patched APK with the bundled zbyd key via apksig (v1+v2+v3). PKCS12 keystore (Android-safe). */
public final class ApkSign {

    public static void sign(InputStream p12, char[] pass, String alias, File in, File out) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(p12, pass);
        PrivateKey key = (PrivateKey) ks.getKey(alias, pass);
        java.security.cert.Certificate[] chain = ks.getCertificateChain(alias);
        List<X509Certificate> certs = new ArrayList<>();
        for (java.security.cert.Certificate c : chain) certs.add((X509Certificate) c);

        ApkSigner.SignerConfig signer = new ApkSigner.SignerConfig.Builder("zbyd", key, certs).build();
        ApkSigner s = new ApkSigner.Builder(Collections.singletonList(signer))
            .setInputApk(in).setOutputApk(out)
            .setV1SigningEnabled(true).setV2SigningEnabled(true).setV3SigningEnabled(true)
            .build();
        s.sign();
    }
}
