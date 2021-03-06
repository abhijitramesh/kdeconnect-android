/*
 * Copyright 2014 Albert Vaca Cintora <albertvaka@gmail.com>
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License as
 * published by the Free Software Foundation; either version 2 of
 * the License or (at your option) version 3 or any later version
 * accepted by the membership of KDE e.V. (or its successor approved
 * by the membership of KDE e.V.), which shall act as a proxy
 * defined in Section 14 of version 3 of the license.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>. 
*/

package org.kde.kdeconnect.Backends.LanBackend;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;
import org.kde.kdeconnect.Backends.BaseLink;
import org.kde.kdeconnect.Backends.BasePairingHandler;
import org.kde.kdeconnect.Device;
import org.kde.kdeconnect.Helpers.SecurityHelpers.RsaHelper;
import org.kde.kdeconnect.Helpers.SecurityHelpers.SslHelper;
import org.kde.kdeconnect.Helpers.StringsHelper;
import org.kde.kdeconnect.NetworkPacket;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.channels.NotYetConnectedException;
import java.security.PublicKey;

import javax.net.ssl.SSLSocket;

public class LanLink extends BaseLink {

    public interface LinkDisconnectedCallback {
        void linkDisconnected(LanLink brokenLink);
    }

    public enum ConnectionStarted {
        Locally, Remotely
    }

    private ConnectionStarted connectionSource; // If the other device sent me a broadcast,
                                                // I should not close the connection with it
                                                  // because it's probably trying to find me and
                                                  // potentially ask for pairing.

    private volatile Socket socket = null;

    private final LinkDisconnectedCallback callback;

    @Override
    public void disconnect() {
        Log.i("LanLink/Disconnect","socket:"+ socket.hashCode());
        try {
            socket.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    //Returns the old socket
    public Socket reset(final Socket newSocket, ConnectionStarted connectionSource) throws IOException {

        Socket oldSocket = socket;
        socket = newSocket;

        this.connectionSource = connectionSource;

        if (oldSocket != null) {
            oldSocket.close(); //This should cancel the readThread
        }

        //Log.e("LanLink", "Start listening");
        //Create a thread to take care of incoming data for the new socket
        new Thread(() -> {
            try {
                BufferedReader reader = new BufferedReader(new InputStreamReader(newSocket.getInputStream(), StringsHelper.UTF8));
                while (true) {
                    String packet;
                    try {
                        packet = reader.readLine();
                    } catch (SocketTimeoutException e) {
                        continue;
                    }
                    if (packet == null) {
                        throw new IOException("End of stream");
                    }
                    if (packet.isEmpty()) {
                        continue;
                    }
                    NetworkPacket np = NetworkPacket.unserialize(packet);
                    receivedNetworkPacket(np);
                }
            } catch (Exception e) {
                Log.i("LanLink", "Socket closed: " + newSocket.hashCode() + ". Reason: " + e.getMessage());
                try { Thread.sleep(300); } catch (InterruptedException ignored) {} // Wait a bit because we might receive a new socket meanwhile
                boolean thereIsaANewSocket = (newSocket != socket);
                if (!thereIsaANewSocket) {
                    callback.linkDisconnected(LanLink.this);
                }
            }
        }).start();

        return oldSocket;
    }

    public LanLink(Context context, String deviceId, LanLinkProvider linkProvider, Socket socket, ConnectionStarted connectionSource) throws IOException {
        super(context, deviceId, linkProvider);
        callback = linkProvider;
        reset(socket, connectionSource);
    }


    @Override
    public String getName() {
        return "LanLink";
    }

    @Override
    public BasePairingHandler getPairingHandler(Device device, BasePairingHandler.PairingHandlerCallback callback) {
        return new LanPairingHandler(device, callback);
    }

    //Blocking, do not call from main thread
    private boolean sendPacketInternal(NetworkPacket np, final Device.SendPacketStatusCallback callback, PublicKey key) {
        if (socket == null) {
            Log.e("KDE/sendPacket", "Not yet connected");
            callback.onFailure(new NotYetConnectedException());
            return false;
        }

        try {

            //Prepare socket for the payload
            final ServerSocket server;
            if (np.hasPayload()) {
                server = LanLinkProvider.openServerSocketOnFreePort(LanLinkProvider.PAYLOAD_TRANSFER_MIN_PORT);
                JSONObject payloadTransferInfo = new JSONObject();
                payloadTransferInfo.put("port", server.getLocalPort());
                np.setPayloadTransferInfo(payloadTransferInfo);
            } else {
                server = null;
            }

            //Encrypt if key provided
            if (key != null) {
                np = RsaHelper.encrypt(np, key);
            }

            //Log.e("LanLink/sendPacket", np.getType());

            //Send body of the network package
            try {
                OutputStream writer = socket.getOutputStream();
                writer.write(np.serialize().getBytes(StringsHelper.UTF8));
                writer.flush();
            } catch (Exception e) {
                disconnect(); //main socket is broken, disconnect
                throw e;
            }

            //Send payload
            if (server != null) {
                Socket payloadSocket = null;
                OutputStream outputStream = null;
                InputStream inputStream = null;
                try {
                    //Wait a maximum of 10 seconds for the other end to establish a connection with our socket, close it afterwards
                    server.setSoTimeout(10*1000);

                    payloadSocket = server.accept();

                    //Convert to SSL if needed
                    if (socket instanceof SSLSocket) {
                        payloadSocket = SslHelper.convertToSslSocket(context, payloadSocket, getDeviceId(), true, false);
                    }

                    outputStream = payloadSocket.getOutputStream();
                    inputStream = np.getPayload().getInputStream();

                    Log.i("KDE/LanLink", "Beginning to send payload");
                    byte[] buffer = new byte[4096];
                    int bytesRead;
                    long size = np.getPayloadSize();
                    long progress = 0;
                    long timeSinceLastUpdate = -1;
                    while ((bytesRead = inputStream.read(buffer)) != -1) {
                        //Log.e("ok",""+bytesRead);
                        progress += bytesRead;
                        outputStream.write(buffer, 0, bytesRead);
                        if (size > 0) {
                            if (timeSinceLastUpdate + 500 < System.currentTimeMillis()) { //Report progress every half a second
                                long percent = ((100 * progress) / size);
                                callback.onProgressChanged((int) percent);
                                timeSinceLastUpdate = System.currentTimeMillis();
                            }
                        }
                    }
                    outputStream.flush();
                    Log.i("KDE/LanLink", "Finished sending payload ("+progress+" bytes written)");
                } finally {
                    try { server.close(); } catch (Exception e) { }
                    try { payloadSocket.close(); } catch (Exception e) { }
                    np.getPayload().close();
                    try { outputStream.close(); } catch (Exception e) { }
                }
            }

            callback.onSuccess();
            return true;
        } catch (Exception e) {
            if (callback != null) {
                callback.onFailure(e);
            }
            return false;
        } finally  {
            //Make sure we close the payload stream, if any
            if (np.hasPayload()) {
                np.getPayload().close();
            }
        }
    }


    //Blocking, do not call from main thread
    @Override
    public boolean sendPacket(NetworkPacket np, Device.SendPacketStatusCallback callback) {
        return sendPacketInternal(np, callback, null);
    }

    //Blocking, do not call from main thread
    @Override
    public boolean sendPacketEncrypted(NetworkPacket np, Device.SendPacketStatusCallback callback, PublicKey key) {
        return sendPacketInternal(np, callback, key);
    }

    private void receivedNetworkPacket(NetworkPacket np) {

        if (np.getType().equals(NetworkPacket.PACKET_TYPE_ENCRYPTED)) {
            try {
                np = RsaHelper.decrypt(np, privateKey);
            } catch(Exception e) {
                e.printStackTrace();
                Log.e("KDE/onPacketReceived","Exception decrypting the package");
            }
        }

        if (np.hasPayloadTransferInfo()) {
            Socket payloadSocket = new Socket();
            try {
                int tcpPort = np.getPayloadTransferInfo().getInt("port");
                InetSocketAddress deviceAddress = (InetSocketAddress) socket.getRemoteSocketAddress();
                payloadSocket.connect(new InetSocketAddress(deviceAddress.getAddress(), tcpPort));
                // Use ssl if existing link is on ssl
                if (socket instanceof SSLSocket) {
                    payloadSocket = SslHelper.convertToSslSocket(context, payloadSocket, getDeviceId(), true, true);
                }
                np.setPayload(new NetworkPacket.Payload(payloadSocket, np.getPayloadSize()));
            } catch (Exception e) {
                try { payloadSocket.close(); } catch(Exception ignored) { }
                e.printStackTrace();
                Log.e("KDE/LanLink", "Exception connecting to payload remote socket");
            }

        }

        packageReceived(np);
    }

    @Override
    public boolean linkShouldBeKeptAlive() {

        return true;    //FIXME: Current implementation is broken, so for now we will keep links always established

        //We keep the remotely initiated connections, since the remotes require them if they want to request
        //pairing to us, or connections that are already paired.
        //return (connectionSource == ConnectionStarted.Remotely);

    }
}
