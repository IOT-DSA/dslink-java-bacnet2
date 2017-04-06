package bacnet;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang3.tuple.Pair;
import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.NodeBuilder;
import org.dsa.iot.dslink.node.Permission;
import org.dsa.iot.dslink.node.actions.Action;
import org.dsa.iot.dslink.node.actions.ActionResult;
import org.dsa.iot.dslink.node.actions.Parameter;
import org.dsa.iot.dslink.node.value.Value;
import org.dsa.iot.dslink.node.value.ValueType;
import org.dsa.iot.dslink.util.handler.Handler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.serotonin.bacnet4j.RemoteDevice;
import com.serotonin.bacnet4j.exception.BACnetException;
import com.serotonin.bacnet4j.type.Encodable;
import com.serotonin.bacnet4j.type.constructed.ObjectPropertyReference;
import com.serotonin.bacnet4j.type.constructed.SequenceOf;
import com.serotonin.bacnet4j.type.enumerated.ObjectType;
import com.serotonin.bacnet4j.type.primitive.ObjectIdentifier;
import com.serotonin.bacnet4j.util.RequestUtils;

public class BacnetDevice {
	private static final Logger LOGGER = LoggerFactory.getLogger(BacnetDevice.class);
	
	static final String NODE_STATUS = "STATUS";
	static final String ACTION_REMOVE = "remove";
	static final String ACTION_EDIT = "edit";
	static final String ACTION_ADD_FOLDER = "add folder";
	static final String ACTION_DISCOVER_OBJECTS = "discover objects";
	static final String ACTION_ADD_OBJECT = "add object";
	static final String ACTION_STOP = "stop";
	static final String ACTION_RESTART = "restart";
	
	BacnetConn conn;
	private Node node;
	private final Node statnode;
	
	RemoteDevice remoteDevice;
	ReadWriteMonitor monitor = new ReadWriteMonitor();
	
	private int instanceNumber;
	private double pollingIntervalSeconds;
	
	private Map<BacnetProperty, ObjectPropertyReference> subscribed = new ConcurrentHashMap<BacnetProperty, ObjectPropertyReference>();
	private ScheduledFuture<?> pollingFuture = null;
	Object futureLock = new Object();
	
	public BacnetDevice(BacnetConn conn, Node node, RemoteDevice d) {
		this.conn = conn;
		this.node = node;
		try {
			monitor.checkInWriter();
			this.remoteDevice = d;
			monitor.checkOutWriter();
		} catch (InterruptedException e) {
			
		}
		
		instanceNumber = Utils.getAndMaybeSetRoConfigNum(node, "Instance Number", 0).intValue();
		pollingIntervalSeconds = Utils.getAndMaybeSetRoConfigNum(node, "Polling Interval", 5).doubleValue();
		
		this.statnode = node.createChild(NODE_STATUS, true).setValueType(ValueType.STRING).setValue(new Value(""))
				.build();
		this.statnode.setSerializable(false);
	}
	
	public void restoreLastSession() {
		init();
		restoreFolder(node);
	}
	
	private void restoreFolder(Node fnode) {
		if (fnode.getChildren() == null) {
			return;
		}
		for (Node child : fnode.getChildren().values()) {
			Value restype = child.getRoConfig("restoreAs");
			if (restype != null && "folder".equals(restype.getString())) {
				makeFolderActions(child);
				restoreFolder(child);
			} else if (restype != null && "object".equals(restype.getString())) {
				String typestr = Utils.safeGetRoConfigString(child, "Object Type", null);
				Number instnum = Utils.safeGetRoConfigNum(child, "Instance Number", null);
				ObjectIdentifier oid = null;
				if (typestr != null && instnum != null) {
					try {
						ObjectType type = ObjectType.forName(typestr);
						oid = new ObjectIdentifier(type, instnum.intValue());
					} catch (Exception e) {
					}
				}
				if (oid != null) {
					BacnetObject bo = new BacnetObject(this, child, oid);
					bo.restoreLastSession();
				} else {
					child.delete(false);
				}
			} else if (child.getAction() == null && !child.getName().equals(NODE_STATUS)) {
				child.delete(false);
			}
		}
	}
	
	public void init() {
		try {
			monitor.checkInWriter();
			if (remoteDevice == null) {
				statnode.setValue(new Value("Connecting"));
				try {
					conn.monitor.checkInReader();
					if (conn.localDevice != null) {
						try {
							remoteDevice = conn.localDevice.getRemoteDeviceBlocking(instanceNumber);
						} catch (BACnetException e) {
							statnode.setValue(new Value("Failed to Connect"));
							LOGGER.debug("", e);
						}
					} else {
						statnode.setValue(new Value("Connection Down"));
					}
					conn.monitor.checkOutReader();
				} catch (InterruptedException e) {
					
				}
			}
			if (remoteDevice != null) {
				statnode.setValue(new Value("Ready"));
			}
			monitor.checkOutWriter();
		} catch (InterruptedException e) {
			
		}
		
		makeFolderActions(node);
		makeEditAction();
		makeStopAction();
		makeRestartAction();
	}
	
	
	/////////////////////////////////////////////////////////////////////////////////////////
	// Polling
	/////////////////////////////////////////////////////////////////////////////////////////
	
	public void subscribeProperty(BacnetProperty prop) {
		ObjectPropertyReference opr = new ObjectPropertyReference(prop.oid, prop.pid);
		subscribed.put(prop, opr);
		startPolling();
	}
	
	public void unsubscribeProperty(BacnetProperty prop) {
		subscribed.remove(prop);
		if (subscribed.isEmpty()) {
			stopPolling();
		}
	}
	
	private void startPolling() {
		synchronized(futureLock) {
			if (pollingFuture != null) {
				return;
			}
			long interval = (long) (pollingIntervalSeconds * 1000);
			pollingFuture = conn.getStpe().scheduleWithFixedDelay(new Runnable() {
	
				@Override
				public void run() {
					readProperties();
				}
	
			}, 0, interval, TimeUnit.MILLISECONDS);
		}
	}
	
	private void stopPolling() {
		synchronized(futureLock) {
			if (pollingFuture != null) {
				pollingFuture.cancel(false);
				pollingFuture = null;
			}
		}
	}
	
	private void readProperties() {
		List<ObjectPropertyReference> oprs = new ArrayList<ObjectPropertyReference>(subscribed.size());
		List<BacnetProperty> props = new ArrayList<BacnetProperty>(subscribed.size());
		for (Entry<BacnetProperty, ObjectPropertyReference> entry : subscribed.entrySet()) {
			oprs.add(entry.getValue());
			props.add(entry.getKey());
		}
		List<Pair<ObjectPropertyReference, Encodable>> results = null;
		try {
			monitor.checkInReader();
			if (remoteDevice != null) {
				try {
					conn.monitor.checkInReader();
					if (conn.localDevice != null) {
						try {
//							LOGGER.info("Sending Read Properties Request to Device: " + node.getName());
							results = RequestUtils.readProperties(conn.localDevice, remoteDevice, oprs, null);
//							if (results != null) LOGGER.info("Recieved Read Properties Response from Device: " + node.getName());
						} catch (BACnetException e) {
							LOGGER.debug("", e);
						}
					}
					conn.monitor.checkOutReader();
				} catch (InterruptedException e) {
					
				}
			}
			monitor.checkOutReader();
		} catch (InterruptedException e) {
			
		}
		if (results == null) {
			return;
		}
		if (results.size() != props.size()) {
			LOGGER.error("Number of results doesn't match number of requests");
			return;
		}
		for (int i=0; i<results.size(); i++) {
			Pair<ObjectPropertyReference, Encodable> result = results.get(i);
			//ObjectPropertyReference opr = result.getLeft();
			Encodable val = result.getRight();
			BacnetProperty prop = props.get(i);
			prop.updateValue(val);
		}
	}
	
	
	/////////////////////////////////////////////////////////////////////////////////////////
	// Actions
	/////////////////////////////////////////////////////////////////////////////////////////
	
	private void makeFolderActions(Node fnode) {
		makeRemoveAction(fnode);
		makeAddFolderAction(fnode);
		makeDiscoverObjectsAction(fnode);
//		makeAddObjectAction(fnode);
	}
	
	private void makeRemoveAction(Node fnode) {
		Action act = new Action(Permission.READ, new Handler<ActionResult>(){
			@Override
			public void handle(ActionResult event) {
				remove(fnode);
			}
		});
		Node anode = fnode.getChild(ACTION_REMOVE, true);
		if (anode == null) {
			fnode.createChild(ACTION_REMOVE, true).setAction(act).build().setSerializable(false);
		} else {
			anode.setAction(act);
		}
	}
	
	private void remove(Node fnode) {
		if (node.equals(fnode)) {
			stop();
		}
		fnode.delete(false);
	}
	
	private void makeAddFolderAction(Node fnode) {
		Action act = new Action(Permission.READ, new Handler<ActionResult>(){
			@Override
			public void handle(ActionResult event) {
				addFolder(fnode, event);
			}
		});
		act.addParameter(new Parameter("Name", ValueType.STRING));
		Node anode = fnode.getChild(ACTION_ADD_FOLDER, true);
		if (anode == null) {
			fnode.createChild(ACTION_ADD_FOLDER, true).setAction(act).build().setSerializable(false);
		} else {
			anode.setAction(act);
		}
	}
	
	private void addFolder(Node fnode, ActionResult event) {
		String name = event.getParameter("Name", ValueType.STRING).getString();
		Node child = fnode.createChild(name, true).setRoConfig("restoreAs", new Value("folder")).build();
		makeFolderActions(child);
	}
	
	private void makeDiscoverObjectsAction(Node fnode) {
		Action act = new Action(Permission.READ, new Handler<ActionResult>(){
			@Override
			public void handle(ActionResult event) {
				discoverObjects(fnode);
			}
		});
		Node anode = fnode.getChild(ACTION_DISCOVER_OBJECTS, true);
		if (anode == null) {
			fnode.createChild(ACTION_DISCOVER_OBJECTS, true).setAction(act).build().setSerializable(false);
		} else {
			anode.setAction(act);
		}
	}
	
	private void makeAddObjectAction(Node fnode) {
		Action act = new Action(Permission.READ, new Handler<ActionResult>(){
			@Override
			public void handle(ActionResult event) {
				addObject(fnode, event);
			}
		});
		Node anode = fnode.getChild(ACTION_ADD_OBJECT, true);
		if (anode == null) {
			fnode.createChild(ACTION_ADD_OBJECT, true).setAction(act).build().setSerializable(false);
		} else {
			anode.setAction(act);
		}
	}
	
	private void discoverObjects(Node fnode) {
		SequenceOf<ObjectIdentifier> oids = null;
		try {
			monitor.checkInReader();
			if (remoteDevice != null) {
				try {
					conn.monitor.checkInReader();
					if (conn.localDevice != null) {
						try {
							LOGGER.info("Sending Object List Request for Device: " + node.getName());
							oids = RequestUtils.getObjectList(conn.localDevice, remoteDevice);
							if (oids != null) LOGGER.info("Recieved Object List Response from Device " + node.getName());
						} catch (BACnetException e) {
							LOGGER.debug("", e);
						}
					}
					conn.monitor.checkOutReader();
				} catch (InterruptedException e) {
					
				}
			}
			monitor.checkOutReader();
		} catch (InterruptedException e) {
			
		}
		if (oids == null) {
			return;
		}
		for (ObjectIdentifier oid : oids) {
			String name = oid.toString();
			NodeBuilder b = fnode.createChild(name, true).setRoConfig("restoreAs", new Value("object")).setRoConfig("Object Type", new Value(oid.getObjectType().toString())).setRoConfig("Instance Number", new Value(oid.getInstanceNumber())).setValueType(ValueType.STRING).setValue(new Value(""));
			BacnetObject bo = new BacnetObject(this, b.getChild(), oid);
			bo.init();
			b.build();
		}
	}
	
	private void addObject(Node fnode, ActionResult event) {
		
	}
	
	private void makeStopAction() {
		Action act = new Action(Permission.READ, new Handler<ActionResult>(){
			@Override
			public void handle(ActionResult event) {
				stop();
			}
		});
		Node anode = node.getChild(ACTION_STOP, true);
		if (anode == null) {
			node.createChild(ACTION_STOP, true).setAction(act).build().setSerializable(false);
		} else {
			anode.setAction(act);
		}
	}
	
	private void makeRestartAction() {
		Action act = new Action(Permission.READ, new Handler<ActionResult>(){
			@Override
			public void handle(ActionResult event) {
				restart();
			}
		});
		Node anode = node.getChild(ACTION_RESTART, true);
		if (anode == null) {
			node.createChild(ACTION_RESTART, true).setAction(act).build().setSerializable(false);
		} else {
			anode.setAction(act);
		}
	}
	
	private void makeEditAction() {
		Action act = new Action(Permission.READ, new Handler<ActionResult>(){
			@Override
			public void handle(ActionResult event) {
				edit(event);
			}
		});
		act.addParameter(new Parameter("Instance Number", ValueType.NUMBER, new Value(instanceNumber)));
		act.addParameter(new Parameter("Polling Interval", ValueType.NUMBER, new Value(pollingIntervalSeconds)));
		//TODO headless polling?
		act.addParameter(new Parameter("Use COV", ValueType.BOOL, new Value(false)));
		Node anode = node.getChild(ACTION_EDIT, true);
		if (anode == null) {
			node.createChild(ACTION_EDIT, true).setAction(act).build().setSerializable(false);
		} else {
			anode.setAction(act);
		}
	}
	
	private void edit(ActionResult event) {
		Utils.setConfigsFromActionResult(node, event);
		instanceNumber = Utils.safeGetRoConfigNum(node, "Instance Number", instanceNumber).intValue();
		pollingIntervalSeconds = Utils.safeGetRoConfigNum(node, "Polling Interval", pollingIntervalSeconds).doubleValue();
		restart();
	}
	
	
	private void restart() {
		stop();
		init();
	}
	
	private void stop() {
		try {
			monitor.checkInWriter();
			remoteDevice = null;
			statnode.setValue(new Value("Stopped"));
			monitor.checkOutWriter();
		} catch (InterruptedException e) {
			
		}
	}

}
