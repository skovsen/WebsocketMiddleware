package dk.alexandra.organicity.webserver;

import java.io.IOException;

import javax.annotation.PostConstruct;

import org.apache.log4j.BasicConfigurator;
import org.apache.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageSendingOperations;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.socket.messaging.SessionConnectEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;

import com.fasterxml.jackson.databind.ObjectMapper;

import dk.alexandra.organicity.orion.Connector;
import dk.alexandra.orion.websocket.transports.Notification;
import dk.alexandra.orion.websocket.transports.OrionSubscription;
import dk.alexandra.orion.websocket.transports.OutOfBandMessage;
import dk.alexandra.orion.websocket.transports.pojo.ContextElement;


/**
 * 
 * @author Morten Skov
 *
 * Spring.io class for acting as middleware between WS/STOMP clients and Orion Context Broker  
 *
 */

@Controller
public class OrionController {

	ObjectMapper mapper = new ObjectMapper();
	private Connector connector;
	
	protected static final Logger LOGGER = Logger.getLogger(OrionController.class);
	
	@Autowired
    public SimpMessageSendingOperations messagingTemplate;


	
	/**
	 * Endpoint for registering to a entity in the Orion Context Broker
	 * 
	 * @param headerAccessor headers from client
	 * @param payload from client, contains a {@link dk.alexandra.orion.websocket.transports.OrionSubscription}
	 */
    @MessageMapping("/register")
    public void registerSubscription(SimpMessageHeaderAccessor headerAccessor, String payload) {
        String sessionId = headerAccessor.getSessionId(); // Session ID
        
        
        LOGGER.info("client registered with sessionID: "+sessionId);
        LOGGER.info(sessionId+" sent following payload: "+payload);
        OrionSubscription subscription = null;
        
        try{
        	subscription = mapper.readValue(payload, OrionSubscription.class);
        }catch(IOException e){
        	e.printStackTrace();
        }
        String subscriptionId = connector.registerSubscription(subscription, sessionId);

        OutOfBandMessage message = new OutOfBandMessage();
        if(subscriptionId==null){
        	message.setType("error");
        	message.setMessage("Subscription not added "+subscription.getId());
        }else{
        	message.setType("subscriptionId");
        	message.setMessage(subscriptionId);
        }
        messagingTemplate.convertAndSendToUser(sessionId,"/message/queue/orion", message, createHeaders(sessionId));
    }
    
    
    /**
	 * Endpoint for removing a subscription.
	 * 
	 * @param headerAccessor headers from client
	 * @param payload from client. contains a {@link dk.alexandra.orion.websocket.transports.OutOfBandMessage} with subscriptionId to remove
	 */
    @MessageMapping("/unregister")
    public void unregisterSubscription(SimpMessageHeaderAccessor headerAccessor, String payload) {
    	String sessionId = headerAccessor.getSessionId(); // Session ID
    	OutOfBandMessage receivedMessage = null;
    	OutOfBandMessage responseMessage = new OutOfBandMessage();
    	String subscriptionId = null;
    	try{
    		receivedMessage= mapper.readValue(payload, OutOfBandMessage.class);
    		subscriptionId = connector.removeSubscription(receivedMessage.getMessage(), sessionId);
        }catch(IOException e){
        	e.printStackTrace();
        }
    	
    	LOGGER.info("response from unsubscribe: "+subscriptionId);
        if(subscriptionId==null){
        	responseMessage.setType("error");
        	responseMessage.setMessage("Subscription not removed "+receivedMessage.getMessage());
        }else{
        	responseMessage.setType("removeSubscription");
        	responseMessage.setMessage(subscriptionId);
        }
        messagingTemplate.convertAndSendToUser(sessionId,"/message/queue/orion", responseMessage, createHeaders(sessionId));
    	
    }
    
    
    /**
	 * Method for sending the recieved notification from Context Broker to correct client
	 * 
	 * @param notification from Context Broker. @see {@link dk.alexandra.orion.websocket.transports.Notification}
	 */
    private void sendNotification(Notification notification){
    	
    	String sessionId = connector.getSubscriptionSessionId(notification.getSubscriptionId());
    	
    	if(sessionId==null){
    		//session not found, so we discard the message
    		LOGGER.info("No Session id found in list. Ignoring..");
    		return;
    	}
    	LOGGER.info("sending to: "+sessionId);
    	messagingTemplate.convertAndSendToUser(sessionId,"/message/queue/orion", notification, createHeaders(sessionId));
    	
    }
    
    /**
	 * Method for creating the correct headers when sending a message via WS
	 * 
	 * @param sessionId The id of the client to send to
	 * 
	 * @return The headers needed 
	 */
    private MessageHeaders createHeaders(String sessionId) {
        SimpMessageHeaderAccessor headerAccessor = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        headerAccessor.setSessionId(sessionId);
        headerAccessor.setLeaveMutable(true);
        return headerAccessor.getMessageHeaders();
    }
    
    
    /**
	 * EventListener for when a client subscribes to an endpoint
	 * Sends the sessionId back to the client 
	 * 
	 * @param event the subscription event
	 */
    @EventListener
    public void handleSubscribeEvent(SessionSubscribeEvent event) {
        LOGGER.info("SubscribeEvent: "+event);
        StompHeaderAccessor headers = StompHeaderAccessor.wrap(event.getMessage());
        String sessionId = headers.getSessionId();
        
        OutOfBandMessage response = new OutOfBandMessage("sessionId",sessionId);
        messagingTemplate.convertAndSendToUser(sessionId,"/message/queue/orion", response, createHeaders(sessionId));
    }
	
    @EventListener
    public void handleConnectEvent(SessionConnectEvent event) {
    	//LOGGER.info("connection "+event);	
    }
    
    /**
	 * EventListener for when a client disconnects
	 * Responsible for cleaning up 
	 * 
	 * @param event the disconnect event
	 */
    @EventListener
    public void handleDisconnectEvent(SessionDisconnectEvent event){
    	LOGGER.info("DisconnectEvent "+event);
    	StompHeaderAccessor headers = StompHeaderAccessor.wrap(event.getMessage());
        String sessionId = headers.getSessionId();
        boolean res = connector.clientDisconnected(sessionId);
        String str = "Client: "+sessionId+" was";
        if(res){
        	str+=" disconnected sucessfully";
        }else{
        	str+=" unsuccessfully disconnected";
        }
        
        LOGGER.info(str);
    }
    
    
    /**
	 * POST endpoint for recieving updates from the Context Broker
	 * Sends the sessionId back to the client 
	 * 
	 * @param res the string containing in the information
	 * 
	 * @return A responseEntity to connecting client
	 */
    @PostMapping(value = "/receiveNotifications")
	public ResponseEntity<String> receiveNotification(@RequestBody String res) {
    	
    	JSONObject json = new JSONObject(res);
    	String subscriptionId = json.getString("subscriptionId");
    	JSONArray arr = json.getJSONArray("contextResponses");
    	JSONObject element = ((JSONObject)arr.get(0)).getJSONObject("contextElement");
    	
    	
    	try {
    		ContextElement elm = mapper.readValue(element.toString(), ContextElement.class);
    		Notification not = new Notification();
    		not.setSubscriptionId(subscriptionId);
    		not.setElement(elm);
    		LOGGER.info("Sending notification: "+not);
    		sendNotification(not);
    		
    		
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
    	
		return new ResponseEntity<String>(res, HttpStatus.OK);
	}
    
    
    /**
	 * Called after constructor
	 * Initiates the connection to the Context Broker 
	 * 
	 */
    @PostConstruct
    private void initOrionClient(){
    	BasicConfigurator.configure();
    	
    	LOGGER.info("initializing Orion Context Broker client");
    	connector = new Connector();
        
    }

}
