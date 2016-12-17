package dk.alexandra.organicity.orion;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Properties;
import java.util.TimeZone;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.apache.log4j.Logger;
import org.glassfish.jersey.client.ClientConfig;
import org.glassfish.jersey.filter.LoggingFilter;
import org.json.JSONObject;

import com.amaxilatis.orion.OrionClient;
import com.amaxilatis.orion.model.subscribe.OrionEntity;
import com.amaxilatis.orion.model.subscribe.SubscriptionResponse;

import dk.alexandra.orion.websocket.transports.OrionSubscription;



/**
 * 
 * @author Morten Skov
 *
 * Responsible for handling the connections specific to the Orion Context Broker
 * Settings for the broker, can be set in conncetion.properties
 *
 */
public class Connector {
	
	protected static final Logger LOGGER = Logger.getLogger(Connector.class);
	
    private OrionClient client;
    private SimpleDateFormat df;
    private Properties properties;
    private String localURI;
    private HashMap<String, OrionSubscription> subscriptions = new HashMap<>();
    private HashMap<String, ArrayList<String>> clientIndexedSubscriptions = new HashMap<>();
    private String serverUrl;

    
    /*
    public static void main(String[] args){
    	Connector c = new Connector();
    	String[] attr = new String[1];
    	attr[0] = "temperature";
    	String[] cond = new String[1];
    	cond[0] = "pressure";
		String entityId = "urn:oc:entity:experimenters:cf2c1723-3369-4123-8b32-49abe71c0e57:5846db253be86fb0409329e8:11";
    	OrionSubscription subscription = new OrionSubscription(cond, attr, "P1D", entityId, false, "Room",null);
    	
    	
    	String res = c.registerSubscription(subscription, "XXX");
    	System.out.println(res);
    }
    */
    /**
	 * Initiates the connection to the Context Broker
	 * 
	 */
	public Connector(){
        TimeZone tz = TimeZone.getTimeZone("UTC");
        df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'");
        df.setTimeZone(tz);
        
        serverUrl = "http://192.168.121.132:1026";
        String token = "";
        localURI = "http://192.168.121.1:8090/receiveNotifications";
        try{
        	properties = new Properties();
            properties.load(Thread.currentThread().getContextClassLoader().getResourceAsStream("connection.properties"));
            serverUrl = properties.getProperty("serverUrl");
            token = properties.getProperty("token");
            localURI = properties.getProperty("localURI");
        }catch(IOException e){
        	e.printStackTrace();
        	LOGGER.error("not able to use properties. Continuing with default values");
        	
        }
        
        LOGGER.info("Connecting to url: "+serverUrl);
        client = new OrionClient(serverUrl,token, "organicity", "/");
        
	}
	
	
	/**
	 * Registering a subscription at the Context Broker
	 * 
	 * @param subscription A POJO containing the subscription data needed to set a subscription
	 * @param clientId The id of the client requesting the subscription
	 * 
	 * @return The subscriptionId if successful, null otherwise
	 */
	public String registerSubscription(OrionSubscription subscription, String clientId){
		String subscriptionId = null;
		
		//using clean java http client, as OrionClient is non functioning with simple get
		Client c = ClientBuilder.newClient( new ClientConfig().register( LoggingFilter.class ) );
		WebTarget webTarget = c.target(serverUrl).path("/v2/entities/"+subscription.getId());
		
		Invocation.Builder invocationBuilder =  webTarget.request(MediaType.APPLICATION_JSON).header("fiware-service", "organicity");
		Response checkResponse = invocationBuilder.get();
		
		JSONObject entity = new JSONObject(checkResponse.readEntity(String.class));

		if(entity.has("error")){
			//entity does not exist
			subscriptionId = "Sorry, entity not available"; 
			LOGGER.info("Client tried to access unknown entity: "+subscription.getId());
		}else if(entity.has("access:scope") && ((JSONObject)(entity.get("access:scope"))).has("value") && ((JSONObject)(entity.get("access:scope"))).get("value").equals("private")){
			subscriptionId = "Sorry, entity not available";
			LOGGER.info("Client tried to access private entity: "+subscription.getId());
		}
		
		
		if(subscriptionId!=null)
			return subscriptionId;
		
		
		OrionEntity entitty = new OrionEntity();
		entitty.setId(subscription.getId());
		entitty.setIsPattern(String.valueOf(subscription.isPattern()));
		entitty.setType(subscription.getType());
		String[] attributes = subscription.getAttributes();
		String[] conditions = subscription.getConditions();
		String duration = subscription.getDuration();
		try{
			SubscriptionResponse response = client.subscribeChange(entitty, attributes, localURI,conditions, duration);
			if(response!=null){
				subscriptionId = response.getSubscribeResponse().getSubscriptionId();
				subscription.setSubscriberId(clientId);
				subscriptions.put(subscriptionId, subscription);
				if(clientIndexedSubscriptions.get(clientId)==null){
					clientIndexedSubscriptions.put(clientId, new ArrayList<String>(Arrays.asList(subscriptionId)));
				}else{
					List<String> subscriptions = clientIndexedSubscriptions.get(clientId);
					if(!subscriptions.contains(subscriptions)){
						subscriptions.add(subscriptionId);
					}
				}
			}
			subscriptionId = "Not able to subscribe at the moment. Please try again";
		}catch(IOException e){
			LOGGER.error("Not able to add subscription: "+e.getStackTrace());
			subscriptionId = "Something went wrong when trying to subscribe. Please try again";
		}
		
		return subscriptionId;
	}
	
	
	/**
	 * Removing a subscription from Context Broker
	 * 
	 * @param subscriptionId The specific subscriptionId of the subscription wished to be removed
	 * @param clientId The id of the client requesting the subscription
	 * 
	 * @return subscriptionId if remove is successful, null otherwise
	 */
	public String removeSubscription(String subscriptionId, String clientId){
		try {
			LOGGER.info("Sending request to remove subscription with id: "+subscriptionId);
			SubscriptionResponse response = client.unSubscribeChange(subscriptionId);
			if(response.getSubscribeError()==null){
				subscriptions.remove(subscriptionId);
				List<String> clientSubscriptions = clientIndexedSubscriptions.get(clientId);
				if(clientSubscriptions!=null){
					clientSubscriptions.remove(subscriptionId);
				}
				return subscriptionId;
			}else{
				LOGGER.error("Error while unscribing subscription with id: "+subscriptionId+": "+response.getSubscribeError());
			}
		} catch (IOException e) {
			// TODO Auto-generated catch block
			LOGGER.error("Error while unscribing subscription with id: "+subscriptionId+": "+e.getStackTrace());
			//e.printStackTrace();
			
		}
		return null;
	}
	
	
	/**
	 * Method for getting a clientId from a given subscriptionId
	 * 
	 * @param subscriptionId The specific subscriptionId
	 * 
	 * @return The clientId from that subscription
	 */
	public String getSubscriptionSessionId(String subscriptionId){
		String sessionId = subscriptions.get(subscriptionId).getSubscriberId();
		return sessionId;
	}
	
	
	/**
	 * Get all subscriptions
	 * 
	 * @return The list of subscriptions
	 */
	public HashMap<String, OrionSubscription> getSubscriptions(){
		return subscriptions;
	}
	
	
	/**
	 * Method for handling cleaning up after a client disconnects
	 * 
	 * @param clientId The id of the client requesting the subscription
	 * 
	 * @return A boolean if clean up succeded
	 */
	public boolean clientDisconnected(String clientId){
		ArrayList<String> clientSubscriptions = clientIndexedSubscriptions.get(clientId);
		if(clientSubscriptions==null){
			//no subscriptions found. all good
			return true;
		}
		
		List<String> subscriptions = (ArrayList)clientSubscriptions.clone();
		
		boolean allGood = true;
		
		if(subscriptions!=null){
			for(String subscriptionId: subscriptions){
				String res = removeSubscription(subscriptionId, clientId);
				if(res==null){
					allGood=false;
				}
			}
		}
		clientIndexedSubscriptions.remove(clientId);
		
		return allGood;
	}

}
