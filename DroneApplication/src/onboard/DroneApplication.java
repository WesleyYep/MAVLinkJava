/**
 * $Id: TestMavlinkReader.java 10 2013-04-26 13:04:11Z ghelle31@gmail.com $
 * $Date: 2013-04-26 15:04:11 +0200 (ven., 26 avr. 2013) $
 *
 * ======================================================
 * Copyright (C) 2012 Guillaume Helle.
 * Project : MAVLINK Java
 * Module : org.mavlink.library
 * File : org.mavlink.TestMavlinkReader.java
 * Author : Guillaume Helle
 *
 * ======================================================
 * HISTORY
 * Who       yyyy/mm/dd   Action
 * --------  ----------   ------
 * ghelle	31 aout 2012		Create
 * 
 * ====================================================================
 * Licence: MAVLink LGPL
 * ====================================================================
 */

package onboard;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.PipedInputStream;
import org.mavlink.MAVLinkReader;
import org.mavlink.messages.MAVLinkMessage;
import org.mavlink.messages.ja4rtor.msg_ahrs2;
import org.mavlink.messages.ja4rtor.msg_global_position_int;
import org.mavlink.messages.ja4rtor.msg_heartbeat;
import org.mavlink.messages.ja4rtor.msg_rc_channels_raw;
import jssc.SerialPortList;
import network.Client;
import serial.Reader;
import serial.Sender;
import serial.SerialPortCommunicator;

/**
 * This runs onboard the drone
 * It creates the threads for reading the HKPilot through UART serial cable, communication with the ground
 * station via Wifi, and for the image processing algorithm
 *
 */
public class DroneApplication {
	public Drone drone = new Drone(); //represents drone properties eg. yaw, roll, pitch
	private String direction = "";
	private Sender sender;
	private int channel1Mid = 0;
	private int channel2Mid = 0;
	private int channel3Mid = 0;
	private int channel4Mid = 0;
	private int testValue = 100; // the offset for the rc override messages
	private String ipAddress = "169.254.110.196";
//	private static boolean testMode = false;
	private boolean testMode = true;
	
    /**
     * Entry point of onboard drone application
     */
    public static void main(String[] args) {
    	DroneApplication application = new DroneApplication();
    	application.start(args);
    }
    
    /**
     * Start processing
     * @param args - the command line arguments given for testing certain features
     */
    public void start(String[] args) {
		SerialPortCommunicator spc = new SerialPortCommunicator();
		sender = new Sender(spc);
    	try {
			System.out.println("Trying to open " + SerialPortList.getPortNames()[0]);
			spc.openPort(SerialPortList.getPortNames()[0]);
			
			if (!spc.isOpened()) {
				System.err.println("Port not opened");
			} else {
				System.out.println("Port opened!");
			}
    	} catch (Exception ex) {
    		System.err.println("No ports available");
    	}
//    	
	
		ImageProcessing imageProcessing = new ImageProcessing(drone, this);
		
		imageProcessing.client = new Client(ipAddress, 55555, data ->{
			System.out.println(data.toString());		
			String[] arr = data.toString().split(":");
			if (data.toString().startsWith("stream:")) {
				imageProcessing.isStreaming = arr[1].equals("true");
			} else if (data.toString().startsWith("slider:")) {
				if (arr[1].equals("h")) {
					imageProcessing.hMin = Double.parseDouble(arr[2]);
					imageProcessing.hMax = Double.parseDouble(arr[3]);
				} else if (arr[1].equals("s")) {
					imageProcessing.sMin = Double.parseDouble(arr[2]);
					imageProcessing.sMax = Double.parseDouble(arr[3]);
				} else if (arr[1].equals("v")) {
					imageProcessing.vMin = Double.parseDouble(arr[2]);
					imageProcessing.vMax = Double.parseDouble(arr[3]);
				}
			} else if (data.toString().startsWith("arm:")) {
				testArm(sender, arr[1].equals("true"));	
			} else if (data.toString().startsWith("mode:")) {
				changeMode(arr[1], arr[2].equals("true"));
			} else if (data.toString().startsWith("land:")) {
				land(sender, arr[1]); //eg. land:10
			} else if (data.toString().startsWith("command:")) {
				direction = arr[1];
			} else if (data.toString().startsWith("test:")) {
				if (arr[1].equals("test")) {
					testMode = Boolean.parseBoolean(arr[2]);
				}
				System.out.println("Setting test value to: " + arr[1]);
				testValue = Integer.parseInt(arr[1]);
			}
		});
	
    	//start camera for QR detection
    	Thread imageProcessingThread = new Thread(new Runnable(){
			@Override
			public void run() {
				try {
					imageProcessing.start();
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
    	});
    	
    	//thread that deals with sending and receiving mavlink messages
    	Thread mavlinkThread = new Thread(new Runnable() {
    		@Override
			public void run() {
				String cmd = "";
				if (args.length != 0) {
					cmd = args[0];
				}
				
				if (cmd.equals("send")) {
					testSendToSerial(sender, Integer.parseInt(args[1]));
				} else if (cmd.equals("rec")){  // rec
					if (args.length > 1 && args[1].equals("hb")) { // rec hb
						sender.send(0);
					}
					testFromSerial(spc);
				} else if (cmd.equals("angle")){
					testAngle(sender, spc);
				} else if (cmd.equals("cmd")) {
					testCommands(sender, Integer.parseInt(args[1]), Integer.parseInt(args[2]), Integer.parseInt(args[3]));
				} else if (cmd.equals("arm")) {
					testArm(sender, args.length > 1 && args[1].equals("true"));
				} else if (cmd.equals("hb")) {
					testHeartBeat(sender);
				} else if (cmd.equals("mode")) {
					sender.heartbeat();
					sender.mode(args.length > 1 ? args[1] : "", args.length > 2 && args[2].equals("armed"));
				} else if (cmd.equals("land")) {
					land(sender, args[1]);
				} else if (cmd.equals("test")) {
					//sender.test(Integer.parseInt(args[1]), Integer.parseInt(args[2]));
					//testGuidedCommand(Double.parseDouble(args[1]));
				} else {
					testAngle(sender, spc);
				}
    		}
    	});
    	
		Thread moveCommandThread = new Thread(new Runnable() {
			@Override
			public void run() {
				//start move message sending
				command(sender, imageProcessing);
			}
		});
    	
		imageProcessingThread.start();
		mavlinkThread.start();
		moveCommandThread.start();
    }
    
    public void changeMode(String mode, boolean armed) {
    	sender.heartbeat();
		sender.mode(mode, armed);
	}

	private void command(Sender sender, ImageProcessing imageProcessing) {
    	while (true) {
			sender.heartbeat();
			
			if (testMode && imageProcessing.xOffsetValue != -99999 && imageProcessing.yOffsetValue != -99999) {
				if (drone.currentMode == 209) { //stabilize, alt_hold or land + ARMED mode
					//may need to reverse orientation after testing
					int xDirection = imageProcessing.xOffsetValue > 0 ? channel1Mid+testValue : channel1Mid-testValue;
					int yDirection = imageProcessing.yOffsetValue > 0 ? channel2Mid-testValue : channel2Mid+testValue;
					sender.rc(yDirection, xDirection, 0, 0);
				}
				
			} else {
				if (direction.equals("forward")) {
					if (drone.currentCustomMode == 9){ sender.land(0, 30); }
					else if (drone.currentCustomMode == 4){ sender.command(0, 0.5, 0); }
					else { sender.rc(0, channel2Mid+testValue, 0, 0); } //	public boolean rc(int aileronValue, int elevatorValue, int throttleValue, int rudderValue) {
				} else if (direction.equals("backward")) {
					if (drone.currentCustomMode == 9){ sender.land(0, -30); }
					else if (drone.currentCustomMode == 4){ sender.command(0, -0.5, 0); }
					else { sender.rc(0, channel2Mid-testValue, 0, 0); } // elevator only (controls pitch)
				} else if (direction.equals("left")) {
					if (drone.currentCustomMode == 9){ sender.land(-30, 0); }
					else if (drone.currentCustomMode == 4){ sender.command(-0.5, 0, 0); }
					else { sender.rc(channel1Mid-testValue, 0, 0, 0); } //aileron only (controls roll)
				} else if (direction.equals("right")) {
					if (drone.currentCustomMode == 9){ sender.land(30, 0); }
					else if (drone.currentCustomMode == 4){ sender.command(0.5, 0, 0); }
					else { sender.rc(channel1Mid+testValue, 0, 0, 0); }
				} else if (direction.equals("centre")) {
					if (drone.currentCustomMode == 9){ sender.land(0, 0); }
					else if (drone.currentCustomMode == 4){ sender.command(0,0,0); }
					else { sender.rc(0, 0, channel3Mid + testValue, 0); } // throttle only
				} else if (direction.equals("descend")) {
					if (drone.currentCustomMode == 9){ sender.land(0, 0); }
					else if (drone.currentCustomMode == 4){ sender.command(0,0,0.5); }
					else { sender.rc(0, 0, 0, 0); } //cancel all
				}
			}
			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {}
		}
	}   
    
    private void land(Sender sender, String degreesString) {
    	while (true) {
			if(sender.heartbeat()) {
	    		System.out.println("Successfully set heartbeat");
	    	}
			sender.land(Float.parseFloat(degreesString), 0);
			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {}
		}
	}    
    
	private void testHeartBeat(Sender sender) {
		if(sender.heartbeat()) {
    		System.out.println("Successfully set heartbeat");
    	}
	}
    
    private void testCommands(Sender sender,/* int value*/ int x, int y, int z) {
		while (true) {
			if(sender.heartbeat()) {
	    		System.out.println("Successfully set heartbeat");
	    	}
			if (sender.command(x, y, z)) {
				System.out.println("sent manual move message");
			}
			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {}
		}
    }
      
    private void testAngle(Sender sender, SerialPortCommunicator spc) {
    	sender.send(0); //stops all streams
    	sender.send(3); //rc raw values
		sender.send(6); // barometer for altitude
		sender.send(10); //pitch, yaw, roll
    	System.out.println("Sent request for orientation/altitude messages");
    	try {
			Thread.sleep(2000);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
        MAVLinkReader reader;
        int nb = 0;
    	Reader rdr = new Reader(spc);
    	PipedInputStream in = rdr.read();
        DataInputStream dis = new DataInputStream(in);
        reader = new MAVLinkReader(dis);
        try {
            while (true /*dis.available() > 0*/) {
                MAVLinkMessage msg = reader.getNextMessage();
                if (msg != null && msg.messageType == msg_ahrs2.MAVLINK_MSG_ID_AHRS2) {
                    nb++;
                    //System.out.println("SysId=" + msg.sysId + " CompId=" + msg.componentId + " seq=" + msg.sequence + " " + msg.toString());
                    drone.pitch = ((msg_ahrs2)msg).pitch;
                    drone.yaw = ((msg_ahrs2)msg).yaw;
                    drone.roll = -((msg_ahrs2)msg).roll;
          //          System.out.println("pitch=" + pitch + " - roll="+roll + " - yaw=" +yaw);
                /*} else if (msg != null && msg.messageType == msg_global_position_int.MAVLINK_MSG_ID_GLOBAL_POSITION_INT) {
                    nb++;
                    drone.altitude = ((msg_global_position_int)msg).alt - drone.initialAltitude;
          //      	System.out.println("altitude=" + altitude); */
                } else if (msg != null && msg.messageType == msg_heartbeat.MAVLINK_MSG_ID_HEARTBEAT) {
          //      	System.out.println("got heartbeat message!!!!!");
                	nb++;
                	drone.currentMode = ((msg_heartbeat)msg).base_mode;
                	drone.currentCustomMode = ((msg_heartbeat)msg).custom_mode;
                } else if (msg != null && msg.messageType == msg_rc_channels_raw.MAVLINK_MSG_ID_RC_CHANNELS_RAW) {
                	if (((msg_rc_channels_raw)msg).chan1_raw != ((msg_rc_channels_raw)msg).chan2_raw && ((msg_rc_channels_raw)msg).chan1_raw != ((msg_rc_channels_raw)msg).chan3_raw
                			 && ((msg_rc_channels_raw)msg).chan1_raw != ((msg_rc_channels_raw)msg).chan4_raw  && ((msg_rc_channels_raw)msg).chan2_raw != ((msg_rc_channels_raw)msg).chan3_raw) {
                		nb++;
                		channel1Mid = ((msg_rc_channels_raw)msg).chan1_raw;
                		channel2Mid = ((msg_rc_channels_raw)msg).chan2_raw;
                		channel3Mid = ((msg_rc_channels_raw)msg).chan3_raw;
                		channel4Mid = ((msg_rc_channels_raw)msg).chan4_raw;
                		sender.send(-3);
                		System.out.println("got rc raw message!!!!! " + channel1Mid + " " + channel2Mid + " " + channel3Mid + " " + channel4Mid);
                	}
                }
            }
        } catch (IOException e) {
        	e.printStackTrace();
        }

        System.out.println("TOTAL BYTES = " + reader.getTotalBytesReceived());
        System.out.println("NBMSG (" + nb + ") : " + reader.getNbMessagesReceived() + " NBCRC=" + reader.getBadCRC() + " NBSEQ="
                          + reader.getBadSequence() + " NBLOST=" + reader.getLostBytes());
	}

	public void testArm(Sender sender, boolean arm) {
		sender.heartbeat();
    	if(sender.arm(arm)) {
    		System.out.println("Successfully set ARMED to: " + arm);
    	}
    }
    
    public void testSendToSerial(Sender sender, int streamId) {
    	if(sender.send(streamId)) {
    		System.out.println("sent successfully");
    	}    	
    }
    
    public void testFromSerial(SerialPortCommunicator spc) {
        MAVLinkReader reader;
        int nb = 0;
    	Reader rdr = new Reader(spc);
    	PipedInputStream in = rdr.read();
        DataInputStream dis = new DataInputStream(in);
        reader = new MAVLinkReader(dis);
        try {
            while (true /*dis.available() > 0*/) {
                MAVLinkMessage msg = reader.getNextMessage();
                if (msg != null) {
                    nb++;
                    System.out.println("SysId=" + msg.sysId + " CompId=" + msg.componentId + " seq=" + msg.sequence + " " + msg.toString());
                }
            }
        } catch (IOException e) {
        	e.printStackTrace();
        }

        System.out.println("TOTAL BYTES = " + reader.getTotalBytesReceived());
        System.out.println("NBMSG (" + nb + ") : " + reader.getNbMessagesReceived() + " NBCRC=" + reader.getBadCRC() + " NBSEQ="
                          + reader.getBadSequence() + " NBLOST=" + reader.getLostBytes());
    }

    
}