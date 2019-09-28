package ohbroker;

import java.lang.reflect.Array;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.text.AbstractWriter;
import javax.xml.bind.DatatypeConverter;

import org.apache.commons.collections4.queue.CircularFifoQueue;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.MqttTopic;

import jssc.SerialPort;
import jssc.SerialPortEvent;
import jssc.SerialPortEventListener;
import jssc.SerialPortException;

public class OHBroker {
	
	private String port;
	private String mqttServer;
	private String mqttPort;
	private String mqttUser;
	private String mqttPassword;
	
	private SerialPort serialPort;
	private String serialBuffer = new String();
	
	private MqttClient mqttClient;
	private MqttConnectOptions mqttConnectOptions;
	
	private Timer pollTimer;
	private TimerTask pollTask;
	
	private boolean doPolling;
	
	private static final int NUMBER_OF_ITEMS = 14;
	private static final int MEDIAN_SIZE = 5;
	private Map<String, CircularFifoQueue<Float>> sensorValues;
	
	private static final String COMMAND_TOPIC = "ehome/heating/commands";
	private static final String STATUS_TOPIC = "ehome/heating/statuses";
	private static final String RAW_TOPIC = "ehome/heating/raw";
	private static final String DEBUG_TOPIC = "ehome/heating/debug";
	
	private static final String TOTAL_CONSUMPTION_ITEM = "total_consumption";
	private static final String TEMP_ITEM = "_temp";
	private static final String IS_AUTO_ITEM = "_isauto";
	private static final String IS_ON_ITEM = "_ison";
	private static final String PRIORITY_ITEM = "_prio";
	private static final String TARGET_TEMPERATURE_ITEM = "_targettemp";
	private static final String CONSUMPTION_LIMIT_ITEM = "consumption_limit";
	private static final String HYSTERESIS_ITEM = "hysteresis";
	private static final String SENSOR_ITEM = "_sensor";
	private static final String PORT_ITEM = "_port";
	private static final String TEMPERATURE_ADJUST_ITEM = "_tempadjust";
	private static final String CONSUMPTION_ITEM = "_consumption";
	private static final String IS_ENABLED_ITEM = "_isenabled";
	private static final String EEPROM_ERROR_ITEM = "eeprom_error";
	private static final String TEMPERATURE_READ_ERROR_ITEM = "temp_read_error";
	
	public OHBroker(String port, String mqttServer, String mqttPort, String mqttUser, String mqttPassword, String doPolling) {
		sensorValues = new HashMap<String, CircularFifoQueue<Float>>();
		for (int i=0; i<NUMBER_OF_ITEMS; i++) {
			String item = String.format("%06X", i);
			sensorValues.put(item, new CircularFifoQueue<Float>(MEDIAN_SIZE));
		}
		this.port = port;
		this.mqttServer = mqttServer;
		this.mqttPort = mqttPort;
		this.mqttUser = mqttUser;
		this.mqttPassword = mqttPassword;
		this.doPolling = doPolling.equals("true") ? true : false;
		pollTimer = new Timer();
		pollTask = new PollTask();
	}

	public static void main(String[] args) {
		Map<String, String> arguments = new HashMap<String, String>();
		for (String s : args) {
			String[] arg = s.split(":");
			if(arg.length == 2) {
				arguments.put(arg[0], arg[1]);
			} else {
				displayHelp();
				System.exit(-1);
			}
		}
		
		if (arguments.entrySet().size() != 6) {
			displayHelp();
			System.exit(-1);
		}
		
		if (!arguments.containsKey("serialPort")
				|| !arguments.containsKey("mqttServer")
				|| !arguments.containsKey("mqttPort")
				|| !arguments.containsKey("mqttUser")
				|| !arguments.containsKey("mqttPass")
				|| !arguments.containsKey("doPolling")) {
			displayHelp();
			System.exit(-1);
		}
		
		OHBroker ohBroker = new OHBroker(arguments.get("serialPort"),
				arguments.get("mqttServer"),
				arguments.get("mqttPort"),
				arguments.get("mqttUser"),
				arguments.get("mqttPass"),
				arguments.get("doPolling"));
		ohBroker.start();

	}
	
	private static void displayHelp() {
		System.out.println("Incorrect arguments.");
		System.out.println("Usage: ohbroker serialPort:<serialPort>, "
				+ "mqttServer:<mqttServer>, "
				+ "mqttPort:<mqttPort>, "
				+ "mqttUser:<mqttUser>, "
				+ "mqttPass:<mqttPass>, "
				+ "doPolling:<true|false>");
	}
	
	private void start() {
		openSerialPort();
		connectToMqttServer();
		subscribeToTopics();
		getItemStates();
		if (doPolling) {
			startPolling();
			System.out.println("Polling started");
		}
	}

	private void openSerialPort() {
		serialPort = new SerialPort(port);
		try {
			serialPort.openPort();
			serialPort.setParams(SerialPort.BAUDRATE_115200,
					SerialPort.DATABITS_8,
					SerialPort.STOPBITS_1,
					SerialPort.PARITY_NONE);
			serialPort.addEventListener(new MySerialPortEventListener(), SerialPort.MASK_RXCHAR);
		} catch (SerialPortException e) {
			e.printStackTrace();
			System.exit(-1);
		}
		System.out.println("Opened serial port");
	}
	
	private void connectToMqttServer() {
		mqttConnectOptions = new MqttConnectOptions();
		mqttConnectOptions.setCleanSession(true);
		mqttConnectOptions.setKeepAliveInterval(30);
		mqttConnectOptions.setUserName(mqttUser);
		mqttConnectOptions.setPassword(mqttPassword.toCharArray());
		
		try {
			mqttClient = new MqttClient("tcp://" + mqttServer + ":" + mqttPort, "OHBroker");
			mqttClient.setCallback(new MyMqttCallback());
			mqttClient.connect(mqttConnectOptions);
		} catch (MqttException e) {
				e.printStackTrace();
				System.out.println("Unable to connect to server");
				System.exit(-1);
		}
		System.out.println("Connected to mqtt server " + mqttServer + ":" + mqttPort);
	}
	
	private void subscribeToTopics() {
		for (int i=0; i<NUMBER_OF_ITEMS; i++) {
			String item = String.format("%06X", i);
			try {
				mqttClient.subscribe(COMMAND_TOPIC + "/item_" + item + IS_ENABLED_ITEM, 0);
				mqttClient.subscribe(COMMAND_TOPIC + "/item_" + item + IS_AUTO_ITEM, 0);
				mqttClient.subscribe(COMMAND_TOPIC + "/item_" + item + IS_ON_ITEM, 0);
				mqttClient.subscribe(COMMAND_TOPIC + "/item_" + item + PRIORITY_ITEM, 0);
				mqttClient.subscribe(COMMAND_TOPIC + "/item_" + item + TARGET_TEMPERATURE_ITEM, 0);
				mqttClient.subscribe(COMMAND_TOPIC + "/item_" + item + SENSOR_ITEM, 0);
				mqttClient.subscribe(COMMAND_TOPIC + "/item_" + item + PORT_ITEM, 0);
				mqttClient.subscribe(COMMAND_TOPIC + "/item_" + item + TEMPERATURE_ADJUST_ITEM, 0);
				mqttClient.subscribe(COMMAND_TOPIC + "/item_" + item + CONSUMPTION_ITEM, 0);
				mqttClient.subscribe(COMMAND_TOPIC + "/" + CONSUMPTION_LIMIT_ITEM, 0);
				mqttClient.subscribe(COMMAND_TOPIC + "/" + HYSTERESIS_ITEM, 0);
			} catch (MqttException e) {
				e.printStackTrace();
				System.out.println("Unable to subscribe to topics");
				System.exit(-1);
			}
		}
	}
	
	private void getItemStates() {
		for (int i=0; i<NUMBER_OF_ITEMS; i++) {
			sendCommand(makeCommand(Interface.GETSTATE, String.format("%06X", i)));
			try {
				Thread.sleep(100);
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		sendCommand(makeCommand(Interface.GETCONSUMPTIONLIMIT, "000000"));
		sendCommand(makeCommand(Interface.GETHYSTERESIS, "000000"));
	}
	
	private void startPolling() {
		pollTimer.schedule(pollTask,  0, 2000);
	}
	
	private void publishToTopic(String topic, String message, int qos, boolean isRetained) {
		//System.out.printf("Publishing %s to topic %s", message, topic);
		MqttTopic mqttTopic;
		if (mqttClient != null) {
			mqttTopic = mqttClient.getTopic(topic);
		} else {
			return;
		}
		
		MqttMessage mqttMessage = new MqttMessage(message.getBytes());
		mqttMessage.setQos(qos);
		mqttMessage.setRetained(isRetained);
		
		MqttDeliveryToken mqttToken;
		
		try {
			mqttToken = mqttTopic.publish(mqttMessage);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
	
	private void parseResponse(String received) {
		String resp = received.replaceAll("\\s", "");
		publishToTopic(RAW_TOPIC, "<-- " + resp, 0, false);
		resp = resp.substring(2, resp.length()-2);
		if (!received.startsWith(Interface.BEGINTRANSMISSION) || !received.endsWith(Interface.ENDTRANSMISSION)) {
			System.out.println("Invalid command");
			return;
		}
		
		if (resp.substring(0, 2).equals(Interface.DEBUGINFO)) {
			publishToTopic(DEBUG_TOPIC, resp, 0, false);
			return;
		}
		
		String crc = resp.substring(resp.length()-2, resp.length());
		byte[] bytes = DatatypeConverter.parseHexBinary(resp.substring(0, resp.length()-2));
		if(!calculateCRC(bytes).equals(crc)) {
			System.out.printf("Bad CRC in response: %s\n", received);
			return;
		}
		String addr;
		Byte b;
		try {
			addr = resp.substring(2, 8);
			b = Byte.parseByte(addr, 16);
		} catch (Exception e) {
			System.out.printf("Error parsing item address in response: %s\n", received);
			System.out.println(e.getMessage());
			return;
		}
		
		HeaterItem heater = new HeaterItem(b);
		int consumptionLimit, consumption;
		float hysteresis, temp;
		CircularFifoQueue<Float> data;

		int sign,integer,decimal;
		switch (resp.substring(0,2)) {
		case Interface.TEMPREADERROR:
			publishToTopic(STATUS_TOPIC + "/" + EEPROM_ERROR_ITEM, "ON", 2, false);
			break;
		case Interface.EEPROMERROR:
			publishToTopic(STATUS_TOPIC + "/" + TEMPERATURE_READ_ERROR_ITEM, "ON", 2, false);
			break;
		case Interface.REPORTSETENABLED:
			heater.isEnabled = (bytes[4]!=0 ? true : false);
			publishToTopic(STATUS_TOPIC + "/item_" + addr + IS_ENABLED_ITEM, heater.isEnabled ? "ON" : "OFF", 0, true);
			break;
		case Interface.REPORTSETTARGETTEMP:
			heater.targetTemperature = bytes[4]&0xFF;
			publishToTopic(STATUS_TOPIC + "/item_" + addr + TARGET_TEMPERATURE_ITEM, String.valueOf(heater.targetTemperature), 0, true);
			break;
		case Interface.REPORTSETPRIORITY:
			heater.priority = bytes[4]&0xFF;
			publishToTopic(STATUS_TOPIC + "/item_" + addr + PRIORITY_ITEM, String.valueOf(heater.priority), 0, true);
			break;
		case Interface.REPORTSETAUTO:
			heater.isAuto = (bytes[4]!=0 ? true : false);
			publishToTopic(STATUS_TOPIC + "/item_" + addr + IS_AUTO_ITEM, heater.isAuto ? "ON" : "OFF", 0, true);
			break;
		case Interface.REPORTSETONOFF:
			heater.isOn = (bytes[4]!=0 ? true : false);
			publishToTopic(STATUS_TOPIC + "/item_" + addr + IS_ON_ITEM, heater.isOn ? "ON" : "OFF", 0, true);
			break;
		case Interface.REPORTSETSENSOR:
			heater.address = Arrays.copyOfRange(bytes, 4, 12);
			publishToTopic(STATUS_TOPIC + "/item_" + addr + SENSOR_ITEM, DatatypeConverter.printHexBinary(heater.address), 0, true);
			break;
		case Interface.REPORTSETPORT:
			heater.port = bytes[4];
			publishToTopic(STATUS_TOPIC + "/item_" + addr + PORT_ITEM, String.valueOf(heater.port), 0, true);
			break;
		case Interface.REPORTSETADJUST:
			sign = (bytes[4] == 0 ? 1 : -1);
			integer = bytes[5]&0xFF;
			decimal = bytes[6]&0xFF;
			heater.temperatureAdjust = sign * (integer*100 + decimal)/(float)100;
			publishToTopic(STATUS_TOPIC + "/item_" + addr + TEMPERATURE_ADJUST_ITEM, String.valueOf(heater.temperatureAdjust), 0, true);
			break;
		case Interface.REPORTSETCONSUMPTION:
			heater.powerConsumption = (short) ((bytes[4] & 0xFF) <<8 | bytes[5] & 0xFF);
			publishToTopic(STATUS_TOPIC + "/item_" + addr + CONSUMPTION_ITEM, String.valueOf(heater.powerConsumption), 0, true);
			break;
		case Interface.REPORTCONSUMPTIONLIMIT:
			consumptionLimit = (bytes[4] & 0xFF) << 8 | bytes[5] & 0xFF;
			publishToTopic(STATUS_TOPIC + "/" + CONSUMPTION_LIMIT_ITEM, String.valueOf(consumptionLimit), 0, true);
			break;
		case Interface.REPORTHYSTERESIS:
			integer = bytes[4]&0xFF;
			decimal = bytes[5]&0xFF;
			hysteresis = (integer*100 + decimal)/(float)100;
			publishToTopic(STATUS_TOPIC + "/" + HYSTERESIS_ITEM, String.valueOf(hysteresis), 0, true);
			break;
		case Interface.REPORTTOTALCONSUMPTION:
			consumption = (bytes[4] & 0xFF) << 8 | bytes[5] & 0xFF;
			publishToTopic(STATUS_TOPIC + "/" + TOTAL_CONSUMPTION_ITEM, String.valueOf(consumption), 0, true);
			break;
		case Interface.REPORTTEMP:
			sign = (bytes[4] == 0 ? 1 : -1);
			integer = bytes[5]&0xFF;
			decimal = bytes[6]&0xFF;
			temp = sign * (integer*100 + decimal)/(float)100;
			data = sensorValues.get(addr);
			if (data.size() < MEDIAN_SIZE) {
				heater.temperature = temp;
				publishToTopic(STATUS_TOPIC + "/item_" + addr + TEMP_ITEM, String.valueOf(heater.temperature), 0, true);
			} else {
				data.add(temp);
				heater.temperature = calculateMedian(data);
				publishToTopic(STATUS_TOPIC + "/item_" + addr + TEMP_ITEM, String.valueOf(heater.temperature), 0, true);
			}
			break;
		case Interface.REPORTACTUALSTATE:
			heater.actualState = (bytes[4]!=0 ? true : false);
			publishToTopic(STATUS_TOPIC + "/item_" + addr + IS_ON_ITEM, heater.actualState ? "ON" : "OFF", 0, true);
			break;
		case Interface.REPORTSTATE:
			heater.port = bytes[4];
			heater.address = Arrays.copyOfRange(bytes, 5, 13);
			heater.isAuto = (bytes[13]!=0 ? true : false);
			heater.isOn = (bytes[14]!=0 ? true : false);
			heater.actualState = (bytes[15]!=0 ? true : false);
			heater.priority = bytes[16]&0xFF;
			heater.targetTemperature = bytes[17]&0xFF;
			sign = (bytes[18] == 0 ? 1 : -1);
			integer = bytes[19]&0xFF;
			decimal = bytes[20]&0xFF;
			temp = sign * (integer*100 + decimal)/(float)100;
			data = sensorValues.get(addr);
			if (data.size() < MEDIAN_SIZE) {
				heater.temperature = temp;
				publishToTopic(STATUS_TOPIC + "/item_" + addr + TEMP_ITEM, String.valueOf(heater.temperature), 0, true);
			} else {
				data.add(temp);
				heater.temperature = calculateMedian(data);
				publishToTopic(STATUS_TOPIC + "/item_" + addr + TEMP_ITEM, String.valueOf(heater.temperature), 0, true);
			}			
			sign = (bytes[21] == 0 ? 1 : -1);
			integer = bytes[22]&0xFF;
			decimal = bytes[23]&0xFF;
			heater.temperatureAdjust = sign * (integer*100 + decimal)/(float)100;
			heater.isEnabled = (bytes[24]!=0 ? true : false);
			heater.powerConsumption = (short) ((bytes[25] & 0xFF) << 8 | bytes[26] & 0xFF);
			publishToTopic(STATUS_TOPIC + "/item_" + addr + IS_ENABLED_ITEM, heater.isEnabled ? "ON" : "OFF", 0, true);
			if (heater.isEnabled) {
				publishToTopic(STATUS_TOPIC + "/item_" + addr + PORT_ITEM, String.valueOf(heater.port), 0, true);
				publishToTopic(STATUS_TOPIC + "/item_" + addr + SENSOR_ITEM, DatatypeConverter.printHexBinary(heater.address), 0, true);
				publishToTopic(STATUS_TOPIC + "/item_" + addr + TEMP_ITEM, String.valueOf(heater.temperature), 0, true);
				publishToTopic(STATUS_TOPIC + "/item_" + addr + TARGET_TEMPERATURE_ITEM, String.valueOf(heater.targetTemperature), 0, true);
				publishToTopic(STATUS_TOPIC + "/item_" + addr + IS_ON_ITEM, heater.actualState ? "ON" : "OFF", 0, true);
				publishToTopic(STATUS_TOPIC + "/item_" + addr + IS_AUTO_ITEM, heater.isAuto ? "ON" : "OFF", 0, true);
				publishToTopic(STATUS_TOPIC + "/item_" + addr + PRIORITY_ITEM, String.valueOf(heater.priority), 0, true);
				publishToTopic(STATUS_TOPIC + "/item_" + addr + TEMPERATURE_ADJUST_ITEM, String.valueOf(heater.temperatureAdjust), 0, true);
				publishToTopic(STATUS_TOPIC + "/item_" + addr + CONSUMPTION_ITEM, String.valueOf(heater.powerConsumption), 0, true);
			}
			break;
		default:
			System.out.println("Unknown response: " + received);
			break;
		}
	}
	
	String makeCommand(String command, String addr) {
		return makeCommand(command, addr, "");
	}
	
	String makeCommand(String command, String addr, String param) {
		StringBuilder sb = new StringBuilder();
		sb.append(command).append(addr).append(param);
		String crc = calculateCRC(DatatypeConverter.parseHexBinary(sb.toString()));
		sb.append(crc).append(Interface.ENDTRANSMISSION).insert(0,Interface.BEGINTRANSMISSION);
		return sb.toString();
	}
	
	void sendCommand(String command) {
		if (serialPort != null && serialPort.isOpened()) {
			try {
				//System.out.printf("Command: %s\n", command);
				byte[] bytes = DatatypeConverter.parseHexBinary(command);
				serialPort.writeBytes(bytes);
				publishToTopic(RAW_TOPIC, "--> " + command, 0, false);
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}
	
	String calculateCRC(String str) {
		return calculateCRC(DatatypeConverter.parseHexBinary(str));
	}
	
	String calculateCRC(byte[] bytes) {
		int sum = 0;
		for(byte b : bytes) {
			sum += 0xFF & b;
		}
		sum &= 0xF7;
		byte[] arr = new byte[1];
		arr[0] = (byte)sum;
		return DatatypeConverter.printHexBinary(arr);
	}
	
	float calculateMedian(CircularFifoQueue<Float> data) {
		Float[] dataSet = data.toArray(new Float[0]);
		Arrays.sort(dataSet);
		float median;
		if (dataSet.length % 2 == 0) {
			median = (dataSet[dataSet.length/2] + dataSet[dataSet.length/2 - 1])/2;
		} else {
			median = dataSet[dataSet.length/2];
		}
		return median;
	}
	
	float calculateAverage(CircularFifoQueue<Float> data) {
		float sum = 0;
		for (int i=0; i<data.size(); i++) {
			sum += data.get(i);
		}
		return sum / (float)data.size();
	}
	
	class ShutdownHook extends Thread {
		public void run() {
			try {
				if (serialPort.isOpened()) {
					serialPort.closePort();
				}
				if (mqttClient.isConnected()) {
					mqttClient.disconnect();
					mqttClient.close();
				}
			} catch (Exception e) {
					System.out.println(e.getMessage());
			}
		}
	}
	
	class MySerialPortEventListener implements SerialPortEventListener {
		Pattern p = Pattern.compile("("+Interface.BEGINTRANSMISSION+".*?"+Interface.ENDTRANSMISSION+")");

		@Override
		public void serialEvent(SerialPortEvent event) {
			try {
				serialBuffer = serialBuffer.concat(serialPort.readHexString());
				//System.out.printf("Response: %s\n", serialBuffer);
				Matcher m = p.matcher(serialBuffer);
				int tailPos = 0;
				while (m.find()) {
					if (!m.group(1).isEmpty()) { //<startTag>sometext<endTag> messages found. Process them.
						//System.out.printf("   chunk: %s\n", m.group(1));
						parseResponse(m.group(1));
						tailPos = m.end();
					}
				}
				if (tailPos < serialBuffer.length()) { //If some unprocessed text still left
					String tail = serialBuffer.substring(tailPos, serialBuffer.length());
					if (tail.contains(Interface.BEGINTRANSMISSION)) { //If this text contains <startTag>
						serialBuffer = tail; //keep it and wait for <endTag>
					} else {
						serialBuffer = ""; //discard it
					}
				} else {
					serialBuffer = ""; //clear incoming string
				}
			} catch (SerialPortException e) {
				System.out.println(e);
			}				
		}
	}

	class MyMqttCallback implements MqttCallback{

		@Override
		public void connectionLost(Throwable arg0) {
			System.out.println("Lost connection to server");
			System.exit(-1);
		}

		@Override
		public void deliveryComplete(IMqttDeliveryToken arg0) {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void messageArrived(String topic, MqttMessage message) throws Exception {
			String msg = new String(message.getPayload());
			String addr = new String(), item = new String();
			Pattern p;
			Matcher m;
			if (topic.contains("item_")) {
				p = Pattern.compile("/item_([0-9a-fA-F]{6})(_.*)");
				m = p.matcher(topic);
				if (m.find()) {
					if (!m.group(1).isEmpty()) {
						addr = m.group(1);
					}
					if (!m.group(2).isEmpty()) {
						item = m.group(2);
					}
				}
			} else {
				p = Pattern.compile("/((?:" + CONSUMPTION_LIMIT_ITEM + ")|(?:" + HYSTERESIS_ITEM + "))");
				m = p.matcher(topic);
				if (m.find()) {
					if (!m.group(1).isEmpty()) {
						item = m.group(1);
					}
				}
			}
				
			if (item.isEmpty()) {
				return;
			}
			
			System.out.println(item + addr + ": " + msg);
			byte[] data;
			switch (item) {
			case IS_ENABLED_ITEM:
				sendCommand(makeCommand(Interface.SETENABLED, addr, msg.equals("ON") ? "01" : "00"));
				break;
			case IS_AUTO_ITEM:
				sendCommand(makeCommand(Interface.SETAUTO, addr, msg.equals("ON") ? "01" : "00"));
				break;
			case IS_ON_ITEM:
				sendCommand(makeCommand(Interface.SETONOFF, addr, msg.equals("ON") ? "01" : "00"));
				break;
			case PRIORITY_ITEM:
				data = new byte[1];
				try {
					data[0] = Byte.parseByte(msg.replaceAll("\\.\\d+", ""));
				} catch (Exception e) {
					System.out.println("Error parsing priority item: " + e.getMessage());
					return;
				}
				sendCommand(makeCommand(Interface.SETPRIORITY, addr, DatatypeConverter.printHexBinary(data)));
				break;
			case TARGET_TEMPERATURE_ITEM:
				data = new byte[1];
				try {
					data[0] = Byte.parseByte(msg.replaceAll("\\.\\d+", ""));
				} catch (Exception e) {
					System.out.println("Error parsing target temperature item: " + e.getMessage());
					return;
				}
				sendCommand(makeCommand(Interface.SETTARGETTEMP, addr, DatatypeConverter.printHexBinary(data)));
				break;
			case SENSOR_ITEM:
				sendCommand(makeCommand(Interface.SETSENSOR, addr, msg));
				break;
			case PORT_ITEM:
				data = new byte[1];
				try {
					data[0] = Byte.parseByte(msg.replaceAll("\\.\\d+", ""));
				} catch (Exception e) {
					System.out.println("Error parsing hysteresis item: " + e.getMessage());
					return;
				}
				sendCommand(makeCommand(Interface.SETPORT, addr, DatatypeConverter.printHexBinary(data)));
				break;
			case TEMPERATURE_ADJUST_ITEM:
				data = new byte[3];
				float adjust = 0;
				try {
					adjust = Float.parseFloat(msg);
				} catch (Exception e) {
					System.out.println("Error parsing temperature adjust item: " + e.getMessage());
					return;
				}
				data[0] = (byte) (adjust < 0 ? 1 : 0);
				data[1] = (byte) (Math.abs(adjust));
				data[2] = (byte) (Math.abs(adjust)*100 - data[1]*100);
				sendCommand(makeCommand(Interface.SETADJUST, addr, DatatypeConverter.printHexBinary(data)));
				break;
			case CONSUMPTION_ITEM:
				short consumption = Short.parseShort(msg.replaceAll("\\.\\d+", ""));
				data = ByteBuffer.allocate(2).putShort(consumption).array();
				sendCommand(makeCommand(Interface.SETCONSUMPTION, addr, DatatypeConverter.printHexBinary(data)));
				break;
			case CONSUMPTION_LIMIT_ITEM:
				short consumption_limit = 0;
				try {
					consumption_limit = Short.parseShort(msg.replaceAll("\\.\\d+", ""));
				} catch (Exception e) {
					System.out.println("Error parsing consumption limit item: " + e.getMessage());
					return;
				}
				data = ByteBuffer.allocate(2).putShort(consumption_limit).array();
				sendCommand(makeCommand(Interface.SETCONSUMPTIONLIMIT, "000000", DatatypeConverter.printHexBinary(data)));
				break;
			case HYSTERESIS_ITEM:
				data = new byte[2];
				float hysteresis = 0;
				try {
					hysteresis = Float.parseFloat(msg);
				} catch (Exception e) {
					System.out.println("Error parsing hysteresis item: " + e.getMessage());
					return;
				}
				data[0] = (byte)(Math.abs(hysteresis));
				data[1] = (byte)(Math.abs(hysteresis)*100 - data[0]*100);
				sendCommand(makeCommand(Interface.SETHYSTERESIS, "000000", DatatypeConverter.printHexBinary(data)));
				break;
			}
		}
	}
	
	class PollTask extends TimerTask {

		@Override
		public void run() {
			for(int i=0; i<NUMBER_OF_ITEMS; i++) {
				//sendCommand(makeCommand(Interface.GETSTATE, String.format("%06X", i)));
				try {
					sendCommand(makeCommand(Interface.GETTEMP, String.format("%06X", i)));
					Thread.sleep(100);
					sendCommand(makeCommand(Interface.GETACTUALSTATE, String.format("%06X", i)));
					Thread.sleep(100);
				} catch (Exception e) {
					
				}
			}
			sendCommand(makeCommand(Interface.GETTOTALCONSUMPTION, "000000"));
		}
		
	}
}
