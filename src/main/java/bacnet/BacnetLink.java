package bacnet;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.Permission;
import org.dsa.iot.dslink.node.actions.Action;
import org.dsa.iot.dslink.node.actions.ActionResult;
import org.dsa.iot.dslink.node.actions.Parameter;
import org.dsa.iot.dslink.node.value.Value;
import org.dsa.iot.dslink.node.value.ValueType;
import org.dsa.iot.dslink.serializer.Deserializer;
import org.dsa.iot.dslink.serializer.Serializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.vertx.java.core.Handler;

import bacnet.BacnetConn.CovType;

import com.serotonin.io.serial.CommPortConfigException;
import com.serotonin.io.serial.CommPortProxy;
import com.serotonin.io.serial.SerialUtils;

public class BacnetLink {
	private static final Logger LOGGER;
	
	private Node node;
	final Map<BacnetPoint, ScheduledFuture<?>> futures;
	final Set<BacnetConn> serialConns;
	Serializer copySerializer;
	Deserializer copyDeserializer;
	
	static {
		LOGGER = LoggerFactory.getLogger(BacnetLink.class);
	}
	
	private BacnetLink(Node node, Serializer ser, Deserializer deser) {
		this.node = node;
		this.futures = new ConcurrentHashMap<BacnetPoint, ScheduledFuture<?>>();
		this.copyDeserializer = deser;
		this.copySerializer = ser;
		this.serialConns = new HashSet<BacnetConn>();
	}
	
	public static void start(Node parent, Serializer ser, Deserializer deser) {
		Node node = parent;
		final BacnetLink link = new BacnetLink(node, ser, deser);
		link.init();
	}
	
	private void init() {
		
		restoreLastSession();
		
		Action act = new Action(Permission.READ, new AddConnHandler(true));
		act.addParameter(new Parameter("name", ValueType.STRING));
		act.addParameter(new Parameter("broadcast ip", ValueType.STRING, new Value("10.0.1.255")));
		act.addParameter(new Parameter("port", ValueType.NUMBER, new Value(47808)));
		act.addParameter(new Parameter("local bind address", ValueType.STRING, new Value("0.0.0.0")));
		act.addParameter(new Parameter("local network number", ValueType.NUMBER, new Value(0)));
		act.addParameter(new Parameter("strict device comparisons", ValueType.BOOL, new Value(true)));
		act.addParameter(new Parameter("timeout", ValueType.NUMBER, new Value(6000)));
		act.addParameter(new Parameter("segment timeout", ValueType.NUMBER, new Value(5000)));
		act.addParameter(new Parameter("segment window", ValueType.NUMBER, new Value(5)));
		act.addParameter(new Parameter("retries", ValueType.NUMBER, new Value(2)));
		act.addParameter(new Parameter("local device id", ValueType.NUMBER, new Value(1212)));
		act.addParameter(new Parameter("local device name", ValueType.STRING, new Value("DSLink")));
		act.addParameter(new Parameter("local device vendor", ValueType.STRING, new Value("DGLogik Inc.")));
		act.addParameter(new Parameter("default polling interval", ValueType.NUMBER, new Value(5)));
		node.createChild("add ip connection").setAction(act).build().setSerializable(false);
		
//		act = new Action(Permission.READ, new RxtxSetupHandler());
//		act.addParameter(new Parameter("Operating System", ValueType.makeEnum("Windows-x32", "Windows-x64", "Linux-x86", "Linux-x86_64", "Linux-ia64", "MacOSX")));
//		node.createChild("setup rxtx").setAction(act).build().setSerializable(false);
		
		act = getAddSerialAction();
		node.createChild("add mstp connection").setAction(act).build().setSerializable(false);
		
		act = new Action(Permission.READ, new PortScanHandler());
		node.createChild("scan for serial ports").setAction(act).build().setSerializable(false);
	}
	
	private class PortScanHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
			Action act = getAddSerialAction();
			Node anode = node.getChild("add mstp connection");
			if (anode == null) {
				anode = node.createChild("add mstp connection").setAction(act).build();
				anode.setSerializable(false);
			} else {
				anode.setAction(act);
			}
			
			for (BacnetConn conn: serialConns) {
				anode = conn.node.getChild("edit");
				if (anode != null) { 
					act = conn.getEditAction();
					anode.setAction(act);
				}
			}
		}
	}
	
	static Set<String> listPorts() {
		Set<String> portids = new HashSet<String>();
		try {
			List<CommPortProxy> cports = SerialUtils.getCommPorts();
			for (CommPortProxy port: cports)  {
				portids.add(port.getId());
				LOGGER.debug("comm port found: " + port.getId() );
			}
		} catch (CommPortConfigException e) {
			// TODO Auto-generated catch block
			LOGGER.debug("error scanning for ports: ", e);
		}
		return portids;
	}
	
	private Action getAddSerialAction() {
		Action act = new Action(Permission.READ, new AddConnHandler(false));
		act.addParameter(new Parameter("name", ValueType.STRING));
		Set<String> portids = listPorts();
		if (portids.size() > 0) {
			act.addParameter(new Parameter("comm port id", ValueType.makeEnum(portids)));
			act.addParameter(new Parameter("comm port id (manual entry)", ValueType.STRING));
		} else {
			act.addParameter(new Parameter("comm port id", ValueType.STRING));
		}
		act.addParameter(new Parameter("baud rate", ValueType.NUMBER, new Value(19200)));
		act.addParameter(new Parameter("this station id", ValueType.NUMBER, new Value(0)));
		act.addParameter(new Parameter("frame error retry count", ValueType.NUMBER, new Value(1)));
		act.addParameter(new Parameter("local network number", ValueType.NUMBER, new Value(0)));
		act.addParameter(new Parameter("strict device comparisons", ValueType.BOOL, new Value(true)));
		act.addParameter(new Parameter("timeout", ValueType.NUMBER, new Value(6000)));
		act.addParameter(new Parameter("segment timeout", ValueType.NUMBER, new Value(5000)));
		act.addParameter(new Parameter("segment window", ValueType.NUMBER, new Value(5)));
		act.addParameter(new Parameter("retries", ValueType.NUMBER, new Value(2)));
		act.addParameter(new Parameter("local device id", ValueType.NUMBER, new Value(1212)));
		act.addParameter(new Parameter("local device name", ValueType.STRING, new Value("DSLink")));
		act.addParameter(new Parameter("local device vendor", ValueType.STRING, new Value("DGLogik Inc.")));
		act.addParameter(new Parameter("default polling interval", ValueType.NUMBER, new Value(5)));
		return act;
	}
	
	public void restoreLastSession() {
		if (node.getChildren() == null) return;
		for (Node child: node.getChildren().values()) {
			Value isip = child.getAttribute("isIP");
			Value bip = child.getAttribute("broadcast ip");
			Value port = child.getAttribute("port");
			Value lba = child.getAttribute("local bind address");
			Value commPort = child.getAttribute("comm port id");
			Value baud = child.getAttribute("baud rate");
			Value station = child.getAttribute("this station id");
			Value ferc = child.getAttribute("frame error retry count");
			Value lnn = child.getAttribute("local network number");
			Value strict = child.getAttribute("strict device comparisons");
			Value timeout = child.getAttribute("timeout");
			Value segtimeout = child.getAttribute("segment timeout");
			Value segwin = child.getAttribute("segment window");
			Value retries = child.getAttribute("retries");
			Value locdevId = child.getAttribute("local device id");
			Value locdevName = child.getAttribute("local device name");
			Value locdevVend = child.getAttribute("local device vendor");
			Value interval = child.getAttribute("default polling interval");
			if (isip!=null && bip!=null && port!=null && lba!=null && commPort!=null 
					&& baud!=null && station!=null && ferc!=null && lnn!=null && 
					strict!=null && timeout!=null && segtimeout!=null && segwin!=null 
					&& retries!=null && locdevId!=null && locdevName!=null && 
					locdevVend!=null && interval!=null) {
				
				BacnetConn bc = new BacnetConn(getMe(), child);
				bc.restoreLastSession();
			} else {
				node.removeChild(child);
			}
		}
	}
	
	void setupPoint(final BacnetPoint point, final DeviceFolder devicefold) {
		if (devicefold.conn.localDevice == null) {
			devicefold.conn.stop();
			return;
		}
		setupNode(point, devicefold, 0);
		setupNode(point, devicefold, 1);
    }
	
	private void setupNode(final BacnetPoint point, final DeviceFolder devicefold, final int nodeIndex) {
		Node child;
		switch (nodeIndex) {
		case 0: child = point.node; break;
		case 1: child = point.node.getChild("present value"); break;
		default: return;
		}
		if (devicefold.root.covType != CovType.NONE && point.isCov()) {
//			ScheduledFuture<?> fut = futures.remove(child);
//			if (fut != null) {
//				fut.cancel(false);
//			}
			child.getListener().setOnSubscribeHandler(new Handler<Node>() {
				public void handle(final Node event) {
					point.poller.subscribe(nodeIndex, true);
				}
			});
		} else {
			child.getListener().setOnSubscribeHandler(new Handler<Node>() {
				public void handle(final Node event) {
					point.poller.subscribe(nodeIndex, false);
				}
			});
		}
		child.getListener().setOnUnsubscribeHandler(new Handler<Node>() {
			public void handle(final Node event) {
				point.poller.unsubscribe(nodeIndex);
			}
		});
	}
	
	private class AddConnHandler implements Handler<ActionResult> {
		private boolean isIP;
		AddConnHandler(boolean isIP) {
			this.isIP = isIP;
		}
		public void handle(ActionResult event) {
			String name = event.getParameter("name", ValueType.STRING).getString();
			String bip= " "; int port = 0; String lba = " ";
			String commPort = " "; int baud = 0; int station = 0; int ferc = 1;
			if (isIP) {
				bip = event.getParameter("broadcast ip", ValueType.STRING).getString();
				port = event.getParameter("port", ValueType.NUMBER).getNumber().intValue();
				lba = event.getParameter("local bind address", ValueType.STRING).getString();
			} else {
				commPort = event.getParameter("comm port id", ValueType.STRING).getString();
				baud = event.getParameter("baud rate", ValueType.NUMBER).getNumber().intValue();
				station = event.getParameter("this station id", ValueType.NUMBER).getNumber().intValue();
				ferc = event.getParameter("frame error retry count", ValueType.NUMBER).getNumber().intValue();
			}
			int lnn = event.getParameter("local network number", ValueType.NUMBER).getNumber().intValue();
			boolean strict = event.getParameter("strict device comparisons", ValueType.BOOL).getBool();
			int timeout = event.getParameter("timeout", ValueType.NUMBER).getNumber().intValue();
			int segtimeout = event.getParameter("segment timeout", ValueType.NUMBER).getNumber().intValue();
			int segwin = event.getParameter("segment window", ValueType.NUMBER).getNumber().intValue();
			int retries = event.getParameter("retries", ValueType.NUMBER).getNumber().intValue();
			int locdevId = event.getParameter("local device id", ValueType.NUMBER).getNumber().intValue();
			String locdevName = event.getParameter("local device name", ValueType.STRING).getString();
			String locdevVend = event.getParameter("local device vendor", ValueType.STRING).getString();
			long interval = (long) (1000*event.getParameter("default polling interval", ValueType.NUMBER).getNumber().doubleValue());
			
			Node child = node.createChild(name).build();
			child.setAttribute("isIP", new Value(isIP));
			child.setAttribute("broadcast ip", new Value(bip));
			child.setAttribute("port", new Value(port));
			child.setAttribute("local bind address", new Value(lba));
			child.setAttribute("comm port id", new Value(commPort));
			child.setAttribute("baud rate", new Value(baud));
			child.setAttribute("this station id", new Value(station));
			child.setAttribute("frame error retry count", new Value(ferc));
			child.setAttribute("local network number", new Value(lnn));
			child.setAttribute("strict device comparisons", new Value(strict));
			child.setAttribute("timeout", new Value(timeout));
			child.setAttribute("segment timeout", new Value(segtimeout));
			child.setAttribute("segment window", new Value(segwin));
			child.setAttribute("retries", new Value(retries));
			child.setAttribute("local device id", new Value(locdevId));
			child.setAttribute("local device name", new Value(locdevName));
			child.setAttribute("local device vendor", new Value(locdevVend));
			child.setAttribute("default polling interval", new Value(interval));

			BacnetConn conn = new BacnetConn(getMe(), child);
			conn.init();
		}
	}
	
	private BacnetLink getMe() {
		return this;
	}
	

}
