package bacnet;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.Permission;
import org.dsa.iot.dslink.node.actions.Action;
import org.dsa.iot.dslink.node.actions.ActionResult;
import org.dsa.iot.dslink.node.actions.Parameter;
import org.dsa.iot.dslink.node.value.Value;
import org.dsa.iot.dslink.node.value.ValueType;
import org.dsa.iot.dslink.util.Objects;
import org.vertx.java.core.Handler;
import org.vertx.java.core.json.JsonObject;

import bacnet.BacnetConn.CovType;

import com.serotonin.bacnet4j.RemoteDevice;
import com.serotonin.bacnet4j.type.primitive.ObjectIdentifier;
import com.serotonin.bacnet4j.util.PropertyReferences;

public class DeviceNode extends DeviceFolder {
	
	final Node statnode;
	private boolean enabled;
	RemoteDevice device;
	long interval;
	CovType covType;
	
	private final ScheduledThreadPoolExecutor stpe;
	private final Map<ObjectIdentifier, BacnetPoint> subscribedPoints = new HashMap<ObjectIdentifier, BacnetPoint>();
	private ScheduledFuture<?> future = null;
	
	DeviceNode(BacnetConn conn, Node node, RemoteDevice d) {
		super(conn, node);
		this.device = d;
		this.root = this;
		
		if (node.getChild("STATUS") != null) {
			this.statnode = node.getChild("STATUS");
			enabled = new Value("enabled").equals(statnode.getValue());
		} else {
			this.statnode = node.createChild("STATUS").setValueType(ValueType.STRING).setValue(new Value("enabled")).build();
			enabled = true;
		}
		
		if (d == null) {
			statnode.setValue(new Value("disabled"));
			enabled = false;
		}
		
		if (enabled) {
			Action act = new Action(Permission.READ, new Handler<ActionResult>() {
				public void handle(ActionResult event) {
					disable();
				}
			});
			node.createChild("disable").setAction(act).build().setSerializable(false);
		} else {
			Action act = new Action(Permission.READ, new Handler<ActionResult>() {
				public void handle(ActionResult event) {
					enable();
				}
			});
			node.createChild("enable").setAction(act).build().setSerializable(false);
		}
		
		this.interval = node.getAttribute("polling interval").getNumber().longValue();
		this.covType = CovType.NONE;
		try {
			this.covType = CovType.valueOf(node.getAttribute("cov usage").getString());
		} catch (Exception e) {
		}
		
		if (conn.isIP) this.stpe = Objects.createDaemonThreadPool();
		else this.stpe = conn.getDaemonThreadPool();
		
		makeEditAction();

	}
	
	ScheduledThreadPoolExecutor getDaemonThreadPool() {
		return stpe;
	}
	
	private void enable() {
		enabled = true;
		if (future == null) startPolling();
		if (device == null) {
			String mac = node.getAttribute("MAC address").getString();
			CovType covtype = CovType.NONE;
			try {
				covtype = CovType.valueOf(node.getAttribute("cov usage").getString());
			} catch (Exception e1) {
			}
			int covlife = node.getAttribute("cov lease time (minutes)").getNumber().intValue();
			final RemoteDevice d = conn.getDevice(mac, interval, covtype, covlife);
			conn.getDeviceProps(d);
			device = d;
		}
		statnode.setValue(new Value("enabled"));
		node.removeChild("enable");
		Action act = new Action(Permission.READ, new Handler<ActionResult>() {
			public void handle(ActionResult event) {
				disable();
			}
		});
		node.createChild("disable").setAction(act).build().setSerializable(false);
		if (device == null) disable();
	}
	
	private void disable() {
		enabled = false;
		stopPolling();
		statnode.setValue(new Value("disabled"));
		node.removeChild("disable");
		Action act = new Action(Permission.READ, new Handler<ActionResult>() {
			public void handle(ActionResult event) {
				enable();
			}
		});
		node.createChild("enable").setAction(act).build().setSerializable(false);
	}
	
	@Override
	protected void remove() {
		super.remove();
		if (conn.isIP) stpe.shutdown();
	}
	
	private void makeEditAction() {
		Action act = new Action(Permission.READ, new EditHandler());
		act.addParameter(new Parameter("name", ValueType.STRING, new Value(node.getName())));
		act.addParameter(new Parameter("MAC address", ValueType.STRING, node.getAttribute("MAC address")));
		double defint = node.getAttribute("polling interval").getNumber().doubleValue()/1000;
	    act.addParameter(new Parameter("polling interval", ValueType.NUMBER, new Value(defint)));
	    act.addParameter(new Parameter("cov usage", ValueType.makeEnum("NONE", "UNCONFIRMED", "CONFIRMED"), node.getAttribute("cov usage")));
	    act.addParameter(new Parameter("cov lease time (minutes)", ValueType.NUMBER, node.getAttribute("cov lease time (minutes)")));
	    Node anode = node.getChild("edit");
	    if (anode == null) node.createChild("edit").setAction(act).build().setSerializable(false);
	    else anode.setAction(act);
	}

	
	private class EditHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
			String name = event.getParameter("name", ValueType.STRING).getString();
			long interv = (long) (1000*event.getParameter("polling interval", ValueType.NUMBER).getNumber().doubleValue());
			CovType covtype = CovType.NONE;
			try {
				covtype = CovType.valueOf(event.getParameter("cov usage").getString());
			} catch (Exception e1) {
			}
			int covlife =event.getParameter("cov lease time (minutes)", ValueType.NUMBER).getNumber().intValue();
			String mac = event.getParameter("MAC address", ValueType.STRING).getString();
			if (!mac.equals(node.getAttribute("MAC address").getString())) {
				final RemoteDevice d = conn.getDevice(mac, interv, covtype, covlife);
				conn.getDeviceProps(d);
				device = d;
			}
			interval = interv;
			covType = covtype;
//        	try {
//        		mac = d.getAddress().getMacAddress().toIpPortString();
//        	} catch (Exception e) {
//        		mac = Byte.toString(d.getAddress().getMacAddress().getMstpAddress());
//        	}
        	node.setAttribute("MAC address", new Value(mac));
	        node.setAttribute("polling interval", new Value(interval));
	        node.setAttribute("cov usage", new Value(covtype.toString()));
	        node.setAttribute("cov lease time (minutes)", new Value(covlife));
	        
	        if (!name.equals(node.getName())) {
				rename(name);
			}
	        
	        makeEditAction();
		}
	}
	
	@Override
	protected void duplicate(String name) {
		JsonObject jobj = conn.link.copySerializer.serialize();
		JsonObject parentobj = getParentJson(jobj, node);
		JsonObject nodeobj = parentobj.getObject(node.getName());
		parentobj.putObject(name, nodeobj);
		conn.link.copyDeserializer.deserialize(jobj);
		Node newnode = node.getParent().getChild(name);
		conn.restoreDevice(newnode);
		return;
		
	}
	
	protected JsonObject getParentJson(JsonObject jobj, Node n) {
		return jobj.getObject(conn.node.getName());
	}
	
	//polling
	void addPointSub(BacnetPoint point) {
		if (subscribedPoints.containsKey(point.oid)) return;
		subscribedPoints.put(point.oid, point);
		if (future == null) startPolling();
	}
	
	void removePointSub(BacnetPoint point) {
		subscribedPoints.remove(point.oid);
		if (subscribedPoints.size() == 0) stopPolling();
	}
	
	private void stopPolling() {
		if (future != null) {
			future.cancel(false);
			future = null;
		}
	}
	
	private void startPolling() {
		if (!enabled || subscribedPoints.size() == 0) return;
		
		future = stpe.scheduleWithFixedDelay(new Runnable() {
			public void run() {
				if (conn.localDevice == null) {
					conn.stop();
					return;
				}
				PropertyReferences refs = new PropertyReferences();
				for (ObjectIdentifier oid: subscribedPoints.keySet()) {
					DeviceFolder.addPropertyReferences(refs, oid);
				}
		      	getProperties(refs, subscribedPoints);
			}
		}, 0, interval, TimeUnit.MILLISECONDS);
		
		
	}
	
}
