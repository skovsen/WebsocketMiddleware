package dk.alexandra.organicity.orion;


import java.io.IOException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Properties;
import java.util.TimeZone;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Form;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.apache.log4j.Logger;
import org.json.JSONObject;

import com.amaxilatis.orion.OrionClient;
import com.amaxilatis.orion.model.subscribe.OrionEntity;
import com.amaxilatis.orion.model.subscribe.SubscriptionResponse;

import dk.alexandra.organicity.config.JwtParser;
import dk.alexandra.orion.websocket.transports.OrionSubscription;
import io.jsonwebtoken.Claims;



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
    private String tokenUrl = null;
    private String token = null;
    private String wsClientId;
    private String wsClientSecret;
    private int delay = 0;

    
    
    public static void main(String[] args){
    	Connector c = new Connector();
    	
    }
    
    /**
	 * Initiates the connection to the Context Broker
	 * 
	 */
	public Connector(){
        TimeZone tz = TimeZone.getTimeZone("UTC");
        df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'");
        df.setTimeZone(tz);
        
        serverUrl = "http://192.168.121.132:1026";
        localURI = "http://192.168.121.1:8090/receiveNotifications";
        
        try{
        	properties = new Properties();
            properties.load(Thread.currentThread().getContextClassLoader().getResourceAsStream("connection.properties"));
            //serverUrl = properties.getProperty("serverUrl");
            
            localURI = properties.getProperty("localURI");
            tokenUrl = properties.getProperty("tokenUrl");
            wsClientId = properties.getProperty("clientId");
            wsClientSecret = properties.getProperty("clientSecret");
        }catch(IOException e){
        	e.printStackTrace();
        	LOGGER.error("not able to use properties. Continuing with default values");
        	System.exit(1);
        }
        
        
        token = getClientCredentialGrantToken(tokenUrl, wsClientId, wsClientSecret);
        
        LOGGER.info("Connecting to server url: "+serverUrl);
        client = new OrionClient(serverUrl,token, "organicity", "/");
        
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);
        //continously update token when it expires
        Runnable task = () -> {
            token = getClientCredentialGrantToken(tokenUrl, wsClientId, wsClientSecret);
            client = new OrionClient(serverUrl,token, "organicity", "/");
            LOGGER.info("Updated token from OAuth2 server");
        };

        executor.scheduleWithFixedDelay(task, delay, delay, TimeUnit.SECONDS);

	}
	
	private String getClientCredentialGrantToken(String tokenUrl, String clientId, String clientSecret) {
		LOGGER.info("getting new token");
		SSLContext sc = null;
		try{
			sc = SSLContext.getInstance("SSL"); 
		    sc.init(null, getTrustManager(), new java.security.SecureRandom());
		}catch(NoSuchAlgorithmException|KeyManagementException e){
			LOGGER.error("Exception thrown while setting up certificats: \n"+e);
			return null;
		}
		
		
		Client c = ClientBuilder.newBuilder().sslContext(sc).build();
		
		String path = "/realms/organicity/protocol/openid-connect/token";
		WebTarget webTarget = c.target(tokenUrl).path(path);
		LOGGER.info("Connecting to token Url: "+tokenUrl+path);
		String base64 = Base64.getEncoder().encodeToString((clientId+":"+clientSecret).getBytes());
		
		
		Invocation.Builder invocationBuilder = webTarget.request(MediaType.APPLICATION_JSON_TYPE).header("Authorization","Basic "+base64);
		

		Form form = new Form();		
		form.param("grant_type", "client_credentials");
		
		Entity entity =  Entity.entity(form, MediaType.APPLICATION_FORM_URLENCODED_TYPE);
		Response response = invocationBuilder.post(entity);
		
		JSONObject tokenEntity = new JSONObject(response.readEntity(String.class));
		//check if middleware can be authenticated
		String possibleError =tokenEntity.optString("error_description", null); 
		if(possibleError!=null){
			LOGGER.error("Not able to authentication middleware credentials");
			LOGGER.error("Error: "+possibleError);
			System.exit(1);	
		}
		
		try{
			String expires = tokenEntity.get("expires_in").toString();
			delay = Integer.parseInt(expires);
		}catch(NumberFormatException e){
			delay = 200;
		};
		
		return tokenEntity.get("access_token").toString();
	}
	
	
	/**
	 * Registering a subscription at the Context Broker
	 * 
	 * @param subscription A POJO containing the subscription data needed to set a subscription
	 * @param sessionId The id of the client requesting the subscription
	 * 
	 * @return The subscriptionId if successful, null otherwise
	 */
	public String[] registerSubscription(OrionSubscription subscription, String sessionId, String clientId){
		LOGGER.info("REGISTER SUBSCRIPTION!!!");
		String subscriptionId = null;
		String[] methodResponse = new String[2];
		methodResponse[0] = "error";
		//using clean java http client, as OrionClient is non functioning with simple get
		//Client c = ClientBuilder.newClient( new ClientConfig().register( LoggingFilter.class ) );
		SSLContext sc = null;
		try{
			sc = SSLContext.getInstance("SSL"); 
		    sc.init(null, getTrustManager(), new java.security.SecureRandom());
		}catch(NoSuchAlgorithmException|KeyManagementException e){
			LOGGER.error("Exception thrown while setting up certificats: \n"+e);
			return null;
		}
		
		
		Client c = ClientBuilder.newBuilder().sslContext(sc).build();
		WebTarget webTarget = c.target(serverUrl).path("/v2/entities/"+subscription.getEntityId());
		
		Invocation.Builder invocationBuilder =  webTarget.request(MediaType.APPLICATION_JSON).header("Fiware-Service", " organicity");
		Response checkResponse = invocationBuilder.get();
		
		JSONObject checkEntity = new JSONObject(checkResponse.readEntity(String.class));
		
		
		if(checkEntity.has("error")){
			//entity does not exist
			subscriptionId = "Sorry, entity not available"; 
			LOGGER.info("Client tried to access unknown entity: "+subscription.getEntityId());
		}else if(checkEntity.has("access:scope") && ((JSONObject)(checkEntity.get("access:scope"))).has("value") && ((JSONObject)(checkEntity.get("access:scope"))).get("value").equals("private") && !subscription.getEntityId().contains(clientId)){
			//entity is private and user does not have access
			subscriptionId = "Sorry, entity not available";
			LOGGER.info("Client tried to access private entity: "+subscription.getEntityId());
		}
		
		
		if(subscriptionId!=null){
			methodResponse[1] = subscriptionId;
			return methodResponse;
		}
			
		
		
		OrionEntity entity = new OrionEntity();
		entity.setId(subscription.getEntityId());
		String[] attributes = subscription.getAttributes();
		String[] conditions = subscription.getConditions();
		
		try{
			subscriptionId = "Not able to subscribe at the moment. Please try again";
			
			SubscriptionResponse response = client.subscribeChange(entity, attributes, localURI,conditions);
			if(response!=null){
				subscriptionId = response.getSubscribeResponse().getSubscriptionId();
				subscription.setSubscriberId(sessionId);
				subscriptions.put(subscriptionId, subscription);
				if(clientIndexedSubscriptions.get(sessionId)==null){
					clientIndexedSubscriptions.put(sessionId, new ArrayList<String>(Arrays.asList(subscriptionId)));
				}else{
					List<String> subscriptions = clientIndexedSubscriptions.get(sessionId);
					if(!subscriptions.contains(subscriptions)){
						subscriptions.add(subscriptionId);
					}
				}
				methodResponse[0] = "subscriptionId";
			}
			
		}catch(IOException e){
			LOGGER.error("Not able to add subscription: "+e.getStackTrace());
			subscriptionId = "Something went wrong when trying to subscribe. Please try again";
		}
		methodResponse[1] = subscriptionId;
		return methodResponse;
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
	
	/**
	 * Method for getting a trust manager for handling the SSL connections
	 * This is a VERY bad solution as it accepts all certificates. But it is needed as OC atm runs with self signed certs...
	 * 
	 * 
	 * @return A TrustManager array
	 */
	
	private TrustManager[] getTrustManager(){
		// Create a trust manager that does not validate certificate chains
		TrustManager[] trustAllCerts = new TrustManager[] { 
				new X509TrustManager() {     
					public java.security.cert.X509Certificate[] getAcceptedIssuers() { 
						return new X509Certificate[0];
					} 
					public void checkClientTrusted( 
							java.security.cert.X509Certificate[] certs, String authType) {
					} 
					public void checkServerTrusted( 
							java.security.cert.X509Certificate[] certs, String authType) {
					}
				} 
		};
		return trustAllCerts;	
	}



}
