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

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class DclareRouter {
	private static final int DEFAULT_PORT = 55055;
	private static final char DEFAULT_SEPARATOR = '\n';

	private static int connectionNumber;
	public  static boolean SHARE_TO_ALL; //for testing

	public static void main(String[] args) {
		boolean verbose = false;
		int port = DEFAULT_PORT;
		char separator = DEFAULT_SEPARATOR;

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
			new DclareRouter(port, separator, verbose);
		} catch (IOException e) {
			System.err.println("could not open port: " + e.getMessage());
		}
	}

	private final char separator;
	private final boolean verbose;
	private final ServerSocket listenSocket;
	private final int port;
	private final Thread listenThread;
	private final Set<ClientInfo> connectionSet = new HashSet<>();
	private boolean closingRequested;

	public DclareRouter() throws IOException {
		this(0, DEFAULT_SEPARATOR, false);
	}

	public DclareRouter(int port, char separator, boolean verbose) throws IOException {
		this.separator = separator;
		if (Character.toString(separator).getBytes().length != 1) {
			throw new Error("separator '" + separator + "' can not be used, only single byte separators are valid");
		}
		this.verbose = verbose;
		listenSocket = new ServerSocket(port);
		this.port = listenSocket.getLocalPort();
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
		console("started at port " + this.port);
	}

	public void verbose(String msg) {
		if (verbose) {
			log(msg);
		}
	}

	public char getSeparator() {
		return separator;
	}

	public static void log(String msg) {
		System.err.println(msg);
	}

	public static void console(String msg) {
		System.out.println("[Dclare Router] " + msg);
	}

	public int getPort() {
		return port;
	}

	public synchronized void addClient(Socket sock) throws IOException {
		SocketReader sr = new SocketReader(this, sock, connectionNumber++);

		connectionSet.add(new ClientInfo(sr, connectionNumber));
		console("client connected: " + sr + " (" + connectionSet.size() + " clients now)");
	}

	private synchronized ClientInfo findClient(SocketReader r) {
		return connectionSet.stream().filter(ci -> ci.socketReader.equals(r)).findAny().get();
	}

	public synchronized void removeClient(SocketReader sr) {
		if (connectionSet.remove(findClient(sr))) {
			console("client disconnected: " + sr + " (" + connectionSet.size() + " clients now)");
		}
	}
	
	public synchronized List<ClientInfo> getClientList(SocketReader except) {
		return connectionSet.stream().filter(ci -> !ci.socketReader.equals(except)).collect(Collectors.toList());
	}

	public byte[] filterMetaData(SocketReader r, byte[] b) {
		String s = new String(b);
		String metaDataMarker = "\"DServerMetaData:";
		int i = s.indexOf(metaDataMarker);
		if (i > -1) {
			int j = s.indexOf("\"", i + 2);
			j += 2;
			j = indexOfClosingMarker(s, j, '{', '}');
			String metaData = s.substring(i + 1, j);
			ClientInfo info = findClient(r);
			updateSharedModels(info, metaData);
			s = s.substring(0, i) + s.substring(j);
		}

		return s.getBytes();
	}

	private int indexOfClosingMarker(String s, int j, char open, char close) {
		int markerOpen = 1;
		while (j < s.length() && markerOpen > 0) {
			char ch = s.charAt(j);
			if (ch == open) {
				markerOpen += 1;
			} else if (ch == close) {
				markerOpen -= 1;
			}
			j++;
		}
		return j - 1;
	}

	private void updateSharedModels(ClientInfo info, String metaData) {
		String property = "SHARED_MODELS\":";
		String modelMarker = "\"DModel:";

		int sharedModelsIndex = metaData.indexOf(property);
		if (sharedModelsIndex > -1) {
			info.sharedModels.clear();
			int startIndex = sharedModelsIndex + property.length() + 1;
			startIndex = metaData.indexOf('[', startIndex);
			int endIndex = indexOfClosingMarker(metaData, startIndex + 1, '[', ']');
			String change = metaData.substring(startIndex, endIndex);
			String elements = change.substring(change.indexOf('[') + 1);

			int elementIndex = 0;
			while (elements.indexOf(modelMarker, elementIndex) > -1) {
				int modelIndex = elements.indexOf(modelMarker,elementIndex);
				int endModelIndex = elements.indexOf("\\\"", modelIndex);
				String modelId = elements.substring(modelIndex + 1, endModelIndex);
				info.sharedModels.add(modelId);
				elementIndex = endModelIndex;
			}
		    System.err.println("client "+ info + " shared models " + info.sharedModels);	
		}
	}
	
	public Map<String, List<String>> splitToChangesPerSharedModel(byte[] bytes) {
		String change = new String(bytes);
		Map<String,String> idChangeMap = new HashMap<>();
		Map<String, List<String>> modelToChangeMap = new HashMap<>();
		List<String> changes = new ArrayList<>();
		int index = 0;
		while (change.indexOf('\"',index)>0) {
			int startId  = change.indexOf('\"',index);
			int endId    = change.indexOf('\"',startId+1);
			String id = change.substring(startId+1,endId);
			int endOfChangeIndex = indexOfClosingMarker(change, endId+3, '{', '}');
			String oneChange = change.substring(startId,endOfChangeIndex+1);
			index = endOfChangeIndex;
			changes.add(oneChange);
			idChangeMap.put(id, oneChange);
		}
		
		idChangeMap.forEach((id,c)-> {
			String modelId = extractModelId(id);
			if (modelId!=null) {
				modelToChangeMap.computeIfAbsent(modelId, k->new ArrayList<>()).add(c);
			}
		});
			
		return modelToChangeMap;
	}
	
	private String extractModelId(String id) {
		if (id.startsWith("DModule")) {
			return null;
		} else if (id.startsWith("DModel")) {
			return id;
		} else if (id.startsWith("DNode")) {
			String modelIdMarker = ":r:";
			int modelMarkerIndex = id.indexOf(modelIdMarker);
			String modelPart = id.substring(modelMarkerIndex+modelIdMarker.length(),id.indexOf('/',modelMarkerIndex));
			return "DModel" + modelIdMarker + modelPart;
			
		}
		return null;
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
		List<ClientInfo> clientList = getClientList(null);
		clientList.stream().map(c->c.socketReader).forEach(SocketReader::close);
		while (clientList.stream().map(c->c.socketReader).anyMatch(Thread::isAlive)) {
			try {
				// noinspection BusyWait
				Thread.sleep(1);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
	}
}
