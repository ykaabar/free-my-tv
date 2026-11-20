package io.freemytv;

import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.zip.CRC32;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * A Tuya device on the local network (Smart Life, LSC Smart Connect from Action and most other cheap Wi-Fi lights and
 * plugs), spoken to directly on TCP 6668 with its local key: no cloud, no account. Protocols 3.3, 3.4 and 3.5, as
 * tinytuya implements them (github.com/jasonacox/tinytuya, MIT), without any library.
 *
 *  - 3.3: frames 0x000055AA … CRC32 … 0x0000AA55, JSON encrypted with AES-128-ECB (the local key), "3.3" + 12 zero
 *    bytes before the ciphertext on commands.
 *  - 3.4: the same frames with an HMAC-SHA256 instead of the CRC, after a session key is agreed (nonces exchanged,
 *    each side proving it knows the local key); "3.4" + 12 zero bytes inside the ciphertext; CONTROL_NEW.
 *  - 3.5: frames 0x00006699 … 0x00009966, AES-128-GCM with the header as additional data, same session key steps.
 *
 * One connection per use (devices accept one or two at a time): connect, a few commands, close. Not thread-safe.
 */
final class Tuya implements Closeable {
    static final int PORT = 6668;
    private static final int CONTROL = 7, STATUS = 8, DP_QUERY = 10, CONTROL_NEW = 13, DP_QUERY_NEW = 16;
    private static final int SESS_KEY_NEG_START = 3, SESS_KEY_NEG_RESP = 4, SESS_KEY_NEG_FINISH = 5;
    private static final int PREFIX_55AA = 0x000055AA, SUFFIX_55AA = 0x0000AA55;
    private static final int PREFIX_6699 = 0x00006699, SUFFIX_6699 = 0x00009966;

    /** A message from the device: its command, return code and decrypted payload (JSON as text, or raw bytes). */
    static final class Message {
        final int cmd, retcode;
        final byte[] payload;
        Message(int cmd, int retcode, byte[] payload) { this.cmd = cmd; this.retcode = retcode; this.payload = payload; }
        String text() { return new String(payload, StandardCharsets.UTF_8); }
    }

    private final String host, id;
    private final byte[] localKey;
    final String version;
    private byte[] sessionKey;   // 3.4 and 3.5: agreed on connect; 3.3: the local key
    private Socket socket;
    private DataInputStream in;
    private OutputStream out;
    private int seq = 1;

    /** version: "3.3", "3.4" or "3.5". localKey: the 16 characters tinytuya's wizard prints. */
    Tuya(String host, String id, String localKey, String version) {
        this.host = host.trim();
        this.id = id.trim();
        this.localKey = localKey.getBytes(StandardCharsets.UTF_8);
        this.version = version;
        if (this.localKey.length != 16) throw new IllegalArgumentException("the local key must be 16 characters");
    }

    void connect(int timeoutMs) throws IOException {
        socket = new Socket();
        socket.connect(new InetSocketAddress(host, PORT), timeoutMs);
        socket.setSoTimeout(timeoutMs);
        socket.setTcpNoDelay(true);
        in = new DataInputStream(socket.getInputStream());
        out = socket.getOutputStream();
        sessionKey = localKey;
        if (!version.equals("3.3")) negotiate();
    }

    @Override public void close() {
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
        socket = null;
    }

    // MARK: - Commands

    /** All data points: {"20":true,"21":"colour",...}, or null if the device sent none. */
    JSONObject status() throws IOException {
        JSONObject q = new JSONObject();
        int cmd;
        try {
            if (version.equals("3.3")) {
                String t = String.valueOf(System.currentTimeMillis() / 1000);
                q.put("gwId", id).put("devId", id).put("uid", id).put("t", t);
                cmd = DP_QUERY;
            } else {
                cmd = DP_QUERY_NEW;   // an empty object: 3.4 and 3.5 know who they are
            }
        } catch (org.json.JSONException e) {
            throw new IOException(e);
        }
        send(cmd, q.toString());
        return awaitDps(cmd);
    }

    /** Sets data points ({"20":true,"22":800}); returns what the device then reports, or null. */
    JSONObject set(JSONObject dps) throws IOException {
        try {
            long t = System.currentTimeMillis() / 1000;
            if (version.equals("3.3")) {
                JSONObject c = new JSONObject().put("devId", id).put("uid", id).put("t", String.valueOf(t)).put("dps", dps);
                send(CONTROL, c.toString());
                return awaitDps(CONTROL);
            }
            JSONObject c = new JSONObject().put("protocol", 5).put("t", t).put("data", new JSONObject().put("dps", dps));
            send(CONTROL_NEW, c.toString());
            return awaitDps(CONTROL_NEW);
        } catch (org.json.JSONException e) {
            throw new IOException(e);
        }
    }

    /** Reads until a message carries data points (the reply, or the STATUS push that follows a command). */
    private JSONObject awaitDps(int sent) throws IOException {
        for (int i = 0; i < 4; i++) {
            Message m;
            try {
                m = receive();
            } catch (java.net.SocketTimeoutException e) {
                return null;   // some devices only acknowledge
            }
            if (m.payload.length == 0) continue;   // a bare acknowledgement
            JSONObject o = parseDps(m.text());
            if (o != null) return o;
            if (m.text().contains("data unvalid")) throw new IOException("the device refused the command (wrong ID?)");
            if (m.cmd != sent && m.cmd != STATUS) continue;
        }
        return null;
    }

    /** {"dps":{…}} (3.3), {"data":{"dps":{…}}} (3.4/3.5), with or without a leading version header. */
    static JSONObject parseDps(String text) {
        int brace = text.indexOf('{');
        if (brace < 0) return null;
        try {
            JSONObject o = new JSONObject(text.substring(brace));
            if (o.optJSONObject("dps") != null) return o.getJSONObject("dps");
            JSONObject data = o.optJSONObject("data");
            if (data != null && data.optJSONObject("dps") != null) return data.getJSONObject("dps");
        } catch (org.json.JSONException ignored) {
        }
        return null;
    }

    // MARK: - Session key (3.4, 3.5)

    private void negotiate() throws IOException {
        byte[] localNonce = new byte[16];
        new SecureRandom().nextBytes(localNonce);
        writeFrame(SESS_KEY_NEG_START, localNonce);
        Message r = receive();
        if (r.cmd != SESS_KEY_NEG_RESP || r.payload.length < 48) {
            throw new IOException("the device didn't answer the key exchange (wrong version or local key?)");
        }
        byte[] remoteNonce = Arrays.copyOfRange(r.payload, 0, 16);
        if (!Arrays.equals(Arrays.copyOfRange(r.payload, 16, 48), hmac(localKey, localNonce))) {
            throw new IOException("the local key doesn't match this device");
        }
        writeFrame(SESS_KEY_NEG_FINISH, hmac(localKey, remoteNonce));
        byte[] x = new byte[16];
        for (int i = 0; i < 16; i++) x[i] = (byte) (localNonce[i] ^ remoteNonce[i]);
        try {
            if (version.equals("3.4")) {
                sessionKey = aesEcb(Cipher.ENCRYPT_MODE, localKey, x, false);
            } else {
                byte[] sealed = gcm(Cipher.ENCRYPT_MODE, localKey, Arrays.copyOf(localNonce, 12), null, x);
                sessionKey = Arrays.copyOf(sealed, 16);   // the ciphertext, without the tag
            }
        } catch (GeneralSecurityException e) {
            throw new IOException(e);
        }
    }

    // MARK: - Frames

    private void send(int cmd, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        boolean header = cmd != DP_QUERY && cmd != DP_QUERY_NEW;
        if (version.equals("3.3")) {
            byte[] enc = encryptEcb(sessionKey, body);
            writeFrame(cmd, header ? concat(versionHeader(), enc) : enc);
        } else {
            writeFrame(cmd, header ? concat(versionHeader(), body) : body);
        }
    }

    private byte[] versionHeader() {
        byte[] h = new byte[15];
        byte[] v = version.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(v, 0, h, 0, 3);
        return h;
    }

    /** One frame out. 3.3: the payload as given (already encrypted). 3.4: encrypted here, HMAC. 3.5: GCM. */
    private void writeFrame(int cmd, byte[] payload) throws IOException {
        int n = seq++;
        ByteArrayOutputStream f = new ByteArrayOutputStream();
        try {
            if (version.equals("3.5")) {
                byte[] iv = new byte[12];
                new SecureRandom().nextBytes(iv);
                int length = 12 + payload.length + 16;
                ByteBuffer h = ByteBuffer.allocate(18).putInt(PREFIX_6699).putShort((short) 0).putInt(n).putInt(cmd).putInt(length);
                byte[] head = h.array();
                byte[] sealed = gcm(Cipher.ENCRYPT_MODE, sessionKey, iv, Arrays.copyOfRange(head, 4, 18), payload);
                f.write(head);
                f.write(iv);
                f.write(sealed);   // ciphertext + 16-byte tag
                f.write(ByteBuffer.allocate(4).putInt(SUFFIX_6699).array());
            } else {
                boolean v34 = version.equals("3.4");
                byte[] body = v34 ? encryptEcb(sessionKey, payload) : payload;
                int end = v34 ? 32 + 4 : 4 + 4;
                ByteBuffer h = ByteBuffer.allocate(16).putInt(PREFIX_55AA).putInt(n).putInt(cmd).putInt(body.length + end);
                f.write(h.array());
                f.write(body);
                byte[] signed = f.toByteArray();
                if (v34) {
                    f.write(hmac(sessionKey, signed));
                } else {
                    CRC32 crc = new CRC32();
                    crc.update(signed);
                    f.write(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
                }
                f.write(ByteBuffer.allocate(4).putInt(SUFFIX_55AA).array());
            }
        } catch (GeneralSecurityException e) {
            throw new IOException(e);
        }
        out.write(f.toByteArray());
        out.flush();
    }

    /** One frame in, checked and decrypted. Skips bytes until a prefix if the stream is out of step. */
    private Message receive() throws IOException {
        int prefix = in.readInt();
        for (int skipped = 0; prefix != PREFIX_55AA && prefix != PREFIX_6699; skipped++) {
            if (skipped > 4096) throw new IOException("no Tuya frame from the device");
            prefix = (prefix << 8) | in.readUnsignedByte();
        }
        try {
            if (prefix == PREFIX_6699) {
                byte[] head = new byte[18];
                ByteBuffer.wrap(head).putInt(PREFIX_6699);
                in.readFully(head, 4, 14);
                ByteBuffer hb = ByteBuffer.wrap(head);
                hb.position(10);
                int cmd = hb.getInt(), length = hb.getInt();
                if (length < 28 || length > 1 << 16) throw new IOException("bad frame length");
                byte[] rest = new byte[length + 4];
                in.readFully(rest);
                byte[] iv = Arrays.copyOfRange(rest, 0, 12);
                byte[] sealed = Arrays.copyOfRange(rest, 12, length);   // ciphertext + tag
                byte[] key = cmd == SESS_KEY_NEG_RESP ? localKey : sessionKey;
                byte[] plain = gcm(Cipher.DECRYPT_MODE, key, iv, Arrays.copyOfRange(head, 4, 18), sealed);
                int ret = plain.length >= 4 ? ByteBuffer.wrap(plain, 0, 4).getInt() : 0;
                return new Message(cmd, ret, stripHeader(Arrays.copyOfRange(plain, Math.min(4, plain.length), plain.length)));
            }
            int n = in.readInt(), cmd = in.readInt(), length = in.readInt();
            if (length < 8 || length > 1 << 16) throw new IOException("bad frame length");
            byte[] rest = new byte[length];
            in.readFully(rest);
            boolean v34 = !version.equals("3.3");
            int end = v34 ? 36 : 8;
            if (rest.length < end + 4) return new Message(cmd, 0, new byte[0]);
            int ret = ByteBuffer.wrap(rest, 0, 4).getInt();
            byte[] body = Arrays.copyOfRange(rest, 4, rest.length - end);
            byte[] key = cmd == SESS_KEY_NEG_RESP ? localKey : sessionKey;
            if (v34) {
                ByteBuffer signed = ByteBuffer.allocate(16 + rest.length - end).putInt(PREFIX_55AA).putInt(n).putInt(cmd).putInt(length);
                signed.put(rest, 0, rest.length - end);
                byte[] want = hmac(key, signed.array());
                if (!Arrays.equals(want, Arrays.copyOfRange(rest, rest.length - end, rest.length - 4))) {
                    throw new IOException("message signature doesn't match (wrong local key?)");
                }
                if (body.length == 0) return new Message(cmd, ret, body);
                return new Message(cmd, ret, stripHeader(aesEcb(Cipher.DECRYPT_MODE, key, body, true)));
            }
            body = stripHeader(body);
            if (body.length == 0 || body[0] == '{') return new Message(cmd, ret, body);
            return new Message(cmd, ret, aesEcb(Cipher.DECRYPT_MODE, key, body, true));
        } catch (GeneralSecurityException e) {
            throw new IOException("couldn't decrypt the device's answer (wrong local key?)", e);
        }
    }

    /** Drops a leading "3.x" + 12 bytes version header. */
    private static byte[] stripHeader(byte[] b) {
        if (b.length >= 15 && b[0] == '3' && b[1] == '.' && b[2] >= '1' && b[2] <= '5') return Arrays.copyOfRange(b, 15, b.length);
        return b;
    }

    // MARK: - Crypto

    private static byte[] encryptEcb(byte[] key, byte[] data) throws IOException {
        try {
            return aesEcb(Cipher.ENCRYPT_MODE, key, data, true);
        } catch (GeneralSecurityException e) {
            throw new IOException(e);
        }
    }

    private static byte[] aesEcb(int mode, byte[] key, byte[] data, boolean pad) throws GeneralSecurityException {
        Cipher c = Cipher.getInstance(pad ? "AES/ECB/PKCS5Padding" : "AES/ECB/NoPadding");
        c.init(mode, new SecretKeySpec(key, "AES"));
        return c.doFinal(data);
    }

    /** AES-GCM, 16-byte tag appended (encrypt) or expected at the end (decrypt). */
    private static byte[] gcm(int mode, byte[] key, byte[] iv, byte[] aad, byte[] data) throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        if (aad != null) c.updateAAD(aad);
        return c.doFinal(data);
    }

    private static byte[] hmac(byte[] key, byte[] data) throws IOException {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(key, "HmacSHA256"));
            return m.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IOException(e);
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] r = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }
}
