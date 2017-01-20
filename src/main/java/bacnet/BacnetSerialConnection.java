package bacnet;

import java.util.Set;

import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.actions.Action;
import org.dsa.iot.dslink.node.actions.ActionResult;
import org.dsa.iot.dslink.node.actions.Parameter;
import org.dsa.iot.dslink.node.value.Value;
import org.dsa.iot.dslink.node.value.ValueType;
import org.dsa.iot.dslink.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.serotonin.bacnet4j.npdu.Network;
import com.serotonin.bacnet4j.npdu.mstp.MasterNode;
import com.serotonin.bacnet4j.npdu.mstp.MstpNetwork;
import com.serotonin.bacnet4j.util.sero.SerialPortWrapper;
import com.serotonin.io.serial.SerialPortException;

import bacnet.BacnetConn.SerialPortWrapperImpl;

public class BacnetSerialConnection extends BacnetConn {
	private static final Logger LOGGER;

	static {
		LOGGER = LoggerFactory.getLogger(BacnetSerialConnection.class);
	}

	static final String ATTR_COMM_PORT_ID = "comm port id";
	static final String ATTR_COM_PORT__ID_MANUAL_ENTRY = "comm port id (manual entry)";
	static final String ATTR_BAUD_RATE = "baud rate";
	static final String ATTR_THIS_STATION_ID = "this station id";
	static final String ATTR_FRAME_ERROR_RETRY_COUNT = "frame error retry count";

	String commPort;
	int baud;
	int station;
	int frameErrorRetryCount;

	BacnetSerialConnection(BacnetLink link, Node node) {
		super(link, node);

		this.commPort = node.getAttribute(ATTR_COMM_PORT_ID).getString();
		this.baud = node.getAttribute(ATTR_BAUD_RATE).getNumber().intValue();
		this.station = node.getAttribute(ATTR_THIS_STATION_ID).getNumber().intValue();
		this.frameErrorRetryCount = node.getAttribute(ATTR_FRAME_ERROR_RETRY_COUNT).getNumber().intValue();
	}

	Network createNetwork(int localNetworkNumber) {
		Network network = null;
		try {
			SerialPortWrapper wrapper = new SerialPortWrapperImpl(commPort, baud, "DSLink");
			MasterNode mastnode = new MasterNode(wrapper, (byte) station, frameErrorRetryCount);
			network = new MstpNetwork(mastnode, localNetworkNumber);
		} catch (SerialPortException e) {
			LOGGER.debug("", e.getMessage());
			statnode.setValue(new Value(e.getMessage()));
			network = null;
		}

		return network;
	};

	@Override
	void addTransportParameters(Action act) {
		Set<String> portIds = BacnetLink.listPorts();
		if (portIds.size() > 0) {
			if (portIds.contains(node.getAttribute(ATTR_COMM_PORT_ID).getString())) {
				act.addParameter(new Parameter(ATTR_COMM_PORT_ID, ValueType.makeEnum(portIds),
						node.getAttribute("comm port id")));
				act.addParameter(new Parameter(ATTR_COM_PORT__ID_MANUAL_ENTRY, ValueType.STRING));
			} else {
				act.addParameter(new Parameter(ATTR_COMM_PORT_ID, ValueType.makeEnum(portIds)));
				act.addParameter(new Parameter(ATTR_COM_PORT__ID_MANUAL_ENTRY, ValueType.STRING,
						node.getAttribute(ATTR_COMM_PORT_ID)));
			}
		} else {
			act.addParameter(new Parameter(ATTR_COMM_PORT_ID, ValueType.STRING, node.getAttribute("comm port id")));
		}
		act.addParameter(new Parameter(ATTR_BAUD_RATE, ValueType.NUMBER, node.getAttribute("baud rate")));
		act.addParameter(new Parameter(ATTR_THIS_STATION_ID, ValueType.NUMBER, node.getAttribute("this station id")));
		act.addParameter(new Parameter(ATTR_FRAME_ERROR_RETRY_COUNT, ValueType.NUMBER,
				node.getAttribute(ATTR_FRAME_ERROR_RETRY_COUNT)));

	}

	@Override
	void setTransportAtrributions(ActionResult event) {
		String commPort = event.getParameter(ATTR_COMM_PORT_ID, ValueType.STRING).getString();
		int baud = event.getParameter(ATTR_BAUD_RATE, ValueType.NUMBER).getNumber().intValue();
		int station = event.getParameter(ATTR_THIS_STATION_ID, ValueType.NUMBER).getNumber().intValue();
		int ferc = event.getParameter(ATTR_FRAME_ERROR_RETRY_COUNT, ValueType.NUMBER).getNumber().intValue();

		node.setAttribute(ATTR_COMM_PORT_ID, new Value(commPort));
		node.setAttribute(ATTR_BAUD_RATE, new Value(baud));
		node.setAttribute(ATTR_THIS_STATION_ID, new Value(station));
		node.setAttribute(ATTR_FRAME_ERROR_RETRY_COUNT, new Value(ferc));
	}

	@Override
	void shutdown() {
		serialStpe.shutdown();
	}

	@Override
	BacnetConn getBacnetConnection(BacnetLink link2, Node newnode) {
		BacnetConn bc = new BacnetSerialConnection(link, newnode);
		return bc;
	}

	@Override
	void initializeScheduledThreadPoolExecutor() {
		link.serialConns.add(this);
		serialStpe = Objects.createDaemonThreadPool();
	}
}
