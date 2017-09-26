package bacnet;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.Permission;
import org.dsa.iot.dslink.node.actions.Action;
import org.dsa.iot.dslink.node.actions.ActionResult;
import org.dsa.iot.dslink.node.actions.EditorType;
import org.dsa.iot.dslink.node.actions.Parameter;
import org.dsa.iot.dslink.node.actions.table.Row;
import org.dsa.iot.dslink.node.value.Value;
import org.dsa.iot.dslink.node.value.ValueType;
import org.dsa.iot.dslink.serializer.Deserializer;
import org.dsa.iot.dslink.serializer.Serializer;
import org.dsa.iot.dslink.util.Objects;
import org.dsa.iot.dslink.util.handler.Handler;
import org.dsa.iot.dslink.util.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.serotonin.bacnet4j.LocalDevice;
import com.serotonin.bacnet4j.RemoteDevice;
import com.serotonin.bacnet4j.RemoteObject;
import com.serotonin.bacnet4j.event.DeviceEventListener;
import com.serotonin.bacnet4j.exception.BACnetException;
import com.serotonin.bacnet4j.npdu.Network;
import com.serotonin.bacnet4j.obj.BACnetObject;
import com.serotonin.bacnet4j.service.Service;
import com.serotonin.bacnet4j.service.unconfirmed.WhoIsRequest;
import com.serotonin.bacnet4j.transport.DefaultTransport;
import com.serotonin.bacnet4j.transport.Transport;
import com.serotonin.bacnet4j.type.Encodable;
import com.serotonin.bacnet4j.type.constructed.Address;
import com.serotonin.bacnet4j.type.constructed.Choice;
import com.serotonin.bacnet4j.type.constructed.DateTime;
import com.serotonin.bacnet4j.type.constructed.PropertyValue;
import com.serotonin.bacnet4j.type.constructed.SequenceOf;
import com.serotonin.bacnet4j.type.constructed.TimeStamp;
import com.serotonin.bacnet4j.type.enumerated.EventState;
import com.serotonin.bacnet4j.type.enumerated.EventType;
import com.serotonin.bacnet4j.type.enumerated.MessagePriority;
import com.serotonin.bacnet4j.type.enumerated.NotifyType;
import com.serotonin.bacnet4j.type.enumerated.PropertyIdentifier;
import com.serotonin.bacnet4j.type.notificationParameters.NotificationParameters;
import com.serotonin.bacnet4j.type.primitive.Boolean;
import com.serotonin.bacnet4j.type.primitive.CharacterString;
import com.serotonin.bacnet4j.type.primitive.ObjectIdentifier;
import com.serotonin.bacnet4j.type.primitive.OctetString;
import com.serotonin.bacnet4j.type.primitive.UnsignedInteger;
import com.serotonin.bacnet4j.util.RequestUtils;

public abstract class BacnetConn implements DeviceEventListener {
	private static final Logger LOGGER = LoggerFactory.getLogger(BacnetConn.class);
	
	static final String ACTION_ADD_LOCAL_SLAVE = "set up ip slave";
	static final String ACTION_REMOVE = "remove";
	static final String ACTION_EDIT = "edit";
	static final String ACTION_STOP = "stop";
	static final String ACTION_RESTART = "restart";
	static final String ACTION_DISCOVER_DEVICES = "discover devices";
	static final String ACTION_ADD_DEVICE = "add device";
	static final String ACTION_ADD_DEVICE_BY_NUMBER = "add device by number";
	static final String ACTION_ADD_DEVICE_BY_ADDR = "add device by address";
	static final String ACTION_ADD_ALL = "add all discovered devices";
	static final String ACTION_EXPORT = "export";
	static final String ACTION_IMPORT = "import device";
	
	static final String NODE_LOCAL = "LOCAL";
	static final String NODE_STATUS = "STATUS";
	static final String NODE_STATUS_SETTING_UP_CONNECTION = "Setting up connection";
	static final String NODE_STATUS_CONNECTED = "Connected";
	static final String NODE_STATUS_STOPPED = "Stopped";
	
	protected Node node;
	protected BacnetLink link;
	private final Node statnode;
	private final BacnetLocalDevice localController;
	private final ScheduledThreadPoolExecutor stpe = Objects.createDaemonThreadPool();
	
	private final Map<Integer, RemoteDevice> discovered = new ConcurrentHashMap<Integer, RemoteDevice>();
	Lock discoveryLock = new ReentrantLock();
	LocalDevice localDevice = null;
	ReadWriteMonitor monitor = new ReadWriteMonitor();
	
	Set<BacnetDevice> devices = new HashSet<BacnetDevice>();
	private Map<Long, BacnetObject> covSubs = new ConcurrentHashMap<Long, BacnetObject>();
	Object covSubsLock = new Object();
	private Random random = new Random();
	
	final Map<Integer, OctetString> networkRouters = new HashMap<Integer, OctetString>();
	final Map<String, Integer> bbmdIpToPort = new HashMap<String, Integer>();
	
	int localNetworkNumber;
	int timeout;
	int segmentTimeout;
	int segmentWindow;
	int retries;
	int localDeviceId;
	String localDeviceName;
	String localDeviceVendor;
	long defaultInterval;
	
	protected BacnetConn(BacnetLink link, Node node) {
		this.link = link;
		this.node = node;
		
		this.statnode = node.createChild(NODE_STATUS, true).setValueType(ValueType.STRING).setValue(new Value(""))
				.build();
		this.statnode.setSerializable(false);
		Node localNode = node.getChild(NODE_LOCAL, true);
		if (localNode == null) localNode = node.createChild(NODE_LOCAL, true).build();
		this.localController = new BacnetLocalDevice(this, localNode);
	}
	
	public static BacnetConn buildConn(BacnetLink link, Node node) {
		Value subnetMask = node.getRoConfig("Subnet Mask");
		if (subnetMask == null) {
			subnetMask = new Value("0.0.0.0");
			node.setRoConfig("Subnet Mask", subnetMask);
		}
		Value port = node.getRoConfig("Port");
		Value localBindAddress = node.getRoConfig("Local Bind Address");
		Value isRegisteredAsForeignDevice = node.getRoConfig("Register As Foreign Device In BBMD");
		if (isRegisteredAsForeignDevice == null) {
			isRegisteredAsForeignDevice = new Value(false);
			node.setRoConfig("Register As Foreign Device In BBMD", isRegisteredAsForeignDevice);
		}
		Value bbmdIpList = node.getRoConfig("BBMD IPs With Network Number");
		if (bbmdIpList == null) {
			bbmdIpList = new Value("");
			node.setRoConfig("BBMD IPs With Network Number", bbmdIpList);
		}
		
		// MSTP transport
		Value commPort = node.getRoConfig("Comm Port ID");
		Value baud = node.getRoConfig("Baud Rate");
		Value station = node.getRoConfig("This Station ID");
		Value ferc = node.getRoConfig("Frame Error Retry Count");
		Value maxInfoFrames = node.getRoConfig("Max Info Frames");

		// Common attribution
		Value localNetworkNumber = node.getRoConfig("Local Network Number");
//		Value strict = node.getRoConfig("strict device comparisons");
		Value timeout = node.getRoConfig("Timeout");
		Value segmentTimeout = node.getRoConfig("Segment Timeout");
		Value segmentWindow = node.getRoConfig("Segment Window");
		Value retries = node.getRoConfig("Retries");
		Value localDeviceId = node.getRoConfig("Local Device ID");
		Value localDeviceName = node.getRoConfig("Local Device Name");
		Value localDeviceVendor = node.getRoConfig("Local Device Vendor");
		
		BacnetConn conn;
		if (localNetworkNumber != null && timeout != null && segmentTimeout != null
				&& segmentWindow != null && retries != null && localDeviceId != null && localDeviceName != null
				&& localDeviceVendor != null) {
			if (subnetMask != null && port != null && localBindAddress != null) {
				BacnetIpConn iconn = new BacnetIpConn(link, node);
				iconn.subnetMask = subnetMask.getString();
				iconn.port = port.getNumber().intValue();
				iconn.localBindAddress = localBindAddress.getString();
				iconn.isRegisteredAsForeignDevice = isRegisteredAsForeignDevice.getBool();
				iconn.bbmdIpList = bbmdIpList.getString();
				conn = iconn;
			} else if (commPort != null && baud != null && station != null && ferc != null && maxInfoFrames != null) {
				BacnetSerialConn sconn = new BacnetSerialConn(link, node);
				sconn.commPort = commPort.getString();
				sconn.baud = baud.getNumber().intValue();
				sconn.station = station.getNumber().intValue();
				sconn.frameErrorRetryCount = ferc.getNumber().intValue();
				sconn.maxInfoFrames = maxInfoFrames.getNumber().intValue();
				conn = sconn;
			} else {
				return null;
			}
			conn.localNetworkNumber = localNetworkNumber.getNumber().intValue();
			conn.timeout = timeout.getNumber().intValue();
			conn.segmentTimeout = segmentTimeout.getNumber().intValue();
			conn.segmentWindow = segmentWindow.getNumber().intValue();
			conn.retries = retries.getNumber().intValue();
			conn.localDeviceId = localDeviceId.getNumber().intValue();
			conn.localDeviceName = localDeviceName.getString();
			conn.localDeviceVendor = localDeviceVendor.getString();
			if (conn instanceof BacnetIpConn) {
				((BacnetIpConn) conn).parseBroadcastManagementDevice();
			}
			return conn;

		} else {
			return null;
		}
	}
	
	void init() {
		statnode.setValue(new Value(NODE_STATUS_SETTING_UP_CONNECTION));
		
		makeRemoveAction();
		makeEditAction();
		
		try {
			monitor.checkInWriter();
			try {
				Network network = getNetwork();
				Transport transport = new DefaultTransport(network);
				network.setTransport(transport);
				transport.setRetries(retries);
				transport.setTimeout(timeout);
				transport.setSegTimeout(segmentTimeout);
				transport.setSegWindow(segmentWindow);
				localDevice = new LocalDevice(localDeviceId, transport);
				registerAsForeignDevice(transport);
				try {
					localDevice.writePropertyInternal(PropertyIdentifier.objectName,new CharacterString(localDeviceName));
					localDevice.writePropertyInternal(PropertyIdentifier.vendorName, new CharacterString(localDeviceVendor));
				} catch (Exception e1) {
					LOGGER.debug("", e1);
				}
				localDevice.getEventHandler().addListener(this);
				localDevice.initialize();
				localDevice.sendGlobalBroadcast(localDevice.getIAm());
				LOGGER.info("sent IAm - " + localDevice.getInstanceNumber());
//				localDevice.sendGlobalBroadcast(new WhoIsRequest());
			} catch (Exception e) {
				LOGGER.debug("", e);
				statnode.setValue(new Value("Error in initializing local device :" + e.getMessage()));
				if (localDevice != null && localDevice.isInitialized()) {
					localDevice.terminate();
				}
				localDevice = null;
			}
			monitor.checkOutWriter();
		} catch (InterruptedException e) {
			
		}
		
		if (!NODE_STATUS_STOPPED.equals(statnode.getValue().getString())) {
			makeStopAction();
		}
		makeRestartAction();
		
		if (localDevice != null) {
			statnode.setValue(new Value(NODE_STATUS_CONNECTED));
			makeDiscoverAction();
			makeAddDiscoveredDeviceAction();
			makeAddDeviceByNumberAction();
			makeAddDeviceByAddressAction();
			makeAddAllAction();
		}
		makeExportAction();
		makeImportAction();
		
		localController.init();
		
	}
	
	abstract void registerAsForeignDevice(Transport transport);
	
	abstract Network getNetwork() throws Exception;
	
	void restoreLastSession() {
		init();
		if (node.getChildren() == null) {
			return;
		}

		for (Node child : node.getChildren().values()) {
			if (child.getAction() == null && !child.getName().equals(NODE_STATUS) && !child.getName().equals(NODE_LOCAL)) {
				BacnetDevice bd = new BacnetDevice(this, child, null);
				bd.restoreLastSession();
			}
		}
	}
	
	public ScheduledThreadPoolExecutor getStpe() {
		return stpe;
	}
	
	
	/////////////////////////////////////////////////////////////////////////////////////////
	// Actions
	/////////////////////////////////////////////////////////////////////////////////////////
	
	private void makeRemoveAction() {
		Action act = new Action(Permission.READ, new Handler<ActionResult>(){
			@Override
			public void handle(ActionResult event) {
				remove();
			}
		});
		Node anode = node.getChild(ACTION_REMOVE, true);
		if (anode == null) {
			node.createChild(ACTION_REMOVE, true).setAction(act).build().setSerializable(false);
		} else {
			anode.setAction(act);
		}
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
	
	protected void remove() {
		stop();
		node.delete(false);
	}
	
	protected void stop() {
		statnode.setValue(new Value(NODE_STATUS_STOPPED));
		try {
			monitor.checkInWriter();
			if (localDevice != null && localDevice.isInitialized()) {
				localDevice.terminate();
			}
			localDevice = null;
			monitor.checkOutWriter();
		} catch (InterruptedException e) {
			
		}
	}
	
	private void restart() {
		stop();
		init();
	}

	protected abstract void makeEditAction();
	
	protected void edit(ActionResult event) {
		Utils.setConfigsFromActionResult(node, event);
		setVarsFromConfigs();
		restart();
	}
	
	protected void setVarsFromConfigs() {
		localNetworkNumber = Utils.safeGetRoConfigNum(node, "Local Network Number", localNetworkNumber).intValue();
		timeout = Utils.safeGetRoConfigNum(node, "Timeout", timeout).intValue();
		segmentTimeout = Utils.safeGetRoConfigNum(node, "Segment Timeout", segmentTimeout).intValue();
		segmentWindow = Utils.safeGetRoConfigNum(node, "Segment Window", segmentWindow).intValue();
		retries = Utils.safeGetRoConfigNum(node, "Retries", retries).intValue();
		localDeviceId = Utils.safeGetRoConfigNum(node, "Local Device ID", localDeviceId).intValue();
		localDeviceName = Utils.safeGetRoConfigString(node, "Local Device Name", localDeviceName);
		localDeviceVendor = Utils.safeGetRoConfigString(node, "Local Device Vendor", localDeviceVendor);
	}
	
	private void makeDiscoverAction() {
		Action act = new Action(Permission.READ, new Handler<ActionResult>(){
			@Override
			public void handle(ActionResult event) {
				discover(event);
			}
		});
		act.addParameter(new Parameter("Device Instance Range Low Limit", ValueType.NUMBER));
		act.addParameter(new Parameter("Device Instance Range High Limit", ValueType.NUMBER));
		Node anode = node.getChild(ACTION_DISCOVER_DEVICES, true);
		if (anode == null) {
			node.createChild(ACTION_DISCOVER_DEVICES, true).setAction(act).build().setSerializable(false);
		} else {
			anode.setAction(act);
		}
	}
	
	private void discover(ActionResult event) {
		Value low = event.getParameter("Device Instance Range Low Limit");
		Value hi = event.getParameter("Device Instance Range High Limit");
		WhoIsRequest whoIs = (low == null || hi == null) ? new WhoIsRequest()
				: new WhoIsRequest(low.getNumber().intValue(), hi.getNumber().intValue());
		try {
			monitor.checkInReader();
			if (localDevice != null) {
				localDevice.sendGlobalBroadcast(whoIs);
			}
			monitor.checkOutReader();
		} catch (InterruptedException e) {

		}
//		int lastLength = 0;
//		for (int i=0; i<(timeout/500) + 2; i++) {
//			if (discovered.size() > lastLength) {
//				lastLength = discovered.size();
//				makeAddDiscoveredDeviceAction();
//			}
//			try {
//				Thread.sleep(500);
//			} catch (InterruptedException e) {
//			}
//		}
	}
	
	private void makeAddDiscoveredDeviceAction() {
//		LOGGER.info("updating add discovered device action");
		Action act = new Action(Permission.READ, new Handler<ActionResult>(){
			@Override
			public void handle(ActionResult event) {
				addDiscoveredDevice(event);
			}
		});
		
		act.addParameter(new Parameter("Device", ValueType.makeEnum(Utils.getDeviceEnum(discovered))));
		act.addParameter(new Parameter("Name", ValueType.STRING).setDescription("Optional - will be inferred from device if left blank"));
		act.addParameter(new Parameter("Polling Interval", ValueType.NUMBER, new Value(5)));
		act.addParameter(new Parameter("Get Confirmed COV Notifications", ValueType.BOOL, new Value(false)));
		act.addParameter(new Parameter("COV Lifetime", ValueType.NUMBER, new Value(0)));
		Node anode = node.getChild(ACTION_ADD_DEVICE, true);
		if (anode == null) {
			node.createChild(ACTION_ADD_DEVICE, true).setAction(act).setConfig("actionGroup", new Value("Add Device")).setConfig("actionGroupSubTitle", new Value("From Discovered")).build().setSerializable(false);
		} else {
//			LOGGER.info("actually updating add discovered device action");
			anode.setAction(act);
		}
	}
	
	private void makeAddDeviceByNumberAction() {
		Action act = new Action(Permission.READ, new Handler<ActionResult>(){
			@Override
			public void handle(ActionResult event) {
				addDeviceByNumber(event);
			}
		});
		
		act.addParameter(new Parameter("Name", ValueType.STRING));
		act.addParameter(new Parameter("Instance Number", ValueType.NUMBER));
		act.addParameter(new Parameter("Polling Interval", ValueType.NUMBER, new Value(5)));
		act.addParameter(new Parameter("Get Confirmed COV Notifications", ValueType.BOOL, new Value(false)));
		act.addParameter(new Parameter("COV Lifetime", ValueType.NUMBER, new Value(0)));
		Node anode = node.getChild(ACTION_ADD_DEVICE_BY_NUMBER, true);
		if (anode == null) {
			node.createChild(ACTION_ADD_DEVICE_BY_NUMBER, true).setAction(act).setConfig("actionGroup", new Value("Add Device")).setConfig("actionGroupSubTitle", new Value("By Instance Number")).build().setSerializable(false);
		} else {
			anode.setAction(act);
		}
	}
	
	private void makeAddDeviceByAddressAction() {
		Action act = new Action(Permission.READ, new Handler<ActionResult>(){
			@Override
			public void handle(ActionResult event) {
				addDeviceByAddress(event);
			}	
		});
		
		act.addParameter(new Parameter("Name", ValueType.STRING));
		act.addParameter(new Parameter("Network Number", ValueType.NUMBER, new Value(0)));
		act.addParameter(new Parameter("Address", ValueType.STRING));
		act.addParameter(new Parameter("Polling Interval", ValueType.NUMBER, new Value(5)));
		act.addParameter(new Parameter("Get Confirmed COV Notifications", ValueType.BOOL, new Value(false)));
		act.addParameter(new Parameter("COV Lifetime", ValueType.NUMBER, new Value(0)));
		Node anode = node.getChild(ACTION_ADD_DEVICE_BY_ADDR, true);
		if (anode == null) {
			node.createChild(ACTION_ADD_DEVICE_BY_ADDR, true).setAction(act).setConfig("actionGroup", new Value("Add Device")).setConfig("actionGroupSubTitle", new Value("By Address")).build().setSerializable(false);
		} else {
			anode.setAction(act);
		}
	}
	
	private void makeAddAllAction() {
		Action act = new Action(Permission.READ, new Handler<ActionResult>(){
			@Override
			public void handle(ActionResult event) {
				addAllDiscovered(event);
			}
		});
		act.addParameter(new Parameter("Polling Interval", ValueType.NUMBER, new Value(5)));
		act.addParameter(new Parameter("Get Confirmed COV Notifications", ValueType.BOOL, new Value(false)));
		act.addParameter(new Parameter("COV Lifetime", ValueType.NUMBER, new Value(0)));
		Node anode = node.getChild(ACTION_ADD_ALL, true);
		if (anode == null) {
			node.createChild(ACTION_ADD_ALL, true).setAction(act).setDisplayName("Add All Discovered Devices").build().setSerializable(false);
		} else {
			anode.setAction(act);
		}
	}
	
	private void addAllDiscovered(ActionResult event) {
		for (Entry<Integer, RemoteDevice> entry: discovered.entrySet()) {
			Integer inst = entry.getKey();
			RemoteDevice d = entry.getValue();
			if (d == null || d.getDeviceProperty(PropertyIdentifier.objectName) == null) {
				continue;
			}
			String name = d.getName();
			Node child = node.createChild(name, true).build();
			child.setRoConfig("Instance Number", new Value(inst));
			Utils.setConfigsFromActionResult(child, event);
			BacnetDevice bd = new BacnetDevice(this, child, d);
			bd.init();
		}
	}
	
	private void addDiscoveredDevice(ActionResult event) {
		String devStr = event.getParameter("Device", ValueType.STRING).getString();
		String[] arr = devStr.split("\\(Instance ");
		String instStr = arr[arr.length - 1].split(" at")[0];
		Integer inst;
		try {
			inst = Integer.valueOf(instStr);
		} catch (Exception e) {
			return;
		}
		RemoteDevice d = discovered.get(inst);
		event.getParameters().remove("Device");
		event.getParameters().put("Instance Number", inst);
		addDevice(event, d);
	}
	
	private void addDeviceByNumber(ActionResult event) {
		int inst = event.getParameter("Instance Number", ValueType.NUMBER).getNumber().intValue();
		RemoteDevice d = null;
		try {
			monitor.checkInReader();
			if (localDevice != null) {
				try {
					d = localDevice.getRemoteDeviceBlocking(inst);
				} catch (BACnetException e) {
					LOGGER.debug("" ,e);
				}
			}
			monitor.checkOutReader();
		} catch (InterruptedException e) {
			
		}
		addDevice(event, d);
	}
	
	private void addDeviceByAddress(ActionResult event) {
		int netNum = event.getParameter("Network Number").getNumber().intValue();
		String addrStr = event.getParameter("Address", ValueType.STRING).getString();
		final Address address = Utils.toAddress(netNum, addrStr);
		RemoteDevice d = findRemoteDeviceByAddress(address);
		addDevice(event, d);
	}
	
	RemoteDevice findRemoteDeviceByAddress(final Address address) {
		if (address == null) {
			return null;
		}
		final Queue<RemoteDevice> wrapper = new ConcurrentLinkedQueue<RemoteDevice>();
		DeviceEventListener listener = new DeviceEventListener() {
			@Override
			public void listenerException(Throwable e) {
			}
			@Override
			public void iAmReceived(RemoteDevice d) {
				if (d.getAddress().equals(address)) {
					synchronized(this) {
						wrapper.add(d);
						this.notifyAll();
					}
				}
			}
			@Override
			public boolean allowPropertyWrite(Address from, BACnetObject obj, PropertyValue pv) {
				return true;
			}
			@Override
			public void propertyWritten(Address from, BACnetObject obj, PropertyValue pv) {
			}
			@Override
			public void iHaveReceived(RemoteDevice d, RemoteObject o) {
			}
			@Override
			public void covNotificationReceived(UnsignedInteger subscriberProcessIdentifier,
					ObjectIdentifier initiatingDeviceIdentifier, ObjectIdentifier monitoredObjectIdentifier,
					UnsignedInteger timeRemaining, SequenceOf<PropertyValue> listOfValues) {
			}
			@Override
			public void eventNotificationReceived(UnsignedInteger processIdentifier,
					ObjectIdentifier initiatingDeviceIdentifier, ObjectIdentifier eventObjectIdentifier,
					TimeStamp timeStamp, UnsignedInteger notificationClass, UnsignedInteger priority,
					EventType eventType, CharacterString messageText, NotifyType notifyType,
					Boolean ackRequired, EventState fromState, EventState toState,
					NotificationParameters eventValues) {
			}
			@Override
			public void textMessageReceived(ObjectIdentifier textMessageSourceDevice, Choice messageClass,
					MessagePriority messagePriority, CharacterString message) {
			}
			@Override
			public void synchronizeTime(Address from, DateTime dateTime, boolean utc) {
			}
			@Override
			public void requestReceived(Address from, Service service) {
			}
		};
		boolean disconnected = false;
		try {
			monitor.checkInReader();
			if (localDevice != null) {
				localDevice.getEventHandler().addListener(listener);
				localDevice.send(address, new WhoIsRequest());
			} else {
				disconnected = true;
			}
			monitor.checkOutReader();
		} catch (InterruptedException e) {
			
		}
		if (disconnected) {
			return null;
		}
		synchronized(listener) {
			if(wrapper.isEmpty()) {
				try {
					listener.wait(timeout + 1000);
				} catch (InterruptedException e) {
					
				}
			}
			try {
				monitor.checkInReader();
				if (localDevice != null) {
					localDevice.getEventHandler().removeListener(listener);
				}
				monitor.checkOutReader();
			} catch (InterruptedException e) {
				
			}
			return wrapper.poll();
		}
	}
	
	private void addDevice(ActionResult event, RemoteDevice d) {
		
		String name = null;
		Value nameVal = event.getParameter("Name");
		if (nameVal != null) {
			name = nameVal.getString();
		}
		if ((name == null || name.trim().isEmpty()) && d != null && d.getDeviceProperty(PropertyIdentifier.objectName) != null) {
			name = d.getName();
		}
		if (name == null || name.trim().isEmpty()) {
			return;
		}
		
		Node child = node.createChild(name, true).build();
		Utils.setConfigsFromActionResult(child, event);
		BacnetDevice bd = new BacnetDevice(this, child, d);
		bd.init();
	}
	
	private void makeExportAction() {
		Action act = new Action(Permission.READ, new Handler<ActionResult>(){
			@Override
			public void handle(ActionResult event) {
				handleExport(event);
			}
		});
		act.addResult(new Parameter("JSON", ValueType.STRING).setEditorType(EditorType.TEXT_AREA));
		Node anode = node.getChild(ACTION_EXPORT, true);
		if (anode == null) {
			node.createChild(ACTION_EXPORT, true).setAction(act).build().setSerializable(false);
		} else {
			anode.setAction(act);
		}
	}
	
	private void handleExport(ActionResult event) {
		try {
			Method serMethod = Serializer.class.getDeclaredMethod("serializeChildren", JsonObject.class, Node.class);
			serMethod.setAccessible(true);
			JsonObject childOut = new JsonObject();
			serMethod.invoke(link.serializer, childOut, node);
			String retval = childOut.toString();
			event.getTable().addRow(Row.make(new Value(retval)));
		} catch (NoSuchMethodException | SecurityException | IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
			LOGGER.debug("", e);
		}
	}
	
	private void makeImportAction() {
		Action act = new Action(Permission.READ, new Handler<ActionResult>(){
			@Override
			public void handle(ActionResult event) {
				handleImport(event);
			}
		});
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
			deserMethod.invoke(link.deserializer, child, children);
			BacnetDevice bd = new BacnetDevice(this, child, null);
			bd.restoreLastSession();
		} catch (SecurityException | IllegalArgumentException | NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
			LOGGER.debug("", e);
			child.delete(false);
		}
	}
	

	///////////////////////////////////////////////////////////////////////////////////////////
	// Listener Methods
	///////////////////////////////////////////////////////////////////////////////////////////
	
	@Override
	public void listenerException(Throwable e) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void iAmReceived(final RemoteDevice d) {
		LOGGER.debug("iAm recieved: " + d);
		discovered.put(d.getInstanceNumber(), d);
		Objects.getDaemonThreadPool().schedule(new Runnable() {
			@Override
			public void run() {
				deviceDiscovered(d);
			}
		}, 0, TimeUnit.MILLISECONDS);	
	}
	
	private void deviceDiscovered(RemoteDevice d) {
		if (d == null) {
			return;
		}
		Encodable enc = null;
		try {
			monitor.checkInReader();
			try {
				enc = RequestUtils.sendReadPropertyAllowNull(localDevice, d, d.getObjectIdentifier(), PropertyIdentifier.objectName);
			} catch (BACnetException e) {
				LOGGER.debug("", e);
			}
			monitor.checkOutReader();
		} catch (InterruptedException e1) {
		}
		if (enc != null) {
			d.setDeviceProperty(PropertyIdentifier.objectName, enc);
		}
		makeAddDiscoveredDeviceAction();
		LOGGER.trace("iAm processed: " + d);
//		if (discoveryLock.tryLock()) {
//			try {
//				Thread.sleep(500);
//				makeAddDiscoveredDeviceAction();
//			} catch (InterruptedException e) {
//			} finally {
//				discoveryLock.unlock();
//			}	
//		}
		
	}
	
	public long addCovSub(BacnetObject obj) {
		synchronized(covSubsLock) {
			long id = random.nextLong();
			while (covSubs.containsKey(id)) {
				id = random.nextLong();
			}
			covSubs.put(id, obj);
			return id;
		}
	}
	
	public void removeCovSub(long id) {
		synchronized(covSubsLock) {
			covSubs.remove(id);
		}
	}

	@Override
	public boolean allowPropertyWrite(Address from, BACnetObject obj, PropertyValue pv) {
		// TODO Auto-generated method stub
		return true;
	}

	@Override
	public void propertyWritten(Address from, BACnetObject obj, PropertyValue pv) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void iHaveReceived(RemoteDevice d, RemoteObject o) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void covNotificationReceived(UnsignedInteger subscriberProcessIdentifier,
			ObjectIdentifier initiatingDeviceIdentifier, ObjectIdentifier monitoredObjectIdentifier,
			UnsignedInteger timeRemaining, SequenceOf<PropertyValue> listOfValues) {
//		LOGGER.info("got COV notification for sub id " + subscriberProcessIdentifier + ", device " 
//			+ initiatingDeviceIdentifier.getInstanceNumber() + ", object " + monitoredObjectIdentifier + ": "
//			+ listOfValues + " (remaining time: " + timeRemaining + ")");
		BacnetObject obj = covSubs.get(subscriberProcessIdentifier.longValue());
		if (obj != null) {
			obj.covNotificationReceived(timeRemaining, listOfValues);
		}
	}

	@Override
	public void eventNotificationReceived(UnsignedInteger processIdentifier,
			ObjectIdentifier initiatingDeviceIdentifier, ObjectIdentifier eventObjectIdentifier, TimeStamp timeStamp,
			UnsignedInteger notificationClass, UnsignedInteger priority, EventType eventType,
			CharacterString messageText, NotifyType notifyType, Boolean ackRequired, EventState fromState,
			EventState toState, NotificationParameters eventValues) {
		for (BacnetDevice dev : devices) {
			if (initiatingDeviceIdentifier.getInstanceNumber() == dev.instanceNumber) {
				dev.eventNotificationReceived(processIdentifier, eventObjectIdentifier, timeStamp, notificationClass,
						priority, eventType, messageText, notifyType, ackRequired, fromState, toState, eventValues);

			}
		}

	}

	@Override
	public void textMessageReceived(ObjectIdentifier textMessageSourceDevice, Choice messageClass,
			MessagePriority messagePriority, CharacterString message) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void synchronizeTime(Address from, DateTime dateTime, boolean utc) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void requestReceived(Address from, Service service) {
		// TODO Auto-generated method stub
		
	}


}
