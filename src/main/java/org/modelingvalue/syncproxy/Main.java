//~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
// (C) Copyright 2018-2021 Modeling Value Group B.V. (http://modelingvalue.org)                                        ~
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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class Main {
    private static final boolean VERBOSE = Boolean.getBoolean("VERBOSE");

    private static int           connectionNumber;

    public static void main(String[] args) {
        if (args.length == 0) {
            args = new String[]{"55055"};
        } else if (args.length != 1) {
            throw new Error("usage: $0 <port-num>");
        }
        try {
            new Main(Integer.parseInt(args[0]));
        } catch (IOException e) {
            System.err.println("could not open port: " + e.getMessage());
        }
    }

    private final ServerSocket    listenSocket;
    private final Set<SockReader> connectionSet = new HashSet<>();
    private final int             port;
    private final Thread          listenThread;
    private boolean               closingRequested;

    public Main(int port) throws IOException {
        listenSocket = new ServerSocket(port);
        this.port = listenSocket.getLocalPort();
        listenThread = new Thread(() -> {
            if (VERBOSE) {
                System.err.println("listening for clients on port " + this.port + "...");
            }
            while (!listenSocket.isClosed()) {
                try {
                    addClient(listenSocket.accept());
                } catch (IOException e) {
                    if (!closingRequested) {
                        System.err.println("could not connect with client: " + e.getMessage());
                    }
                }
            }
            if (VERBOSE) {
                System.err.println("stop listening for clients on port " + this.port);
            }
        }, "SyncProxy-" + this.port);
        listenThread.start();
    }

    public int getPort() {
        return port;
    }

    private synchronized void addClient(Socket sock) throws IOException {
        connectionSet.add(new SockReader(sock, connectionNumber++));
        if (VERBOSE) {
            System.err.println("new  client: " + sock + " (" + connectionSet.size() + " clients now)");
        }
    }

    private synchronized void removeClient(SockReader sr) {
        if (connectionSet.remove(sr)) {
            if (VERBOSE) {
                System.err.println("lost client: " + sr.sock + " (" + connectionSet.size() + " clients now)");
            }
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
            System.err.println("error closing listening socket (" + listenSocket + "): " + e.getMessage());
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

    private class SockReader extends WorkDaemon<String> {
        private final Socket         sock;
        private final BufferedReader in;
        private final PrintWriter    out;

        public SockReader(Socket sock, int i) throws IOException {
            super("SyncProxyReader-" + i);
            this.sock = sock;
            this.in = new BufferedReader(new InputStreamReader(sock.getInputStream()));
            this.out = new PrintWriter(sock.getOutputStream(), true);
            start();
        }

        @Override
        protected String waitForWork() {
            try {
                return in.readLine();
            } catch (IOException e) {
                //System.err.println("could not read from client at " + sock.getRemoteSocketAddress() + ": " + e.getMessage());
                return null;
            }
        }

        @Override
        protected void execute(String line) {
            //System.err.println("proxy got: " + line + "  (on " + sock + ")");
            if (line == null) {
                close();
            } else {
                if (VERBOSE) {
                    System.err.println("send line " + line);
                }
                getClientList(this).forEach(sr -> {
                    //System.err.println("    write: " + line + "  (to " + sr.sock + ")");
                    sr.send(line);
                });
            }
        }

        private void send(String line) {
            out.write(line);
            out.write('\n');
            out.flush();
        }

        @Override
        public void close() {
            super.close();
            interrupt();
            try {
                sock.close();
            } catch (IOException e) {
                System.err.println("error closing client socket (" + sock + "): " + e.getMessage());
            }
            removeClient(this);
        }
    }
}
