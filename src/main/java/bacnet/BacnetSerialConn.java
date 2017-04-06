package bacnet;

import java.util.HashSet;
import java.util.Set;

import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.Permission;
import org.dsa.iot.dslink.node.actions.Action;
import org.dsa.iot.dslink.node.actions.ActionResult;
import org.dsa.iot.dslink.node.actions.Parameter;
import org.dsa.iot.dslink.node.value.Value;
import org.dsa.iot.dslink.node.value.ValueType;
import org.dsa.iot.dslink.util.handler.Handler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.serotonin.bacnet4j.npdu.Network;
import com.serotonin.bacnet4j.npdu.mstp.MasterNode;
import com.serotonin.bacnet4j.npdu.mstp.MstpNetwork;
import com.serotonin.bacnet4j.transport.Transport;

public class BacnetSerialConn extends BacnetConn {
	
	private static final Logger LOGGER = LoggerFactory.getLogger(BacnetSerialConn.class);
	
	String commPort;
	int baud;
	int station;
	int frameErrorRetryCount;
	int maxInfoFrames;
	
	SerialPortWrapperImpl portWrapper;
	
	BacnetSerialConn(BacnetLink link, Node node) {
		super(link, node);
		link.serialConns.add(this);
	}

	@Override
	Network getNetwork() {
		portWrapper = new SerialPortWrapperImpl(commPort, baud);
		MasterNode mstpNode = new MasterNode(portWrapper, (byte) station, frameErrorRetryCount);
		mstpNode.setMaxInfoFrames(maxInfoFrames);
		return new MstpNetwork(mstpNode, localNetworkNumber);
	}

	@Override
	void registerAsForeignDevice(Transport transport) {
		//No-op
	}
	
	@Override
	protected void makeEditAction() {
		Action act = new Action(Permission.READ, new Handler<ActionResult>(){
			@Override
			public void handle(ActionResult event) {
				edit(event);
			}
		});
		Set<String> portids = new HashSet<String>();
		try {
			String[] cports = Utils.getCommPorts();
			for (String port : cports) {
				portids.add(port);
			}
		} catch (Exception e) {
			LOGGER.debug("", e);
		}
		if (portids.size() > 0) {
			if (portids.contains(commPort)) {
				act.addParameter(new Parameter("Comm Port ID", ValueType.makeEnum(portids), new Value(commPort)));
				act.addParameter(new Parameter("Comm Port ID (Manual Entry)", ValueType.STRING));
			} else {
				act.addParameter(new Parameter("Comm Port ID", ValueType.makeEnum(portids)));
				act.addParameter(new Parameter("Comm Port ID (Manual Entry)", ValueType.STRING, new Value(commPort)));
			}
		} else {
			act.addParameter(new Parameter("Comm Port ID", ValueType.STRING, new Value(commPort)));
		}
		act.addParameter(new Parameter("Baud Rate", ValueType.NUMBER, new Value(baud)));
		act.addParameter(new Parameter("This Station ID", ValueType.NUMBER, new Value(station)));
		act.addParameter(new Parameter("Frame Error Retry Count", ValueType.NUMBER, new Value(frameErrorRetryCount)));
		act.addParameter(new Parameter("Max Info Frames", ValueType.NUMBER, new Value(maxInfoFrames)));
		act.addParameter(new Parameter("Local Network Number", ValueType.NUMBER, new Value(localNetworkNumber)));
//		act.addParameter(new Parameter("strict device comparisons", ValueType.BOOL, new Value(true)));
		act.addParameter(new Parameter("Timeout", ValueType.NUMBER, new Value(timeout)));
		act.addParameter(new Parameter("Segment Timeout", ValueType.NUMBER, new Value(segmentTimeout)));
		act.addParameter(new Parameter("Segment Window", ValueType.NUMBER, new Value(segmentWindow)));
		act.addParameter(new Parameter("Retries", ValueType.NUMBER, new Value(retries)));
		act.addParameter(new Parameter("Local Device ID", ValueType.NUMBER, new Value(localDeviceId)));
		act.addParameter(new Parameter("Local Device Name", ValueType.STRING, new Value(localDeviceName)));
		act.addParameter(new Parameter("Local Device Vendor", ValueType.STRING, new Value(localDeviceVendor)));
		
		Node anode = node.getChild(ACTION_EDIT, true);
		if (anode == null) {
			node.createChild(ACTION_EDIT, true).setAction(act).build().setSerializable(false);
		} else {
			anode.setAction(act);
		}
	}
	
	@Override
	protected void setVarsFromConfigs() {
		super.setVarsFromConfigs();
		commPort = Utils.safeGetRoConfigString(node, "Comm Port ID", commPort);
		baud = Utils.safeGetRoConfigNum(node, "Baud Rate", baud).intValue();
		station = Utils.safeGetRoConfigNum(node, "This Station ID", station).intValue();
		frameErrorRetryCount = Utils.safeGetRoConfigNum(node, "Frame Error Retry Count", frameErrorRetryCount).intValue();
		maxInfoFrames = Utils.safeGetRoConfigNum(node, "Max Info Frames", maxInfoFrames).intValue();
	}
	
	@Override
	protected void remove() {
		super.remove();
		link.serialConns.remove(this);
	}
	
	@Override
	protected void stop() {
		super.stop();
		Object monitor = portWrapper.getPortCloseMonitor();
		synchronized(monitor) {
			if (!portWrapper.isClosed()) {
				try {
					monitor.wait(timeout);
				} catch (InterruptedException e) {
				}
			}
		}
	}


}
