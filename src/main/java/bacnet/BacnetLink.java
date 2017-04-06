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

import com.serotonin.bacnet4j.npdu.ip.IpNetwork;

public class BacnetLink {
	private static final Logger LOGGER = LoggerFactory.getLogger(BacnetLink.class);
	
	Node node;
	Set<BacnetSerialConn> serialConns = new HashSet<BacnetSerialConn>();
	
	private BacnetLink(Node node) {
		this.node = node;
		//this.futures = new ConcurrentHashMap<BacnetPoint, ScheduledFuture<?>>();
		//this.connections = new HashSet<BacnetConn>();
	}

	public static void start(Node node) {
		final BacnetLink link = new BacnetLink(node);
		link.init();
	}

	private void init() {		
		makeAddIpAction();
		makeAddSerialAction();
		makePortScanAction();

		restoreLastSession();
	}
	
	private void restoreLastSession() {
		if (node.getChildren() == null) {
			return;
		}

		for (Node child : node.getChildren().values()) {
			if (child.getAction() == null && !child.getName().equals("defs")) {
				BacnetConn bc = BacnetConn.buildConn(this, child);
				 if (bc != null) {
					 bc.restoreLastSession();
				 } else {
					 child.delete(false);
				 }
			}
		}
	}
	
	
	/////////////////////////////////////////////////////////////////////////////////////////
	// Actions
	/////////////////////////////////////////////////////////////////////////////////////////
	
	private void makeAddIpAction() {
		Action act = new Action(Permission.READ, new Handler<ActionResult>(){
			@Override
			public void handle(ActionResult event) {
				addConn(event);
			}
		});
		act.addParameter(new Parameter("Name", ValueType.STRING));
		act.addParameter(new Parameter("Subnet Mask", ValueType.STRING, new Value(IpNetwork.DEFAULT_SUBNET_MASK)));
		act.addParameter(new Parameter("Port", ValueType.NUMBER, new Value(IpNetwork.DEFAULT_PORT)));
		act.addParameter(new Parameter("Local Bind Address", ValueType.STRING, new Value(IpNetwork.DEFAULT_BIND_IP)));
		act.addParameter(new Parameter("Local Network Number", ValueType.NUMBER, new Value(0)));
		act.addParameter(new Parameter("Register As Foreign Device In BBMD", ValueType.BOOL, new Value(false)));
		act.addParameter(new Parameter("BBMD IPs With Network Number", ValueType.STRING, new Value("")));
//		act.addParameter(new Parameter("strict device comparisons", ValueType.BOOL, new Value(true)));
		act.addParameter(new Parameter("Timeout", ValueType.NUMBER, new Value(6000)));
		act.addParameter(new Parameter("Segment Timeout", ValueType.NUMBER, new Value(5000)));
		act.addParameter(new Parameter("Segment Window", ValueType.NUMBER, new Value(5)));
		act.addParameter(new Parameter("Retries", ValueType.NUMBER, new Value(2)));
		act.addParameter(new Parameter("Local Device ID", ValueType.NUMBER, new Value(1212)));
		act.addParameter(new Parameter("Local Device Name", ValueType.STRING, new Value("DSLink")));
		act.addParameter(new Parameter("Local Device Vendor", ValueType.STRING, new Value("DGLogik Inc.")));
		
		node.createChild("add ip connection", true).setAction(act).setConfig("actionGroup", new Value("Add Connection")).setConfig("actionGroupSubTitle", new Value("IP")).build().setSerializable(false);
	}
	
	private void makeAddSerialAction() {
		Action act = new Action(Permission.READ, new Handler<ActionResult>(){
			@Override
			public void handle(ActionResult event) {
				addConn(event);
			}
		});
		act.addParameter(new Parameter("Name", ValueType.STRING));
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
			act.addParameter(new Parameter("Comm Port ID", ValueType.makeEnum(portids)));
			act.addParameter(new Parameter("Comm Port ID (Manual Entry)", ValueType.STRING));
		} else {
			act.addParameter(new Parameter("Comm Port ID", ValueType.STRING));
		}
		act.addParameter(new Parameter("Baud Rate", ValueType.NUMBER, new Value(19200)));
		act.addParameter(new Parameter("This Station ID", ValueType.NUMBER, new Value(0)));
		act.addParameter(new Parameter("Frame Error Retry Count", ValueType.NUMBER, new Value(1)));
		act.addParameter(new Parameter("Max Info Frames", ValueType.NUMBER, new Value(1)));
		act.addParameter(new Parameter("Local Network Number", ValueType.NUMBER, new Value(0)));
//		act.addParameter(new Parameter("strict device comparisons", ValueType.BOOL, new Value(true)));
		act.addParameter(new Parameter("Timeout", ValueType.NUMBER, new Value(6000)));
		act.addParameter(new Parameter("Segment Timeout", ValueType.NUMBER, new Value(5000)));
		act.addParameter(new Parameter("Segment Window", ValueType.NUMBER, new Value(5)));
		act.addParameter(new Parameter("Retries", ValueType.NUMBER, new Value(2)));
		act.addParameter(new Parameter("Local Device ID", ValueType.NUMBER, new Value(1212)));
		act.addParameter(new Parameter("Local Device Name", ValueType.STRING, new Value("DSLink")));
		act.addParameter(new Parameter("Local Device Vendor", ValueType.STRING, new Value("DGLogik Inc.")));
		
		Node anode = node.getChild("add mstp connection", true);
		if (anode != null) {
			anode.setAction(act);
		} else {
			node.createChild("add mstp connection", true).setAction(act).setConfig("actionGroup", new Value("Add Connection")).setConfig("actionGroupSubTitle", new Value("MSTP")).build().setSerializable(false);
		}
	}
	
	private void addConn(ActionResult event) {
		Node child = Utils.actionResultToNode(event, node);
		
		if (child != null) {
			BacnetConn bc = BacnetConn.buildConn(this, child);
			if (bc != null) {
				bc.init();
			}
		}
	}

	
	private void makePortScanAction() {
		Action act = new Action(Permission.READ, new Handler<ActionResult>(){
			@Override
			public void handle(ActionResult event) {
				makeAddSerialAction();

				for (BacnetSerialConn conn : serialConns) {
					conn.makeEditAction();
				}
			}	
		});
		node.createChild("scan for serial ports", true).setAction(act).build().setSerializable(false);
	}
	

	
	

}
