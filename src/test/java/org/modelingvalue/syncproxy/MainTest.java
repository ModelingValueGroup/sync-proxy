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

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

import org.junit.jupiter.api.*;

class MainTest {
    @RepeatedTest(1)
    void checkThreads() throws IOException, InterruptedException {
        Set<Thread> initialThreads = Thread.getAllStackTraces().keySet();

        Main       main       = new Main(0);
        int        actualPort = main.getPort();
        TestClient c0         = new TestClient(actualPort);
        TestClient c1         = new TestClient(actualPort);

        c0.writeLine("haystack1");
        assertEquals("haystack1", c1.readLine());

        c1.writeLine("haystack2");
        assertEquals("haystack2", c0.readLine());

        assertEquals(5, getThreadCount(initialThreads));

        main.close();
        c0.interrupt();
        c1.interrupt();

        assertEquals(0, getThreadCount(initialThreads));
    }

    private long getThreadCount(Set<Thread> initialThreads) {
        return Thread.getAllStackTraces().keySet().stream().filter(t -> !initialThreads.contains(t)).count();
    }

    @RepeatedTest(1)
    void twoClientsA() throws IOException, InterruptedException {
        Main       main       = new Main(0);
        int        actualPort = main.getPort();
        TestClient c0         = new TestClient(actualPort);
        TestClient c1         = new TestClient(actualPort);

        c0.writeLine("haystack1");
        assertEquals("haystack1", c1.readLine());

        c1.writeLine("haystack2");
        assertEquals("haystack2", c0.readLine());

        main.close();

        c0.writeLine("closed");
        assertNull(c1.readNull());

        c1.writeLine("closed");
        assertNull(c0.readNull());

        c0.interrupt();
        c1.interrupt();
    }

    @RepeatedTest(1)
    void twoClientsB() throws IOException, InterruptedException {
        Main       main       = new Main(0);
        int        actualPort = main.getPort();
        TestClient c0         = new TestClient(actualPort);
        TestClient c1         = new TestClient(actualPort);

        c0.writeLine("haystack1");
        c1.writeLine("haystack2");
        assertEquals("haystack1", c1.readLine());
        assertEquals("haystack2", c0.readLine());

        main.close();

        c0.writeLine("closed");
        c1.writeLine("closed");
        assertNull(c1.readNull());
        assertNull(c0.readNull());

        c0.interrupt();
        c1.interrupt();
    }

    @RepeatedTest(1)
    void longString() throws IOException, InterruptedException {
        Main       main       = new Main(0);
        int        actualPort = main.getPort();
        TestClient c0         = new TestClient(actualPort);
        TestClient c1         = new TestClient(actualPort);

        String s0 = longRandomString();
        String s1 = longRandomString();
        c0.writeLine(s0);
        c1.writeLine(s1);
        assertEquals(s0, c1.readLine());
        assertEquals(s1, c0.readLine());

        main.close();
        c0.interrupt();
        c1.interrupt();
    }

    @RepeatedTest(1)
    void manyStrings() throws IOException, InterruptedException {
        Main       main       = new Main(0);
        int        actualPort = main.getPort();
        TestClient c0         = new TestClient(actualPort);
        TestClient c1         = new TestClient(actualPort);

        for (int i = 0; i < 1000; i++) {
            String s0 = mediumRandomString();
            String s1 = mediumRandomString();
            c0.writeLine(s0);
            c1.writeLine(s1);
            assertEquals(s0, c1.readLine());
            assertEquals(s1, c0.readLine());
        }

        main.close();
        c0.interrupt();
        c1.interrupt();
    }

    @RepeatedTest(1)
    void threeClients() throws IOException, InterruptedException {
        Main       main       = new Main(0);
        int        actualPort = main.getPort();
        TestClient c0         = new TestClient(actualPort);
        TestClient c1         = new TestClient(actualPort);
        TestClient c2         = new TestClient(actualPort);

        c0.writeLine("haystack1");
        assertEquals("haystack1", c1.readLine());
        assertEquals("haystack1", c2.readLine());

        c1.writeLine("haystack2");
        assertEquals("haystack2", c0.readLine());
        assertEquals("haystack2", c2.readLine());

        c2.writeLine("haystack3");
        assertEquals("haystack3", c0.readLine());
        assertEquals("haystack3", c1.readLine());

        main.close();
        c0.interrupt();
        c1.interrupt();
        c2.interrupt();
    }

    private static String mediumRandomString() {
        byte[] bytes = new byte[1024];
        new Random().nextBytes(bytes);
        return Arrays.toString(bytes);
    }

    private static String longRandomString() {
        byte[] bytes = new byte[1024 * 1024];
        new Random().nextBytes(bytes);
        return Arrays.toString(bytes);
    }

    private static class TestClient extends WorkDaemon<String> {
        private final Socket                sock;
        private final BufferedReader        in;
        private final PrintWriter           out;
        private final BlockingQueue<String> lineQueue = new LinkedBlockingQueue<>();

        public TestClient(int port) throws IOException {
            super("SyncProxy-tester");
            sock = new Socket("localhost", port);
            in = new BufferedReader(new InputStreamReader(sock.getInputStream()));
            out = new PrintWriter(sock.getOutputStream(), true);
            start();
        }

        @Override
        protected String waitForWork() {
            try {
                return in.readLine();
            } catch (IOException e) {
                return null;
            }
        }

        @Override
        protected void execute(String line) throws InterruptedException {
            if (line == null) {
                close();
            } else {
                lineQueue.put(line);
            }
        }

        public void writeLine(String line) {
            if (!sock.isClosed() && sock.isConnected()) {
                out.write(line);
                out.write('\n');
                out.flush();
            }
        }

        public String readNull() throws InterruptedException {
            return sock.isClosed() || !sock.isConnected() ? null : lineQueue.poll(50, TimeUnit.MILLISECONDS);
        }

        public String readLine() throws InterruptedException {
            return sock.isClosed() || !sock.isConnected() ? null : lineQueue.poll(1000, TimeUnit.MILLISECONDS);
        }
    }
}
