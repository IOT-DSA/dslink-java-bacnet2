package bacnet;

import java.io.InputStream;
import java.io.OutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.serotonin.bacnet4j.util.sero.SerialPortWrapper;

import jssc.SerialPort;

public class SerialPortWrapperImpl extends SerialPortWrapper {
	private static final Logger LOGGER = LoggerFactory.getLogger(SerialPortWrapperImpl.class);
	private final SerialPort port;
	private SerialInputStream is;
	private SerialOutputStream os;
	private int baudRate;
	
	private final Object portCloseMonitor = new Object();


	public SerialPortWrapperImpl(String portName, int baudRate) {
		port = new SerialPort(portName);
		this.baudRate = baudRate;

	}
	
	public Object getPortCloseMonitor() {
		return portCloseMonitor;
	}
	
	public boolean isClosed() {
		return !port.isOpened();
	}

	@Override
	public void close() throws Exception {
		port.closePort();
		synchronized (portCloseMonitor) {
			portCloseMonitor.notifyAll();
		}
		LOGGER.debug("serial port " + port.getPortName() + " is closed");
	}

	@Override
	public void open() throws Exception {
		port.openPort();
		port.setParams(this.getBaudRate(), this.getDataBits(), this.getStopBits(), this.getParity());
		port.setFlowControlMode(this.getFlowControlIn() | this.getFlowControlOut());

		this.os = new SerialOutputStream(port);
		this.is = new SerialInputStream(port);

		LOGGER.debug("serial port " + port.getPortName() + " is open");
	}

	@Override
	public InputStream getInputStream() {
		return is;
	}

	@Override
	public OutputStream getOutputStream() {
		return os;
	}

	public int getBaudRate() {
		return baudRate;
	}

	@Override
	public int getFlowControlIn() {
		return SerialPort.FLOWCONTROL_NONE;
	}

	@Override
	public int getFlowControlOut() {
		return SerialPort.FLOWCONTROL_NONE;
	}

	@Override
	public String getCommPortId() {
		return port.getPortName();
	}

}
