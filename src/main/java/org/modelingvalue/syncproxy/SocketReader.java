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
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

class SocketReader extends WorkDaemon<byte[]> {
    private final DclareRouter router;
    private final Socket       sock;
    private final InputStream  in;
    private final OutputStream out;

    public SocketReader(DclareRouter router, Socket sock, int i) throws IOException {
        super("SyncProxyReader-" + i);
        this.router = router;
        this.sock   = sock;
        this.in     = sock.getInputStream();
        this.out    = sock.getOutputStream();
        start();
    }

    private boolean isNonEmpty(byte[] b) {
        return !new String(b).equals("{}");
    }

    @Override
    protected byte[] waitForWork() {
        try {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            int                   c;
            while ((c = in.read()) != router.getSeparator() && c != -1) {
                b.write((byte) c);
            }
            if (c == -1 && b.size() == 0) {
                router.verbose("reader-" + sock.getRemoteSocketAddress() + ": detected EOF");
                return null;
            }
            return router.filterMetaData(this, b.toByteArray());
        } catch (SocketException e) {
            if (e.getMessage().equals("Socket closed") || e.getMessage().contains("Connection reset")) {
                router.verbose("reader-" + sock.getRemoteSocketAddress() + ": socket closed");
            } else {
                DclareRouter.log("reader-" + sock.getRemoteSocketAddress() + ": unexpected exception: " + e);
            }
        } catch (IOException e) {
            DclareRouter.log("reader-" + sock.getRemoteSocketAddress() + ": problem reading: " + e);
        }
        return null;
    }

    @Override
    protected void execute(byte[] bytes) {
        if (bytes == null) {
            router.verbose("reader-" + sock.getRemoteSocketAddress() + ": client disconnected");
            close();
        } else if (isNonEmpty(bytes)) {
            router.verbose("reader-" + sock.getRemoteSocketAddress() + ": got '" + new String(bytes, StandardCharsets.UTF_8) + "'");

            Map<String, List<String>> changesPerModel = DclareRouter.SHARE_TO_ALL ? null : router.splitToChangesPerSharedModel(bytes);

            router.getClientList(this).forEach(ci -> {
                SocketReader sr = ci.socketReader;
                router.verbose("reader-" + sock.getRemoteSocketAddress() + ": relaying to "
                               + sr.sock.getRemoteSocketAddress() + " '" + new String(bytes, StandardCharsets.UTF_8)
                               + "'");
                try {
                    byte[] change = bytes; //for testing
                    if (!DclareRouter.SHARE_TO_ALL && changesPerModel != null) {
                        change = ("{" + ci.sharedModels.stream().flatMap(m -> changesPerModel.getOrDefault(m, new ArrayList<>()).stream()).collect(Collectors.joining(", ")) + "}").getBytes();
                    }
                    if (isNonEmpty(change)) {
                        sr.send(change);
                    }
                } catch (IOException e) {
                    DclareRouter.log("reader-" + sock.getRemoteSocketAddress() + ": relaying to "
                                     + sr.sock.getRemoteSocketAddress() + " failed: " + e.getMessage());
                    sr.close();
                }
            });
        }
    }

    private void send(byte[] bytes) throws IOException {
        synchronized (DclareRouter.class) {
            out.write(bytes);
            out.write(router.getSeparator());
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
            DclareRouter.log("error closing client socket (" + sock + "): " + e.getMessage());
        }
        router.removeClient(this);
    }

    public String toString() {
        return "SocketReader[" + this.sock + "]";
    }
}