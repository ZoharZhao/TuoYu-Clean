import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Base64;

/**
 * Converts the build key (PKCS#8 PEM private key + PEM certificate) into a
 * PKCS#12 keystore for apksigner. apksigner refuses PEM key files, and a
 * PKCS#12 store cannot be produced from the pure-Python tooling.
 *
 * Usage: java KeyConv <key.pem> <cert.pem> <out.p12> <password>
 */
public class KeyConv {
    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            System.err.println("usage: KeyConv <key.pem> <cert.pem> <out.p12> <password>");
            System.exit(2);
        }
        byte[] keyDer = pem(args[0], "PRIVATE KEY");
        byte[] certDer = pem(args[1], "CERTIFICATE");
        java.security.KeyFactory kf = java.security.KeyFactory.getInstance("RSA");
        PrivateKey key = kf.generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(keyDer));
        X509Certificate cert = (X509Certificate) java.security.cert.CertificateFactory
                .getInstance("X.509").generateCertificate(new ByteArrayInputStream(certDer));
        char[] pass = args[3].toCharArray();
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry("caffeine", key, pass, new java.security.cert.Certificate[]{cert});
        try (FileOutputStream out = new FileOutputStream(args[2])) {
            ks.store(out, pass);
        }
        System.out.println("wrote " + args[2]);
    }

    private static byte[] pem(String path, String label) throws Exception {
        String text = new String(readAll(path), "ASCII");
        StringBuilder body = new StringBuilder();
        for (String line : text.split("\\R")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("-----")) {
                continue;
            }
            body.append(line);
        }
        return Base64.getDecoder().decode(body.toString());
    }

    private static byte[] readAll(String path) throws Exception {
        try (FileInputStream in = new FileInputStream(path)) {
            byte[] buf = new byte[(int) new java.io.File(path).length()];
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n < 0) break;
                off += n;
            }
            return buf;
        }
    }
}
