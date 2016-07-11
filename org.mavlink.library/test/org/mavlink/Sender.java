package org.mavlink;

import java.io.IOException;

import org.mavlink.messages.MAV_AUTOPILOT;
import org.mavlink.messages.MAV_CMD;
import org.mavlink.messages.MAV_COMPONENT;
import org.mavlink.messages.MAV_FRAME;
import org.mavlink.messages.MAV_MODE;
import org.mavlink.messages.MAV_MODE_FLAG;
import org.mavlink.messages.MAV_STATE;
import org.mavlink.messages.ja4rtor.msg_command_int;
import org.mavlink.messages.ja4rtor.msg_command_long;
import org.mavlink.messages.ja4rtor.msg_heartbeat;
import org.mavlink.messages.ja4rtor.msg_landing_target;
import org.mavlink.messages.ja4rtor.msg_manual_control;
import org.mavlink.messages.ja4rtor.msg_rc_channels_override;
import org.mavlink.messages.ja4rtor.msg_request_data_stream;
import org.mavlink.messages.ja4rtor.msg_set_mode;
import org.mavlink.messages.ja4rtor.msg_set_position_target_local_ned;

public class Sender {
	private SerialPortCommunicator spc;
	private int sequence = 0;
	private long startTime;
	
	public Sender(SerialPortCommunicator spc){
		this.spc = spc;
		startTime = System.currentTimeMillis();
	}

	public boolean send(int streamId) {
		msg_request_data_stream ds = new msg_request_data_stream(255, 1);
		ds.sequence = sequence++;
		ds.req_message_rate = 10;
		ds.target_system = 1;
		ds.target_component = 1;
		ds.req_stream_id = Math.abs(streamId); //try 2, 6, 10, 11, 12, 01, 03 (10 is orientation AHRS)
		if (streamId > 0) {
			ds.start_stop = 1;
		} else {
			ds.start_stop = 0;
		}
        byte[] result;
		try {
			result = ds.encode();
	        spc.writeData(result);
	        return true;
		} catch (IOException e) {
			e.printStackTrace();
		}
		return false;
	}
	
	public boolean command(int value) {
//	    msg_rc_channels_override msg = new msg_rc_channels_override(255, 1);
//		msg.target_system = 1;
//		msg.target_component = (byte) MAV_COMPONENT.MAV_COMP_ID_ALL;
//		msg.chan1_raw = value;
//		msg.chan2_raw = value;
//		msg.chan3_raw = value;
//		msg.chan4_raw = value;
//		msg.chan5_raw = 65535; //UINT_16 max
//		msg.chan6_raw = 65535;
//		msg.chan7_raw = 65535;
//		msg.chan8_raw = 65535;
		msg_set_position_target_local_ned msg = new msg_set_position_target_local_ned(255, 1);
		msg.time_boot_ms = System.currentTimeMillis() - startTime;
		msg.target_system = 1;
		msg.target_component = (byte) MAV_COMPONENT.MAV_COMP_ID_ALL;
		msg.coordinate_frame = MAV_FRAME.MAV_FRAME_LOCAL_OFFSET_NED;
		msg.type_mask = 0b0000000000000000; //ignore everything except velocity
		msg.x = 10;
		msg.y = 10;
		msg.z = -10;
		msg.vx = 1;
		msg.vy = 1;
		msg.vz = -1;
		msg.afx = 1;
		msg.afy = 1;
		msg.afz = 1;
		byte[] result;
		try {
			result = msg.encode();
	        spc.writeData(result);
	        return true;
		} catch (IOException e) {
			e.printStackTrace();
		}
		return false;
	}
	
	public void land(float degrees) {
		System.out.println("Setting landing target to: x=" + degrees + ", y=0 degrees");
		msg_landing_target msg = new msg_landing_target(255,1);
		msg.time_usec = (System.currentTimeMillis() - startTime)*1000;
		msg.target_num = 1;
		msg.frame = MAV_FRAME.MAV_FRAME_GLOBAL;
		msg.angle_x = (float) Math.toRadians(degrees);
		msg.angle_y = 0;
		msg.distance = 0;
		msg.size_x = 0;
		msg.size_y = 0;
		
        byte[] result;
		try {
			result = msg.encode();
	        spc.writeData(result);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	public boolean arm(boolean arm) {
		msg_command_long msg = new msg_command_long(255,1);
		msg.target_system = 1;
		msg.target_component = (byte) MAV_COMPONENT.MAV_COMP_ID_SYSTEM_CONTROL;

		msg.command = MAV_CMD.MAV_CMD_COMPONENT_ARM_DISARM;
		msg.param1 = arm?1:0;
		msg.param2 = 0;
		msg.param3 = 0;
		msg.param4 = 0;
		msg.param5 = 0;
		msg.param6 = 0;
		msg.param7 = 0;
		msg.confirmation = 0;
		
        byte[] result;
		try {
			result = msg.encode();
	        spc.writeData(result);
	        return true;
		} catch (IOException e) {
			e.printStackTrace();
		}
		return false;
	}

	public boolean heartbeat() {
		msg_heartbeat hb = new msg_heartbeat(255, 1);
	    hb.sequence = sequence++;
	    hb.autopilot = MAV_AUTOPILOT.MAV_AUTOPILOT_PX4;
	    hb.base_mode = MAV_MODE_FLAG.MAV_MODE_FLAG_STABILIZE_ENABLED;
	    hb.custom_mode = 0; //custom mode
	    hb.mavlink_version = 3;
	    hb.system_status = MAV_STATE.MAV_STATE_ACTIVE;
	    byte[] result;
		try {
			result = hb.encode();
	        spc.writeData(result);
	        return true;
		} catch (IOException e) {
			e.printStackTrace();
		}
		return false;
    }

	public void mode(String mode, boolean armed) {
		msg_set_mode msg = new msg_set_mode(255,1);
		msg.target_system = 1;

		if (mode.equals("stabilize")) {
			System.out.println("Setting to stabilize mode");
			msg.base_mode = MAV_MODE.MAV_MODE_STABILIZE_DISARMED + 1; //stabilize mode is 81 for APM
			msg.custom_mode = 0; //custom mode 0 is default
		} else if (mode.equals("land")){
			System.out.println("Setting to land mode");
			msg.base_mode = MAV_MODE.MAV_MODE_STABILIZE_DISARMED + 1;
			msg.custom_mode = 9; //custom mode 9 = land
		} else if (mode.equals("guided")){
			System.out.println("Setting to guided mode");
			msg.base_mode = MAV_MODE.MAV_MODE_GUIDED_DISARMED + 1;
			msg.custom_mode = 4; //custom mode 4 = guided for APM copter
		}  else if (mode.equals("loiter")){
			System.out.println("Setting to loiter mode");
			msg.base_mode = MAV_MODE.MAV_MODE_STABILIZE_DISARMED + 1;
			msg.custom_mode = 5; //custom mode 5 = LOITER for APM copter
		} else {
			System.out.println("Setting to stabilize mode");
			msg.base_mode = MAV_MODE.MAV_MODE_STABILIZE_DISARMED + 1; //default
			msg.custom_mode = 0; 
		}
		if (armed) {
			msg.base_mode += 128;
			System.out.println("Setting base mode to: " + msg.base_mode);
		}

        byte[] result;
		try {
			result = msg.encode();
	        spc.writeData(result);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public void test(int throttle, int speed) {
		msg_command_long msg = new msg_command_long(255,1);
		msg.target_system = 1;
		msg.target_component = (byte) MAV_COMPONENT.MAV_COMP_ID_SYSTEM_CONTROL;

		msg.command = MAV_CMD.MAV_CMD_DO_CHANGE_SPEED;
		msg.param1 = 0;
		msg.param2 = speed;
		msg.param3 = throttle;
		msg.param4 = 0;
		msg.param5 = 0;
		msg.param6 = 0;
		msg.param7 = 0;
		msg.confirmation = 0;
		
        byte[] result;
		try {
			result = msg.encode();
	        spc.writeData(result);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
}
