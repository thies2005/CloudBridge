package ca.pkay.rcloneexplorer.util;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;

import javax.net.SocketFactory;

/**
 * SocketFactory connecting OkHttp over an app-private filesystem Unix domain socket via LocalSocket.
 */
public final class UnixDomainSocketFactory extends SocketFactory {

    private final File socketFile;

    public UnixDomainSocketFactory(@NonNull File socketFile) {
        this.socketFile = socketFile;
    }

    @Override
    public Socket createSocket() {
        return new UnixSocket(socketFile);
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        UnixSocket socket = new UnixSocket(socketFile);
        socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
        return socket;
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
        return createSocket(host, port);
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
        UnixSocket socket = new UnixSocket(socketFile);
        socket.connect(new InetSocketAddress(host != null ? host : InetAddress.getLoopbackAddress(), port));
        return socket;
    }

    @Override
    public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort)
            throws IOException {
        return createSocket(address, port);
    }

    private static final class UnixSocket extends Socket {

        private final File socketFile;
        private final LocalSocket localSocket;
        private volatile boolean connected;
        private volatile boolean closed;
        private volatile boolean inputShutdown;
        private volatile boolean outputShutdown;
        private volatile SocketAddress remoteAddress = new InetSocketAddress(InetAddress.getLoopbackAddress(), 80);
        private int soTimeoutMs;
        @Nullable private Integer receiveBufferSize;
        @Nullable private Integer sendBufferSize;

        UnixSocket(@NonNull File socketFile) {
            this.socketFile = socketFile;
            this.localSocket = new LocalSocket(LocalSocket.SOCKET_STREAM);
        }

        @Override
        public void connect(SocketAddress endpoint) throws IOException {
            connect(endpoint, 0);
        }

        @Override
        public void connect(SocketAddress endpoint, int timeout) throws IOException {
            synchronized (this) {
                if (closed) {
                    throw new SocketException("Socket is closed");
                }
                if (endpoint != null) {
                    this.remoteAddress = endpoint;
                }
            }
            LocalSocketAddress unixAddr = new LocalSocketAddress(
                    socketFile.getAbsolutePath(), LocalSocketAddress.Namespace.FILESYSTEM);
            // Connect outside the lock so concurrent close() can abort a blocked connect.
            localSocket.connect(unixAddr);
            synchronized (this) {
                if (closed) {
                    localSocket.close();
                    throw new SocketException("Socket is closed");
                }
                connected = true;
                if (soTimeoutMs > 0) {
                    localSocket.setSoTimeout(soTimeoutMs);
                }
                if (receiveBufferSize != null) {
                    localSocket.setReceiveBufferSize(receiveBufferSize);
                }
                if (sendBufferSize != null) {
                    localSocket.setSendBufferSize(sendBufferSize);
                }
            }
        }

        @Override
        public void bind(@Nullable SocketAddress bindpoint) {
            // Not applicable to client-side Unix domain sockets.
        }

        @Override
        public InputStream getInputStream() throws IOException {
            return localSocket.getInputStream();
        }

        @Override
        public OutputStream getOutputStream() throws IOException {
            return localSocket.getOutputStream();
        }

        @Override
        public synchronized void close() throws IOException {
            closed = true;
            connected = false;
            localSocket.close();
        }

        @Override
        public void shutdownInput() throws IOException {
            inputShutdown = true;
            localSocket.shutdownInput();
        }

        @Override
        public void shutdownOutput() throws IOException {
            outputShutdown = true;
            localSocket.shutdownOutput();
        }

        @Override
        public boolean isConnected() {
            return connected && !closed;
        }

        @Override
        public boolean isClosed() {
            // Android's LocalSocket.isClosed() unconditionally throws UnsupportedOperationException.
            return closed;
        }

        @Override
        public boolean isInputShutdown() {
            return inputShutdown;
        }

        @Override
        public boolean isOutputShutdown() {
            return outputShutdown;
        }

        @Override
        public synchronized void setSoTimeout(int timeout) throws SocketException {
            if (closed) {
                throw new SocketException("Socket is closed");
            }
            this.soTimeoutMs = timeout;
            if (!connected) {
                return;
            }
            try {
                localSocket.setSoTimeout(timeout);
            } catch (IOException e) {
                SocketException se = new SocketException(e.getMessage());
                se.initCause(e);
                throw se;
            }
        }

        @Override
        public synchronized int getSoTimeout() throws SocketException {
            if (closed) {
                throw new SocketException("Socket is closed");
            }
            if (!connected) {
                return soTimeoutMs;
            }
            try {
                return localSocket.getSoTimeout();
            } catch (IOException e) {
                return soTimeoutMs;
            }
        }

        @Override
        public void setTcpNoDelay(boolean on) {
            // No-op on Unix domain sockets.
        }

        @Override
        public boolean getTcpNoDelay() {
            return false;
        }

        @Override
        public void setKeepAlive(boolean on) {
            // No-op on Unix domain sockets.
        }

        @Override
        public boolean getKeepAlive() {
            return false;
        }

        @Override
        public synchronized void setReceiveBufferSize(int size) throws SocketException {
            if (closed) {
                throw new SocketException("Socket is closed");
            }
            this.receiveBufferSize = size;
            if (!connected) {
                return;
            }
            try {
                localSocket.setReceiveBufferSize(size);
            } catch (IOException e) {
                SocketException se = new SocketException(e.getMessage());
                se.initCause(e);
                throw se;
            }
        }

        @Override
        public synchronized int getReceiveBufferSize() throws SocketException {
            if (closed) {
                throw new SocketException("Socket is closed");
            }
            if (!connected) {
                return receiveBufferSize != null ? receiveBufferSize : 0;
            }
            try {
                return localSocket.getReceiveBufferSize();
            } catch (IOException e) {
                SocketException se = new SocketException(e.getMessage());
                se.initCause(e);
                throw se;
            }
        }

        @Override
        public synchronized void setSendBufferSize(int size) throws SocketException {
            if (closed) {
                throw new SocketException("Socket is closed");
            }
            this.sendBufferSize = size;
            if (!connected) {
                return;
            }
            try {
                localSocket.setSendBufferSize(size);
            } catch (IOException e) {
                SocketException se = new SocketException(e.getMessage());
                se.initCause(e);
                throw se;
            }
        }

        @Override
        public synchronized int getSendBufferSize() throws SocketException {
            if (closed) {
                throw new SocketException("Socket is closed");
            }
            if (!connected) {
                return sendBufferSize != null ? sendBufferSize : 0;
            }
            try {
                return localSocket.getSendBufferSize();
            } catch (IOException e) {
                SocketException se = new SocketException(e.getMessage());
                se.initCause(e);
                throw se;
            }
        }

        @Override
        public InetAddress getInetAddress() {
            return InetAddress.getLoopbackAddress();
        }

        @Override
        public InetAddress getLocalAddress() {
            return InetAddress.getLoopbackAddress();
        }

        @Override
        public int getPort() {
            if (remoteAddress instanceof InetSocketAddress) {
                return ((InetSocketAddress) remoteAddress).getPort();
            }
            return 80;
        }

        @Override
        public int getLocalPort() {
            return 0;
        }

        @Override
        public SocketAddress getRemoteSocketAddress() {
            return remoteAddress;
        }

        @Override
        public SocketAddress getLocalSocketAddress() {
            return new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);
        }
    }
}
