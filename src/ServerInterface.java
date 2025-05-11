import java.rmi.Remote;
import java.rmi.RemoteException;

/**
 * This interface defines the methods that a server must implement to
 * communicate with other servers in the system.
 *
 * @author Jacqueline 
 */
public interface ServerInterface extends Remote {
    public static enum ServerType {
		MASTER, FRONTTIER, MIDTIER, INVALID;
	}
    
    public ServerType getNextServerType() throws RemoteException;
    public void setServerIsWaiting(int vmId, boolean isIdle) throws RemoteException;
    public void notifyRunning(ServerType serverType, int vmId) throws RemoteException;
    public void shutDown() throws RemoteException;
    public Cloud.FrontEndOps.Request pollRequest() throws RemoteException, InterruptedException;
    public void pushRequest(Cloud.FrontEndOps.Request request) throws RemoteException;
}
