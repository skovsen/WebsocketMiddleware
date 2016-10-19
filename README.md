

# Organicity Orion Context Broker Middleware
Java Spring.io library to handle connections between the REST based notifications used by the context broker (https://fiware-orion.readthedocs.io/en/master/user/walkthrough_apiv2/index.html#introduction) and Websockets.
It has been intended to be used in conjection with our Java and Processing Websocket Client library (https://github.com/skovsen/WebsocketClient)


## Dependencies
This example was created using the following tools:
  Eclipse Neon
  Maven 3.3.9
  Spring.MVC
  Spring Boot
  https://github.com/OrganicityEu/OrganicityEntities

## Installation
This middleware requires an public IP adress where the Context Broker can send updates. For that there is connection.properties file in src/resources/ where it is possible to speicify the Orion Context Broker URL as well as any token usd for authentication. Another entry in this file is the localURL, which is your own URL to where the Context Broker should send the updates.

    serverUrl=http://192.168.121.132:1026
    token=
    localURI=http://192.168.121.1:8080/receiveNotifications

This is a Spring MVC application, using Spring Boot, which means that normal conventions are used for defining endpoints
    

