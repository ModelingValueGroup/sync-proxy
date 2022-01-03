//~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
// (C) Copyright 2018-2022 Modeling Value Group B.V. (http://modelingvalue.org)                                        ~
//                                                                                                                     ~
// Licensed under the GNU Lesser General Public License v3.0 (the 'License'). You may not use this file except in      ~
// compliance with the License. You may obtain a copy of the License at: https://choosealicense.com/licenses/lgpl-3.0  ~
// Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on ~
// an 'AS IS' BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the  ~
// specific language governing permissions and limitations under the License.                                          ~
//                                                                                                                     ~
// Maintainers:                                                                                                        ~
//     Wim Bast, Tom Brus, Ronald Krijgsheld                                                                           ~
// Contributors:                                                                                                       ~
//     Arjan Kok, Carel Bast                                                                                           ~
//~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

package org.modelingvalue.syncproxy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class Main {
    private static final int  DEFAULT_PORT      = 55055;
    private static final char DEFAULT_SEPARATOR = '\n';

    private static int connectionNumber;

    public static void main(String[] args) {
        boolean verbose   = false;
        int     port      = DEFAULT_PORT;
        char    separator = DEFAULT_SEPARATOR;

        if (1 <= args.length && args[0].equals("-v")) {
            verbose = true;
            System.arraycopy(args, 1, args, 0, args.length - 1);
        }
        if (3 <= args.length) {
            throw new Error("usage: $0 [-v] [<port-num> [<separator>]]");
        }
        if (2 <= args.length) {
            if (args[1].length() != 1) {
                throw new Error("separator must be exactly one character");
            }
            separator = args[1].charAt(0);
        }
        if (1 <= args.length) {
            port = Integer.parseInt(args[0]);
        }
        try {
            new Main(port, separator, verbose);
        } catch (IOException e) {
            System.err.println("could not open port: " + e.getMessage());
        }
    }

    private final char            separator;
    private final boolean         verbose;
    private final ServerSocket    listenSocket;
    private final int             port;
    private final Thread          listenThread;
    private final Set<SockReader> connectionSet = new HashSet<>();
    private       boolean         closingRequested;

    public Main() throws IOException {
        this(0, DEFAULT_SEPARATOR, false);
    }

    public Main(int port, char separator, boolean verbose) throws IOException {
        this.separator = separator;
        if (Character.toString(separator).getBytes().length != 1) {
            throw new Error("separator '" + separator + "' can not be used, only single byte separators are valid");
        }
        this.verbose = verbose;
        listenSocket = new ServerSocket(port);
        this.port    = listenSocket.getLocalPort();
        listenThread = new Thread(() -> {
            verbose("listening for clients on port " + this.port + "...");
            while (!listenSocket.isClosed()) {
                try {
                    addClient(listenSocket.accept());
                } catch (IOException e) {
                    if (!closingRequested) {
                        log("could not connect with client: " + e.getMessage());
                    }
                }
            }
            verbose("stop listening for clients on port " + this.port);
        }, "SyncProxy-" + this.port);
        listenThread.start();
    }

    public void verbose(String msg) {
        if (verbose) {
            log(msg);
        }
    }

    public void log(String msg) {
        System.err.println(msg);
    }

    public int getPort() {
        return port;
    }

    private synchronized void addClient(Socket sock) throws IOException {
        connectionSet.add(new SockReader(sock, connectionNumber++));
        verbose("new  client: " + sock + " (" + connectionSet.size() + " clients now)");
    }

    private synchronized void removeClient(SockReader sr) {
        if (connectionSet.remove(sr)) {
            verbose("lost client: " + sr.sock + " (" + connectionSet.size() + " clients now)");
        }
    }

    private synchronized List<SockReader> getClientList(SockReader except) {
        return connectionSet.stream().filter(sr -> !sr.equals(except)).collect(Collectors.toList());
    }

    public int getNumClients() {
        return connectionSet.size();
    }

    public void close() {
        closingRequested = true;
        try {
            listenSocket.close();
            listenThread.interrupt();
        } catch (IOException e) {
            log("error closing listening socket (" + listenSocket + "): " + e.getMessage());
        }
        List<SockReader> clientList = getClientList(null);
        clientList.forEach(SockReader::close);
        while (clientList.stream().anyMatch(Thread::isAlive)) {
            try {
                //noinspection BusyWait
                Thread.sleep(1);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    private class SockReader extends WorkDaemon<byte[]> {
        private final Socket       sock;
        private final InputStream  in;
        private final OutputStream out;

        public SockReader(Socket sock, int i) throws IOException {
            super("SyncProxyReader-" + i);
            this.sock = sock;
            this.in   = sock.getInputStream();
            this.out  = sock.getOutputStream();
            start();
        }

        @Override
        protected byte[] waitForWork() {
            try {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                int                   c;
                while ((c = in.read()) != separator && c != -1) {
                    b.write((byte) c);
                }
                if (c == -1 && b.size() == 0) {
                    verbose("reader-" + sock.getRemoteSocketAddress() + ": detected EOF");
                    return null;
                }
                return b.toByteArray();
            } catch (SocketException e) {
                if (e.getMessage().equals("Socket closed")) {
                    verbose("reader-" + sock.getRemoteSocketAddress() + ": socket closed");
                } else {
                    log("reader-" + sock.getRemoteSocketAddress() + ": unexpected exception: " + e);
                }
            } catch (IOException e) {
                log("reader-" + sock.getRemoteSocketAddress() + ": problem reading: " + e);
            }
            return null;
        }

        @Override
        protected void execute(byte[] bytes) {
            if (bytes == null) {
                verbose("reader-" + sock.getRemoteSocketAddress() + ": client disconnected");
                close();
            } else {
                verbose("reader-" + sock.getRemoteSocketAddress() + ": got '" + new String(bytes, StandardCharsets.UTF_8) + "'");
                getClientList(this).forEach(sr -> {
                    verbose("reader-" + sock.getRemoteSocketAddress() + ": relaying to " + sr.sock.getRemoteSocketAddress() + " '" + new String(bytes, StandardCharsets.UTF_8) + "'");
                    try {
                        sr.send(bytes);
                    } catch (IOException e) {
                        log("reader-" + sock.getRemoteSocketAddress() + ": relaying to " + sr.sock.getRemoteSocketAddress() + " failed: " + e.getMessage());
                        sr.close();
                    }
                });
            }
        }

        private void send(byte[] bytes) throws IOException {
            synchronized (Main.class) {
                out.write(bytes);
                out.write(separator);
                out.flush();
            }
        }

        @Override
        public void close() {
            super.close();
            interrupt();
            try {
                sock.close();
            } catch (IOException e) {
                log("error closing client socket (" + sock + "): " + e.getMessage());
            }
            removeClient(this);
        }
    }
}
