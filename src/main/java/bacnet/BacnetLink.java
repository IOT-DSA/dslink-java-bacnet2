package bacnet;

import com.serotonin.bacnet4j.npdu.ip.IpNetwork;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.NodeManager;
import org.dsa.iot.dslink.node.Permission;
import org.dsa.iot.dslink.node.actions.Action;
import org.dsa.iot.dslink.node.actions.ActionResult;
import org.dsa.iot.dslink.node.actions.EditorType;
import org.dsa.iot.dslink.node.actions.Parameter;
import org.dsa.iot.dslink.node.value.Value;
import org.dsa.iot.dslink.node.value.ValueType;
import org.dsa.iot.dslink.serializer.Deserializer;
import org.dsa.iot.dslink.serializer.Serializer;
import org.dsa.iot.dslink.util.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BacnetLink {
	private static final Logger LOGGER = LoggerFactory.getLogger(BacnetLink.class);
	
	static final String ACTION_IMPORT = "import connection";
	
	final Node node;
	final Serializer serializer;
	final Deserializer deserializer;
	final Set<BacnetSerialConn> serialConns = new HashSet<>();
	
	private BacnetLink(Node node) {
		this.node = node;
		NodeManager manager = node.getLink().getDSLink().getNodeManager();
		this.serializer = new Serializer(manager);
		this.deserializer = new Deserializer(manager);
	}

	public static void start(Node node) {
		final BacnetLink link = new BacnetLink(node);
		link.init();
	}

	private void init() {		
		makeAddIpAction();
		makeAddSerialAction();
		makePortScanAction();
		makeImportAction();

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
		Action act = new Action(Permission.READ, event -> addConn(event));
		act.addParameter(new Parameter("Name", ValueType.STRING));
		act.addParameter(new Parameter("Subnet Mask", ValueType.STRING, new Value("0.0.0.0")));
		act.addParameter(new Parameter("Port", ValueType.NUMBER, new Value(IpNetwork.DEFAULT_PORT)));
		act.addParameter(new Parameter("Local Bind Address", ValueType.STRING, new Value(IpNetwork.DEFAULT_BIND_IP)));
		act.addParameter(new Parameter("Use Wildcard Address for Binding", ValueType.BOOL, new Value(true)));
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
		Action act = new Action(Permission.READ, event -> addConn(event));
		act.addParameter(new Parameter("Name", ValueType.STRING));
		Set<String> portids = new HashSet<>();
		try {
			String[] cports = Utils.getCommPorts();
			portids.addAll(Arrays.asList(cports));
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
		Action act = new Action(Permission.READ, event -> {
			makeAddSerialAction();

			for (BacnetSerialConn conn : serialConns) {
				conn.makeEditAction();
			}
		});
		node.createChild("scan for serial ports", true).setAction(act).build().setSerializable(false);
	}
	
	private void makeImportAction() {
		Action act = new Action(Permission.READ, event -> handleImport(event));
		act.addParameter(new Parameter("Name", ValueType.STRING));
		act.addParameter(new Parameter("JSON", ValueType.STRING).setEditorType(EditorType.TEXT_AREA));
		Node anode = node.getChild(ACTION_IMPORT, true);
		if (anode == null) {
			node.createChild(ACTION_IMPORT, true).setAction(act).build().setSerializable(false);
		} else {
			anode.setAction(act);
		}
	}
	
	private void handleImport(ActionResult event) {
		String name = event.getParameter("Name", ValueType.STRING).getString();
		String jsonStr = event.getParameter("JSON", ValueType.STRING).getString();
		JsonObject children = new JsonObject(jsonStr);
		Node child = node.createChild(name, true).build();
		try {
			Method deserMethod = Deserializer.class.getDeclaredMethod("deserializeNode", Node.class, JsonObject.class);
			deserMethod.setAccessible(true);
			deserMethod.invoke(deserializer, child, children);
			BacnetConn bc = BacnetConn.buildConn(this, child);
			 if (bc != null) {
				 bc.restoreLastSession();
			 } else {
				 child.delete(false);
			 }
		} catch (SecurityException | IllegalArgumentException | NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
			LOGGER.debug("", e);
			child.delete(false);
		}
	}

}
