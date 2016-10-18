var stompClient = null;

function setConnected(connected) {
    $("#connect").prop("disabled", connected);
    $("#disconnect").prop("disabled", !connected);
    if (connected) {
        $("#conversation").show();
    }
    else {
        $("#conversation").hide();
    }
    $("#greetings").html("");
}

function connect() {
    console.log("connectiing...")
    let baseline = '/orion'
    var socket = new SockJS(baseline);
    stompClient = Stomp.over(socket);
    stompClient.connect({}, function (frame) {
        setConnected(true);
        
        //let endpoint = socket._transport.url.substring(socket._transport.url.indexOf(baseline)+baseline.length); 
        let endpoint = socket._transport.url.substring(0,socket._transport.url.lastIndexOf("/"));
        endpoint = endpoint.substring(endpoint.lastIndexOf("/")+1);
        console.log(endpoint);
        
        stompClient.subscribe('/message/topic/greetings', function (greeting) {
        	console.log("subscribed... "+greeting);
            showGreeting(JSON.parse(greeting.body).content);
        });
        
        let endPoint = "/user/message/queue/orion";
        console.log(endPoint);
        stompClient.subscribe(endPoint, function (greeting) {
        	console.log("subscribed to ... "+endpoint);
            showGreeting(JSON.parse(greeting.body).content);
        });
    });
}

function disconnect() {
    if (stompClient != null) {
        stompClient.disconnect();
    }
    setConnected(false);
    console.log("Disconnected");
}

function register(){
    stompClient.send("/app/register", {}, "WALLA");
}


function sendName() {
    stompClient.send("/app/hello", {}, JSON.stringify({'name': $("#name").val()}));
}

function showGreeting(message) {
    $("#greetings").append("<tr><td>" + message + "</td></tr>");
}

$(function () {
    $("form").on('submit', function (e) {
        e.preventDefault();
    });
    $( "#connect" ).click(function() { connect(); });
    $( "#disconnect" ).click(function() { disconnect(); });
    $( "#send" ).click(function() { sendName(); });
    $("#register").click(function(){ register(); });
});

