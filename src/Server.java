import java.net.MalformedURLException;
import java.rmi.Naming;
import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * This class implements the ServerInterface and provides the methods
 * that a server must implement to communicate with other servers in the system.
 * It also provides methods to register and unregister the server,
 * scale up and down the servers, and manage the server properties.
 */
public class Server extends UnicastRemoteObject implements ServerInterface{
    // server lib class
    public static ServerLib SL;
    // server ip and port
    public static String ip;
    public static int port;
    // server id
    public static int VMid;
    // server type: MASTER, FRONTTIER, MIDTIER
    public static ServerType serverType;
    // a flag to indicate whether the server is shutting down
    public static boolean shuttingDown = false;

    /** 
     * ServerProperty class to store the properties of each server.
     * 
     * @param vmId the id of the server
     * @param isWaiting whether the server is waiting for a request
     * @param serverType the type of the server
     * @param serverInstance the instance of the server
     */
    public class ServerProperty {
        public int vmId;
        public boolean isWaiting;
        public ServerType serverType;
        public ServerInterface serverInstance;

        public ServerProperty(int vmId, ServerType serverType) {
            this.vmId = vmId;
            this.isWaiting = false;
            this.serverType = serverType;
            try {
                this.serverInstance = getServer(serverType, vmId);
            } catch (RemoteException e) {
                System.err.println(e.getMessage());
            }
        }

        public String toString() {
            return String.format("ServerProperty { vmId: %d, isWaiting: %s, serverType: %s }", vmId, isWaiting, serverType);
        }
    }

    // For Master Server only. Managing the whole load balancing
    // the server properties
    public static ConcurrentHashMap<Integer, ServerProperty> serverManager = new ConcurrentHashMap<>();
    // the number of servers
    public static ConcurrentHashMap<ServerType, Integer> numOfServers = new ConcurrentHashMap<>();
    // the number of servers waiting for a request
    public static ConcurrentHashMap<ServerType, BlockingQueue<Integer>> waitingServerIds = new ConcurrentHashMap<>();
    // the number of servers waiting for a request
    public static BlockingQueue<Optional<Cloud.FrontEndOps.Request>> requestQueue = new LinkedBlockingQueue<>();
    // the number of servers waiting for a request
    public static Queue<ServerType> boostingServerTypes = new ConcurrentLinkedQueue<>();

    // For Other servers only
    // the master server instance
    public static ServerInterface masterServer = null;

    public Server() throws RemoteException {}

    public static void DEBUG(String msg) {
        System.err.println(System.currentTimeMillis() / 100 % 1000 + " | " + msg);
    }

    /** 
     * Scale up or down the servers based on the queue length and number of servers.
     * 
     * @throws RemoteException
     */
    public static void scaleUpScaleDown() {
        try {
            float numFront = (float)numOfServers.getOrDefault(ServerType.FRONTTIER, 0) + 1;
            float boostingFront = (float)boostingServerTypes.stream().filter(s -> s == ServerType.FRONTTIER).mapToInt(s -> 1).sum();
            float frontQueueLen = (float)SL.getQueueLength();
            BlockingQueue<Integer> waitingFront = waitingServerIds.get(ServerType.FRONTTIER);

            float numMid = (float)numOfServers.getOrDefault(ServerType.MIDTIER, 0);
            float boostingMid = (float)boostingServerTypes.stream().filter(s -> s == ServerType.MIDTIER).mapToInt(s -> 1).sum();
            float midQueueLen = (float)requestQueue.size();
            BlockingQueue<Integer> waitingMid = waitingServerIds.get(ServerType.MIDTIER);

            boolean scaled = false;

            if (frontQueueLen > numFront * 0.6 + boostingFront * 0.6 && midQueueLen < numMid * 0.6 && numFront < numMid + 1) {
                addServer(ServerType.FRONTTIER);
                scaled = true;
            }

            if (midQueueLen > numMid * 1.2 + boostingMid * 1.2 && numMid < 9) {
                addServer(ServerType.MIDTIER);
                scaled = true;
            }
            
            if (waitingFront.size() > Math.max(2.0, numFront * 0.7)) {
                removeServer(ServerType.FRONTTIER, waitingFront.take());
                scaled = true;
            }
            if (waitingMid.size() > Math.max(2.0, numMid * 0.7)) {
                removeServer(ServerType.MIDTIER, waitingMid.take());
                scaled = true;
            }

            if (scaled) {
                try { Thread.sleep(500); } catch (InterruptedException e) { }
            }
        } catch (Exception e) {
            System.err.println(e.getMessage());
        }
    }
    
    /** 
     * Get the next server type to be boosted.
     * 
     * @return the next server type to be boosted
     * @throws RemoteException
     */
    @Override
    public synchronized ServerType getNextServerType()  throws RemoteException {
        return boostingServerTypes.poll();
    }

    /** 
     * Set the server to be waiting for a request.
     * 
     * @param vmId the id of the server
     * @param isWaiting whether the server is waiting for a request
     * @throws RemoteException
     */
    @Override
    public synchronized void setServerIsWaiting(int vmId, boolean isWaiting) throws RemoteException {
        ServerProperty serverProperty = serverManager.get(vmId);
        if (serverProperty == null || serverProperty.isWaiting == isWaiting) {
            return;
        }
        if (isWaiting) {
            waitingServerIds.get(serverProperty.serverType).add(vmId);
        }
        else {
            waitingServerIds.get(serverProperty.serverType).remove(vmId);
        }
        serverProperty.isWaiting = isWaiting;
    }

    /** 
     * Notify the master server that a server is running.
     * 
     * @param serverType the type of the server
     * @param vmId the id of the server
     * @throws RemoteException
     */
    @Override
    public synchronized void notifyRunning(ServerType serverType, int vmId) throws RemoteException {
        serverManager.put(vmId, new ServerProperty(vmId, serverType));
    }

    /** 
     * Shut down the server.
     * 
     * @throws RemoteException
     */
    @Override
    public synchronized void shutDown() throws RemoteException {
        shuttingDown = true;
        if (serverType == ServerType.FRONTTIER) {
            SL.interruptGetNext();
        }
        unRegisterServer();
        SL.shutDown();
    }

    /** 
     * FrontTier server push a request to the server.
     * 
     * @param request the request to be pushed
     * @throws RemoteException
     */
    @Override
    public void pushRequest(Cloud.FrontEndOps.Request request) throws RemoteException {
        if (serverManager.isEmpty()) {
            requestQueue.add(Optional.ofNullable(null));
            SL.dropRequest(request);
            return;
        }
        requestQueue.add(Optional.of(request));
    }

    /** 
     * MidTier server Poll a request from the server.
     * 
     * @return the request
     * @throws RemoteException
     * @throws InterruptedException
     */
    @Override
    public Cloud.FrontEndOps.Request pollRequest() throws RemoteException, InterruptedException {
        Optional<Cloud.FrontEndOps.Request> request = Optional.ofNullable(null);
        while (!request.isPresent()) {
            request = requestQueue.take();
        }
        return request.get();
    }

    /** 
     * Add a server.
     * 
     * @param serverType the type of the server
     * @throws RemoteException
     */
    public static void addServer(ServerType serverType) throws RemoteException {
        boostingServerTypes.add(serverType);
        numOfServers.merge(serverType, 1, Integer::sum);
        SL.startVM();
    }

    /** 
     * Remove a server.
     * 
     * @param serverType the type of the server
     * @param vmId the id of the server
     * @throws RemoteException
     */
    public static void removeServer(ServerType serverType, int vmId) throws RemoteException {
        ServerProperty serverRm = serverManager.get(vmId);
        serverManager.remove(vmId);
        numOfServers.merge(serverType, -1, Integer::sum);
        if (serverRm.isWaiting) {
            waitingServerIds.get(serverType).remove(vmId);
        }
        serverRm.serverInstance.shutDown();
    }

    /** 
     * Get the server instance using Naming.lookup.
     * 
     * @param serverType the type of the server
     * @param vmId the id of the server
     * @return the server instance
     * @throws RemoteException
     */
    public static ServerInterface getServer(ServerType serverType, int vmId) throws RemoteException {
        String url = serverType == ServerType.MASTER? String.format("//%s:%d/%s", ip, port, serverType.toString()): String.format("//%s:%d/%s-%d", ip, port, serverType.toString(), vmId);
        try{
            return (ServerInterface) Naming.lookup(url);
        }catch (Exception e){
            System.err.println(e);
            return null;
        }
    }

    /** 
     * Register the server using Naming.bind.
     * 
     * @param registerServerType the type of the server
     * @throws RemoteException
     */
    public static void registerServer(ServerType registerServerType) throws RemoteException {
        serverType = registerServerType;
        Server server = new Server();
        String url = serverType == ServerType.MASTER? String.format("//%s:%d/%s", ip, port, serverType.toString()): String.format("//%s:%d/%s-%d", ip, port, serverType.toString(), VMid);
        try {
            Naming.bind(url, server);
        }
        catch (Exception e) { 
            System.err.println(e);
        }
    }

    /** 
     * Unregister the server using Naming.unbind.
     * 
     * @throws RemoteException
     */
    public static void unRegisterServer() throws RemoteException {
        assert(serverType != ServerType.MASTER);
        try {
            Naming.unbind(String.format("//%s:%d/%s-%d", ip, port, serverType.toString(), VMid));
        }
        catch (Exception e) { 
            System.err.println(e);
        }
    }

    /** 
     * Set up the server and assign roles from the start.
     * 
     * @throws RemoteException
     */
    public static void setUpServer() throws RemoteException {
        masterServer = getServer(ServerType.MASTER, -1);
        if(masterServer == null) {
            registerServer(ServerType.MASTER);
            masterServer = getServer(ServerType.MASTER, -1);

            waitingServerIds.put(ServerType.FRONTTIER, new LinkedBlockingQueue<>());
            waitingServerIds.put(ServerType.MIDTIER, new LinkedBlockingQueue<>());

            addServer(ServerType.MIDTIER);
            addServer(ServerType.FRONTTIER);

            ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
            executor.scheduleAtFixedRate(() -> {
                scaleUpScaleDown();
            }, 500, 300, TimeUnit.MILLISECONDS);
        }
        else {
            registerServer(masterServer.getNextServerType());
            masterServer.notifyRunning(serverType, VMid);
        }
        if (serverType == ServerType.MASTER || serverType == ServerType.FRONTTIER) {
            SL.register_frontend();
        }
    }

    /** 
     * Execute the front tier server logic.
     * 
     * @throws RemoteException
     */
    public static void executeFrontTierServerLogic() throws RemoteException {
        // Cloud.FrontEndOps.Request r = SL.getNextRequest();
        masterServer.setServerIsWaiting(VMid, true);
        ServerLib.Handle h = SL.acceptConnection();
        masterServer.setServerIsWaiting(VMid, false);

        Cloud.FrontEndOps.Request r = SL.parseRequest( h );
        masterServer.pushRequest(r);
    }

    /** 
     * Execute the mid tier server logic.
     * 
     * @throws RemoteException
     */
    public static void executeMidTierServerLogic() throws RemoteException {
        try {
            masterServer.setServerIsWaiting(VMid, true);
            Cloud.FrontEndOps.Request r = masterServer.pollRequest();
            masterServer.setServerIsWaiting(VMid, false);

            SL.processRequest(r);
        } catch (Exception e) {
            System.err.println(e.getMessage());
        }
    }
    public static void main ( String args[] ) throws Exception {
        if (args.length != 3) throw new Exception("Need 3 args: <cloud_ip> <cloud_port> <VM id>");
        ip = args[0];
        port = Integer.parseInt(args[1]);
        VMid = Integer.parseInt(args[2]);

        SL = new ServerLib( ip, port );
        
        setUpServer();
        
        while (!shuttingDown) {
            switch (serverType) {
                case FRONTTIER:
                case MASTER:
                    executeFrontTierServerLogic();
                    break;
                case MIDTIER:
                    executeMidTierServerLogic();
                    break;
                default:
                    break;
            }
        }

        SL.endVM(VMid);
    }
}