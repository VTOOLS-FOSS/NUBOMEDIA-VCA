package com.visual_tools.nubomedia.nuboEyeJava;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.kurento.client.EventListener;
import org.kurento.client.IceCandidate;
import org.kurento.client.KurentoClient;
import org.kurento.client.MediaPipeline;
import org.kurento.client.OnIceCandidateEvent;
import org.kurento.client.WebRtcEndpoint;
import org.kurento.jsonrpc.JsonUtils;
import org.kurento.module.nuboeyedetector.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.kurento.client.EndpointStats;
import org.kurento.client.Stats;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

/**
 * Chroma handler (application and media logic).
 * 
 * @author Victor Hidalgo (vmhidalgo@visual-tools.com)
 * @since 6.0.0
 */
public class NuboEyeJavaHandler extends TextWebSocketHandler {

	private final Logger log = LoggerFactory.getLogger(NuboEyeJavaHandler.class);
	private static final Gson gson = new GsonBuilder().create();

	private final ConcurrentHashMap<String, UserSession> users = new ConcurrentHashMap<String, UserSession>();

	@Autowired
	private KurentoClient kurento;

	private NuboEyeDetector eye = null;
	private MediaPipeline pipeline = null;
    private WebRtcEndpoint webRtcEndpoint = null;
	private int visualizeEye = -1;

	@Override
	public void handleTextMessage(WebSocketSession session, TextMessage message)
			throws Exception {
		JsonObject jsonMessage = gson.fromJson(message.getPayload(),
				JsonObject.class);

		log.debug("Incoming message: {}", jsonMessage);

		switch (jsonMessage.get("id").getAsString()) {
		case "start":
			start(session, jsonMessage);
			break;
		case "show_eyes":	
			setVisualization(session,jsonMessage);
			break;
		case "scale_factor":
			setScaleFactor(session,jsonMessage);
		    break;
		case "process_num_frames":
			setProcessNumberFrames(session,jsonMessage);
		    break;
		case "width_to_process":
			setWidthToProcess(session,jsonMessage);
		    break;
		case "get_stats":			
			getStats(session);
			break;

		case "stop": {
			UserSession user = users.remove(session.getId());
			if (user != null) {
				user.release();
			}
			break;
		}
		case "onIceCandidate": {
			JsonObject candidate = jsonMessage.get("candidate")
					.getAsJsonObject();

			UserSession user = users.get(session.getId());
			if (user != null) {
				IceCandidate cand = new IceCandidate(candidate.get("candidate")
						.getAsString(), candidate.get("sdpMid").getAsString(),
						candidate.get("sdpMLineIndex").getAsInt());
				user.addCandidate(cand);
			}
			break;
		}

		default:
			sendError(session,
					"Invalid message with id "
							+ jsonMessage.get("id").getAsString());
			break;
		}
	}

	private void start(final WebSocketSession session, JsonObject jsonMessage) {
		try {
			// Media Logic (Media Pipeline and Elements)
			UserSession user = new UserSession();
			pipeline = kurento.createMediaPipeline();
			pipeline.setLatencyStats(true);
			user.setMediaPipeline(pipeline);
			webRtcEndpoint = new WebRtcEndpoint.Builder(pipeline)
					.build();
			user.setWebRtcEndpoint(webRtcEndpoint);
			users.put(session.getId(), user);

			webRtcEndpoint
					.addOnIceCandidateListener(new EventListener<OnIceCandidateEvent>() {

						@Override
						public void onEvent(OnIceCandidateEvent event) {
							JsonObject response = new JsonObject();
							response.addProperty("id", "iceCandidate");
							response.add("candidate", JsonUtils
									.toJsonObject(event.getCandidate()));
							try {
								synchronized (session) {
									session.sendMessage(new TextMessage(
											response.toString()));
								}
							} catch (IOException e) {
								log.debug(e.getMessage());
							}
						}
					});

			eye = new NuboEyeDetector.Builder(pipeline).build();

			
			webRtcEndpoint.connect(eye);
			eye.connect(webRtcEndpoint);

			// SDP negotiation (offer and answer)
			String sdpOffer = jsonMessage.get("sdpOffer").getAsString();
			String sdpAnswer = webRtcEndpoint.processOffer(sdpOffer);

			// Sending response back to client
			JsonObject response = new JsonObject();
			response.addProperty("id", "startResponse");
			response.addProperty("sdpAnswer", sdpAnswer);

			synchronized (session) {
				session.sendMessage(new TextMessage(response.toString()));
			}
			webRtcEndpoint.gatherCandidates();

		} catch (Throwable t) {
			sendError(session, t.getMessage());
		}
	}

	private void setVisualization(WebSocketSession session,JsonObject jsonObject)
        {

               	try{
                       	visualizeEye = jsonObject.get("val").getAsInt();
                        if (null != eye)
       	                        eye.showEyes(visualizeEye);

               	} catch (Throwable t){
                       	sendError(session,t.getMessage());
                }
        }

	private void setScaleFactor(WebSocketSession session,JsonObject jsonObject)
    {
	
	try{
	    int scale = jsonObject.get("val").getAsInt();
	    
	    if (null != eye)
		{
		    log.debug("Sending setscaleFactor...." + scale);		  
		    eye.multiScaleFactor(scale);
		}
	    
	} catch (Throwable t){
	    sendError(session,t.getMessage());
	}
    }

    private void setProcessNumberFrames(WebSocketSession session,JsonObject jsonObject)
    {
	
	try{
	    int num_img = jsonObject.get("val").getAsInt();
	    
	    if (null != eye)
		{
		    log.debug("Sending process num frames...." + num_img);
		    
		    eye.processXevery4Frames(num_img);
 		}
	    
	} catch (Throwable t){
	    sendError(session,t.getMessage());
	}
    }
		
    private void setWidthToProcess(WebSocketSession session,JsonObject jsonObject)
    {
	
	try{
	    int width = jsonObject.get("val").getAsInt();
	    
	    if (null != eye)
		{
		    log.debug("Sending width...." + width);
		    eye.widthToProcess(width);
		}
	    
	} catch (Throwable t){
	    sendError(session,t.getMessage());
	}
    } 

    private void getStats(WebSocketSession session)
    {    	
    	try {
    		Map<String,Stats> wr_stats= webRtcEndpoint.getStats();
    	
    		for (Stats s :  wr_stats.values()) {
    		
    			switch (s.getType()) {		
    			case endpoint:
    				EndpointStats end_stats= (EndpointStats) s;
    				double e2eVideLatency= end_stats.getVideoE2ELatency() / 1000000;
    				
    				JsonObject response = new JsonObject();
    				response.addProperty("id", "videoE2Elatency");
    				response.addProperty("message", e2eVideLatency);				
    				session.sendMessage(new TextMessage(response.toString()));				
    				break;
	
    			default:	
    				break;
    			}				
    		}
    	} catch (IOException e) {
			log.error("Exception sending message", e);
		}   
    }
    
	private void sendError(WebSocketSession session, String message) {
		try {
			JsonObject response = new JsonObject();
			response.addProperty("id", "error");
			response.addProperty("message", message);
			session.sendMessage(new TextMessage(response.toString()));
		} catch (IOException e) {
			log.error("Exception sending message", e);
		}
	}
}