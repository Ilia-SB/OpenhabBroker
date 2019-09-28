package ohbroker;

import javax.swing.JLabel;
import javax.swing.JRadioButton;
import javax.swing.JTextField;

public class HeaterItem {
	public boolean isEnabled;
	public byte[] heaterAddress;
	public byte[] address;
	public byte port;
	public boolean isAuto;
	public boolean isOn;
	public boolean actualState;
	public short powerConsumption;
	public boolean wantsOn;
	public int priority;
	public float temperatureAdjust;
	public boolean isConnected;
	public float temperature;
	public int targetTemperature;
	public float delta;
	public JLabel txtTemp;
	public JRadioButton btnOn;
	public JRadioButton btnAuto;
	
	public HeaterItem(byte n) {
		this.heaterAddress = new byte[3];
		this.heaterAddress[0] = 0;
		this.heaterAddress[1] = 0;
		this.heaterAddress[2] = n;
		this.address = new byte[8];
	}

	public HeaterItem() {
		this.heaterAddress = new byte[3];
		this.address = new byte[8];
		this.actualState = false;
		this.btnAuto = new JRadioButton();
		this.btnOn = new JRadioButton();
		this.delta = 0;
		this.isAuto = false;
		this.isConnected = false;
		this.isEnabled = false;
		this.isOn = false;
		this.port = 0;
		this.powerConsumption = 0;
		this.priority = 0;
		this.targetTemperature = 0;
		this.temperature = 0;
		this.temperatureAdjust = 0;
		this.txtTemp = new JLabel();
		this.wantsOn = false;
	}
}
