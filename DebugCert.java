/*
 * Copyright 2006 Sun Microsystems, Inc.  All Rights Reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 *   - Redistributions of source code must retain the above copyright
 *     notice, this list of conditions and the following disclaimer.
 *
 *   - Redistributions in binary form must reproduce the above copyright
 *     notice, this list of conditions and the following disclaimer in the
 *     documentation and/or other materials provided with the distribution.
 *
 *   - Neither the name of Sun Microsystems nor the names of its
 *     contributors may be used to endorse or promote products derived
 *     from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS
 * IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO,
 * THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
/**
 * Originally from:
 * http://blogs.sun.com/andreas/resource/InstallCert.java
 * Use:
 * java InstallCert hostname
 * Example:
 *% java InstallCert ecc.fedora.redhat.com
 */

import java.net.Proxy;
import java.net.Socket;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.net.InetSocketAddress;
import javax.net.ssl.*;
import java.io.*;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

/**
 * Class used to add the server's certificate to the KeyStore
 * with your trusted certificates.
 */
public class DebugCert {

    public static void main(String[] args) throws Exception {

        String host       = null;
        int    port       = -1;
        char[] passphrase = null;

        // proxy
        boolean           useProxy   = false;
        String            proxyHost  = null;
        int               proxyPort  = -1;
        InetSocketAddress proxyAddr  = null;
        Socket            underlying = null;

        int               numRetries = 0;

        int numArg = 0;
        int nbArgs = args.length;
        boolean invalidArgs = false;
        while (numArg < nbArgs) {
            String arg = args[numArg++];
            if (arg.startsWith("--proxy=")) {
                String proxy = arg.substring("--proxy=".length());
                useProxy = true;
                String[] c = proxy.split(":");
                proxyHost = c[0];
                proxyPort = Integer.parseInt(c[1]);  // proxy port is mandatory (we don't default to 8080)
            }
            else if (arg.startsWith("--n=")) {
                String valStr = arg.substring("--n=".length());
                numRetries = Integer.valueOf(valStr);
            }
            else if (host == null) {  // 1st argument is the "host:port"
                String[] c = arg.split(":");
                host = c[0];
                port = (c.length == 1) ? 443 : Integer.parseInt(c[1]);
            }
            else if (passphrase == null) {  //  2nd argument is the keystore passphrase
                passphrase = arg.toCharArray();
            }
            else {
                invalidArgs = true;  // too many args
            }
        }

        if (host == null) {
            invalidArgs = true;
        }

        if (invalidArgs) {
            System.out.println("Usage: java DebugCert [--proxy=proxyHost:proxyPort] host[:port] [passphrase] [--n=numRetries]");
            return;
        }

        // default values
        if (port       == -1  ) { port       = 443; }
        if (passphrase == null) { passphrase = "changeit".toCharArray(); }

        File file = new File("jssecacerts");
        if (file.isFile() == false) {
            char SEP = File.separatorChar;
            File dir = new File(System.getProperty("java.home") + SEP + "lib" + SEP + "security");
            file = new File(dir, "jssecacerts");
            if (file.isFile() == false) {
                file = new File(dir, "cacerts");
            }
        }
        System.out.println("Loading KeyStore " + file + "...");
        InputStream in = new FileInputStream(file);
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(in, passphrase);
        in.close();

        if (useProxy) {
            proxyAddr = new InetSocketAddress(proxyHost, proxyPort);
            underlying = new Socket(new Proxy(Proxy.Type.HTTP, proxyAddr));
        }

        SSLContext context = SSLContext.getInstance("TLS");
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        X509TrustManager defaultTrustManager = (X509TrustManager) tmf.getTrustManagers()[0];
        SavingTrustManager tm = new SavingTrustManager(defaultTrustManager);
        context.init(null, new TrustManager[]{tm}, null);
        SSLSocketFactory factory = context.getSocketFactory();

        System.out.println("Opening connection to " + host + ":" + port + (useProxy ? (" via proxy "+proxyHost+":"+proxyPort) : "") + " ...");

        if (tryHandshake(host, port, useProxy, proxyHost, proxyPort, underlying, factory)) {
            System.out.println();
            System.out.println("No errors, certificate is already trusted");
            printChain(tm.chain);
        } else {
            System.out.println("Failed on first try!");
            printChain(tm.chain);
            return;
        }

        System.out.printf("Retrying %d times\n", numRetries);

        for (int i=0; i<numRetries; i++) {
            System.out.printf("%d/%d ", i+1, numRetries);
            if (!tryHandshake(host, port, useProxy, proxyHost, proxyPort, underlying, factory)) {
        	System.out.println();
        	System.out.printf("FAILURE on try %d!\n", i+1);
                printChain(tm.chain);
        	break;
            }
        }
        System.out.println();
        System.out.println("Complete.");
    }

    private static boolean tryHandshake(String host, int port, boolean useProxy, String proxyHost, int proxyPort,
	    Socket underlying, SSLSocketFactory factory) throws IOException, UnknownHostException, SocketException {
        SSLSocket socket;
        if (useProxy) {
            underlying.connect(new InetSocketAddress(host, port));
            socket = (SSLSocket) factory.createSocket(underlying, host, port, true);
        } else {
            socket = (SSLSocket) factory.createSocket(host, port);
        }
        socket.setSoTimeout(10000);
        try {
            System.out.println("Starting SSL handshake...");
            socket.startHandshake();
            return true;
        } catch (SSLException e) {
            System.out.println();
            e.printStackTrace(System.out);
            return false;
        } finally {
            socket.close();
        }
    }

    private static void printChain(X509Certificate[] chain) throws NoSuchAlgorithmException, CertificateEncodingException {
        if (chain == null) {
            System.out.println("Could not obtain server certificate chain");
            return;
        }

        System.out.println();
        System.out.println("Server sent " + chain.length + " certificate(s):");
        System.out.println();
        MessageDigest sha1 = MessageDigest.getInstance("SHA1");
        MessageDigest md5 = MessageDigest.getInstance("MD5");
        for (int i = 0; i < chain.length; i++) {
            X509Certificate cert = chain[i];
            System.out.println(" " + (i + 1) + " Subject " + cert.getSubjectX500Principal());
            System.out.println("   Issuer  " + cert.getIssuerX500Principal());
            sha1.update(cert.getEncoded());
            System.out.println("   sha1    " + toHexString(sha1.digest()));
            md5.update(cert.getEncoded());
            System.out.println("   md5     " + toHexString(md5.digest()));
            System.out.println();
        }
    }

    private static final char[] HEXDIGITS = "0123456789abcdef".toCharArray();

    private static String toHexString(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 3);
        for (int b : bytes) {
            b &= 0xff;
            sb.append(HEXDIGITS[b >> 4]);
            sb.append(HEXDIGITS[b & 15]);
            sb.append(' ');
        }
        return sb.toString();
    }

    private static class SavingTrustManager implements X509TrustManager {

        private final X509TrustManager tm;
        private X509Certificate[] chain;

        SavingTrustManager(X509TrustManager tm) {
            this.tm = tm;
        }

        public X509Certificate[] getAcceptedIssuers() {
            // This change has been done due to the following resolution advised for Java 1.7+
            // http://infposs.blogspot.kr/2013/06/installcert-and-java-7.html
            return new X509Certificate[0];
            //throw new UnsupportedOperationException();
        }

        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            throw new UnsupportedOperationException();
        }

        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            this.chain = chain;
            tm.checkServerTrusted(chain, authType);
        }
    }

}
