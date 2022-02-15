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

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

@SuppressWarnings("BusyWait")
class MainTest {
    @RepeatedTest(20)
    void checkThreads() throws IOException, InterruptedException {
        List<String> initialThreads = getCurrentThreadNames();

        Main       main       = new Main();
        int        actualPort = main.getPort();
        TestClient c0         = new TestClient(actualPort);
        TestClient c1         = new TestClient(actualPort);

        assertNumClientsAfterAWhile(main, 2);

        c0.writeLine("haystack1");
        assertEquals("haystack1", c1.readLine());

        c1.writeLine("haystack2");
        assertEquals("haystack2", c0.readLine());

        assertExcessThreadsAfterAWhile(initialThreads, 5);
        main.close();
        c0.interrupt();
        c1.interrupt();
        assertExcessThreadsAfterAWhile(initialThreads, 0);
    }

    @RepeatedTest(20)
    void twoClientsA() throws IOException, InterruptedException {
        List<String> initialThreads = getCurrentThreadNames();
        Main         main           = new Main();
        int          actualPort     = main.getPort();
        TestClient   c0             = new TestClient(actualPort);
        TestClient   c1             = new TestClient(actualPort);

        assertNumClientsAfterAWhile(main, 2);

        c0.writeLine("haystack1");
        assertEquals("haystack1", c1.readLine());

        c1.writeLine("haystack2");
        assertEquals("haystack2", c0.readLine());

        assertExcessThreadsAfterAWhile(initialThreads, 5);
        main.close();

        c0.writeLine("closed");
        assertNull(c1.readLine());

        c1.writeLine("closed");
        assertNull(c0.readLine());

        c0.interrupt();
        c1.interrupt();
        assertExcessThreadsAfterAWhile(initialThreads, 0);
    }

    @RepeatedTest(20)
    void twoClientsB() throws IOException, InterruptedException {
        List<String> initialThreads = getCurrentThreadNames();
        Main         main           = new Main();
        int          actualPort     = main.getPort();
        TestClient   c0             = new TestClient(actualPort);
        TestClient   c1             = new TestClient(actualPort);

        assertNumClientsAfterAWhile(main, 2);

        c0.writeLine("haystack1");
        c1.writeLine("haystack2");
        assertEquals("haystack1", c1.readLine());
        assertEquals("haystack2", c0.readLine());

        assertExcessThreadsAfterAWhile(initialThreads, 5);
        main.close();

        c0.writeLine("closed");
        c1.writeLine("closed");
        assertNull(c1.readLine());
        assertNull(c0.readLine());

        c0.interrupt();
        c1.interrupt();
        assertExcessThreadsAfterAWhile(initialThreads, 0);
    }

    @RepeatedTest(20)
    void longString() throws IOException, InterruptedException {
        List<String> initialThreads = getCurrentThreadNames();
        Main         main           = new Main();
        int          actualPort     = main.getPort();
        TestClient   c0             = new TestClient(actualPort);
        TestClient   c1             = new TestClient(actualPort);

        assertNumClientsAfterAWhile(main, 2);

        String s0 = longRandomString();
        String s1 = longRandomString();
        c0.writeLine(s0);
        c1.writeLine(s1);
        String ss1 = c1.readLine();
        String ss0 = c0.readLine();
        assertNotNull(ss0);
        assertNotNull(ss1);
        assertEquals(s0.length(), ss1.length());
        assertEquals(s1.length(), ss0.length());
        assertEquals(s0, ss1);
        assertEquals(s1, ss0);

        assertExcessThreadsAfterAWhile(initialThreads, 5);
        main.close();
        c0.interrupt();
        c1.interrupt();
        assertExcessThreadsAfterAWhile(initialThreads, 0);
    }

    @RepeatedTest(20)
    void manyStrings() throws IOException, InterruptedException {
        List<String> initialThreads = getCurrentThreadNames();
        Main         main           = new Main();
        int          actualPort     = main.getPort();
        TestClient   c0             = new TestClient(actualPort);
        TestClient   c1             = new TestClient(actualPort);

        assertNumClientsAfterAWhile(main, 2);

        for (int i = 0; i < 200; i++) {
            String s0 = mediumRandomString();
            String s1 = mediumRandomString();
            c0.writeLine(s0);
            c1.writeLine(s1);
            assertEquals(s0, c1.readLine());
            assertEquals(s1, c0.readLine());
        }

        assertExcessThreadsAfterAWhile(initialThreads, 5);
        main.close();
        c0.interrupt();
        c1.interrupt();
        assertExcessThreadsAfterAWhile(initialThreads, 0);
    }

    @ParameterizedTest
    @ValueSource(chars = {'$', '\n', ';', '|', '\0'})
    void threeClients(char sep) throws IOException, InterruptedException {
        System.err.println("threeClients SEP=" + sep);
        List<String> initialThreads = getCurrentThreadNames();
        Main         main           = new Main(0, sep, true);
        int          actualPort     = main.getPort();

        TestClient c0 = new TestClient(actualPort, sep);
        TestClient c1 = new TestClient(actualPort, sep);
        TestClient c2 = new TestClient(actualPort, sep);

        assertNumClientsAfterAWhile(main, 3);

        c0.writeLine("h•yståck1");
        assertEquals("h•yståck1", c1.readLine());
        assertEquals("h•yståck1", c2.readLine());

        c1.writeLine("haystack2");
        assertEquals("haystack2", c0.readLine());
        assertEquals("haystack2", c2.readLine());

        c2.writeLine("haystack3");
        assertEquals("haystack3", c0.readLine());
        assertEquals("haystack3", c1.readLine());

        assertExcessThreadsAfterAWhile(initialThreads, 7);
        main.close();
        c0.interrupt();
        c1.interrupt();
        c2.interrupt();
        assertExcessThreadsAfterAWhile(initialThreads, 0);
    }

    private List<String> getCurrentThreadNames() {
        return Thread.getAllStackTraces().keySet().stream()
                .map(Thread::getName)
                .filter(n -> !n.matches("junit-timeout-thread.*"))
                .sorted()
                .collect(Collectors.toList());
    }

    private void assertNumClientsAfterAWhile(Main main, int expectedNumClients) {
        assertTimeoutPreemptively(Duration.ofSeconds(100), () -> {
            while (main.getNumClients() != expectedNumClients) {
                Thread.sleep(1);
            }
        }, () -> "The number of clients did not get " + expectedNumClients + " in time (it is " + main.getNumClients() + " now)");
    }

    private void assertExcessThreadsAfterAWhile(List<String> initialThreadNames, int extra) {
        int initialSize = initialThreadNames.size();
        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
            while (initialSize + extra != getCurrentThreadNames().size()) {
                Thread.sleep(1);
            }
        }, () -> {
            List<String> nowThreadNames = getCurrentThreadNames();
            int          nowSize        = nowThreadNames.size();
            List<String> nowExtraNames = nowThreadNames.stream()
                    .filter(t -> !initialThreadNames.contains(t))
                    .sorted()
                    .collect(Collectors.toList());
            if (extra == 0) {
                return "the number of Threads did not return to " + initialSize + " but remained " + nowSize + " (extra: " + nowExtraNames + ")";
            } else {
                return "the number of Threads did not increase by " + extra + " but by " + (nowSize - initialSize) + " (extra: " + nowExtraNames + ")";
            }
        });
    }

    private static String mediumRandomString() {
        return getRandomString(1024);
    }

    private static String longRandomString() {
        return getRandomString(1024 * 1024);
    }

    private static String getRandomString(int length) {
        byte[] bytes = new byte[length];
        new Random().nextBytes(bytes);
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == '\n' || bytes[i] == '\r') {
                bytes[i] = '.';
            }
        }
        bytes[bytes.length - 1] = '.';
        bytes[bytes.length - 2] = '.';
        return new String(bytes);
    }

    private static class TestClient extends WorkDaemon<String> {
        private static final String                EOL_TOKEN = "EOL_TOKEN";
        private final        char                  separator;
        private final        Socket                sock;
        private final        BufferedReader        in;
        private final        PrintWriter           out;
        private final        BlockingQueue<String> lineQueue = new LinkedBlockingQueue<>();

        public TestClient(int port) throws IOException {
            this(port, '\n');
        }

        public TestClient(int port, char separator) throws IOException {
            super("SyncProxy-tester");
            this.separator = separator;
            sock           = new Socket((String) null, port);
            in             = new BufferedReader(new InputStreamReader(sock.getInputStream()));
            out            = new PrintWriter(sock.getOutputStream(), true);
            start();
        }

        @Override
        protected String waitForWork() {
            try {
                if (separator == '\n') {
                    return in.readLine();
                } else {
                    StringBuilder b = new StringBuilder();
                    int           c;
                    while ((c = in.read()) != separator && c != -1) {
                        b.append((char) c);
                    }
                    if (c == -1 && b.length() == 0) {
                        return null;
                    }
                    return b.toString();
                }
            } catch (IOException e) {
                return null;
            }
        }

        @Override
        protected void execute(String line) throws InterruptedException {
            if (line == null) {
                close();
                lineQueue.put(EOL_TOKEN);
            } else {
                lineQueue.put(line);
            }
        }

        public void writeLine(String line) {
            assertEquals(-1, line.indexOf(separator));
            if (!sock.isClosed() && sock.isConnected()) {
                out.write(line);
                out.write(separator);
                out.flush();
            }
        }

        public String readLine() throws InterruptedException {
            String line = lineQueue.poll(60_000, TimeUnit.MILLISECONDS);
            //noinspection StringEquality
            if (line == EOL_TOKEN) {
                lineQueue.put(line);
                line = null;
            }
            return line;
        }
    }
}
